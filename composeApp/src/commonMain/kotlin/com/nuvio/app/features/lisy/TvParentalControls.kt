package com.nuvio.app.features.lisy

import com.nuvio.app.features.profiles.PinVerifyResult
import com.nuvio.app.features.simkl.SimklPkceCrypto
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal const val TV_PARENTAL_PIN_ITERATIONS = 210_000

internal data class TvParentalState(val configured: Boolean = false, val unlocked: Boolean = false)

internal object TvParentalControls {
    private const val storageKey = "lisyx_parental_pin_v1"
    private val gate = TvParentalGate(
        read = { LisyServerStorage.loadTvPreference(storageKey) },
        write = { LisyServerStorage.saveTvPreference(storageKey, it) },
    )
    val state get() = gate.state
    fun ensureLoaded() = gate.ensureLoaded()
    fun bind(profileId: Int, server: LisyServer?) = gate.bind(profileId, server)
    fun isUnlocked(profileId: Int, server: LisyServer?): Boolean = gate.isUnlocked(profileId, server)
    fun lock() = gate.lock()
    suspend fun unlock(pin: String, profileId: Int, server: LisyServer?): PinVerifyResult =
        gate.unlock(pin, profileId, server).toPinResult()
    suspend fun setPin(pin: String, confirmation: String, currentPin: String? = null): PinVerifyResult =
        gate.setPin(pin, confirmation, currentPin).toPinResult()
}

internal enum class TvParentalError { NotConfigured, InvalidPin, Confirmation, Incorrect, Blocked, Storage, ContextChanged }

internal data class TvParentalResult(
    val success: Boolean,
    val error: TvParentalError? = null,
    val retryAfterSeconds: Int = 0,
)

private suspend fun TvParentalResult.toPinResult(): PinVerifyResult = PinVerifyResult(
    unlocked = success,
    retryAfterSeconds = retryAfterSeconds,
    message = error?.let {
        when (it) {
            TvParentalError.NotConfigured -> getString(Res.string.tv_parental_not_configured)
            TvParentalError.InvalidPin -> getString(Res.string.tv_parental_invalid_pin)
            TvParentalError.Confirmation -> getString(Res.string.tv_parental_confirmation_error)
            TvParentalError.Incorrect -> getString(Res.string.pin_incorrect)
            TvParentalError.Blocked -> getString(Res.string.pin_locked_try_again, retryAfterSeconds)
            TvParentalError.Storage -> getString(Res.string.tv_parental_storage_error)
            TvParentalError.ContextChanged -> getString(Res.string.tv_parental_context_changed)
        }
    },
)

@Serializable
private data class TvParentalRecord(
    val version: Int = 1,
    val salt: String,
    val digest: String,
    val failures: Int = 0,
    val blockedUntil: Long = 0,
)

internal class TvParentalGate(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val operationMutex = Mutex()
    private val sessionLock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(TvParentalState())
    val state get() = mutableState.asStateFlow().also { ensureLoaded() }
    private var loaded = false
    private var readable = true
    private var record: TvParentalRecord? = null
    private var context: Pair<Int, String?>? = null
    private var generation = 0
    private var unlockedUntil = 0L
    private var expiration: Job? = null

    fun ensureLoaded() = synchronized(sessionLock) {
        if (loaded) return@synchronized
        loaded = true
        val raw = runCatching(read).getOrElse {
            readable = false
            mutableState.value = TvParentalState(configured = true)
            return@synchronized
        }
        record = raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<TvParentalRecord>(it) }.getOrNull()
        }
        readable = raw.isNullOrBlank() || record?.let {
            it.version == 1 && it.salt.matches(Regex("[0-9a-f]{64}")) &&
                it.digest.matches(Regex("[0-9a-f]{64}")) && it.failures in 0..30 && it.blockedUntil >= 0
        } == true
        mutableState.value = TvParentalState(configured = !raw.isNullOrBlank())
    }

    fun bind(profileId: Int, server: LisyServer?) = synchronized(sessionLock) {
        ensureLoaded()
        val binding = profileId to server?.let {
            it.url.trimEnd('/') + "|" + it.mac.replace(":", "").replace("-", "").uppercase()
        }
        if (context != binding) {
            context = binding
            lock()
        }
    }

    fun isUnlocked(profileId: Int, server: LisyServer?): Boolean = synchronized(sessionLock) {
        bind(profileId, server)
        if (mutableState.value.unlocked && now() >= unlockedUntil) lock()
        mutableState.value.unlocked
    }

    fun lock() = synchronized(sessionLock) {
        generation++
        unlockedUntil = 0
        expiration?.cancel()
        expiration = null
        mutableState.value = mutableState.value.copy(unlocked = false)
    }

    suspend fun unlock(pin: String, profileId: Int, server: LisyServer?): TvParentalResult =
        operationMutex.withLock {
            bind(profileId, server)
            lock()
            val requestGeneration = synchronized(sessionLock) { generation }
            val result = verify(pin)
            if (!result.success) return@withLock result
            synchronized(sessionLock) {
                if (generation != requestGeneration) {
                    return@synchronized TvParentalResult(false, TvParentalError.ContextChanged)
                }
                unlockedUntil = now() + 15 * 60
                mutableState.value = mutableState.value.copy(unlocked = true)
                expiration?.cancel()
                expiration = scope.launch {
                    delay(15 * 60 * 1000L)
                    synchronized(sessionLock) { if (generation == requestGeneration) lock() }
                }
                result
            }
        }

    suspend fun setPin(pin: String, confirmation: String, currentPin: String? = null): TvParentalResult =
        operationMutex.withLock {
            ensureLoaded()
            lock()
            if (!validPin(pin)) return@withLock TvParentalResult(false, TvParentalError.InvalidPin)
            if (pin != confirmation) return@withLock TvParentalResult(false, TvParentalError.Confirmation)
            if (!readable) return@withLock TvParentalResult(false, TvParentalError.Storage)
            if (record != null) {
                val verified = verify(currentPin.orEmpty())
                if (!verified.success) return@withLock verified
            }
            val credential = withContext(Dispatchers.Default) {
                val salt = SimklPkceCrypto.secureRandomBytes(32)
                TvParentalRecord(salt = salt.toHexString(), digest = TvParentalCrypto.derive(pin, salt).toHexString())
            }
            if (!persist(credential)) return@withLock TvParentalResult(false, TvParentalError.Storage)
            lock()
            mutableState.value = TvParentalState(configured = true)
            TvParentalResult(true)
        }

    private suspend fun verify(pin: String): TvParentalResult {
        if (!readable) return TvParentalResult(false, TvParentalError.Storage)
        val credential = record ?: return TvParentalResult(false, TvParentalError.NotConfigured)
        val retry = (credential.blockedUntil - now()).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        if (retry > 0) return TvParentalResult(false, TvParentalError.Blocked, retry)
        val matches = validPin(pin) && withContext(Dispatchers.Default) {
            val actual = TvParentalCrypto.derive(pin, credential.salt.hexToByteArray()).toHexString()
            actual.length == credential.digest.length && actual.indices.fold(0) { difference, index ->
                difference or (actual[index].code xor credential.digest[index].code)
            } == 0
        }
        val failures = if (matches) 0 else (credential.failures + 1).coerceAtMost(30)
        val cooldown = if (failures >= 5) minOf(300L, 60L shl ((failures - 5).coerceAtMost(3))) else 0
        if (!persist(credential.copy(failures = failures, blockedUntil = if (cooldown > 0) now() + cooldown else 0))) {
            lock()
            return TvParentalResult(false, TvParentalError.Storage)
        }
        return when {
            matches -> TvParentalResult(true)
            cooldown > 0 -> TvParentalResult(false, TvParentalError.Blocked, cooldown.toInt())
            else -> TvParentalResult(false, TvParentalError.Incorrect)
        }
    }

    private fun persist(value: TvParentalRecord): Boolean = runCatching {
        write(json.encodeToString(value))
        record = value
    }.isSuccess

    private fun validPin(value: String): Boolean = value.length == 4 && value.all { it in '0'..'9' }
}
