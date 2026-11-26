package com.nuvio.app.features.lisy

import com.nuvio.app.features.profiles.ProfileRepository
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class LisyServer(val name: String, val url: String, val mac: String)
internal enum class LisyServerValidation { Name, Url, Mac }
internal fun validateLisyServer(name: String, url: String, mac: String): LisyServerValidation? {
    if (name.trim().isEmpty()) return LisyServerValidation.Name
    val address = runCatching { Url(url.trim()) }.getOrNull()
    if (!Regex("(?i)^https?://.+").matches(url.trim()) || address == null || address.protocol.name !in listOf("http", "https") || address.host.isBlank() || url.any(Char::isWhitespace)) return LisyServerValidation.Url
    if (!Regex("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}").matches(mac.trim())) return LisyServerValidation.Mac
    return null
}
internal object LisyServerRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private var loadedProfile: Int? = null
    private val state = MutableStateFlow<LisyServer?>(null)
    val uiState = state.asStateFlow()
    private val hasServer = MutableStateFlow(false)
    val configured = hasServer.asStateFlow()
    fun ensureLoaded(profileId: Int = ProfileRepository.activeProfileId) {
        if (loadedProfile == profileId) return
        val server = LisyServerStorage.load(profileId)?.let { runCatching { json.decodeFromString<LisyServer>(it) }.getOrNull() }
            ?.takeIf { validateLisyServer(it.name, it.url, it.mac) == null }
        state.value = server
        hasServer.value = server != null
        loadedProfile = profileId
    }
    fun save(name: String, url: String, mac: String): LisyServerValidation? {
        validateLisyServer(name, url, mac)?.let { return it }
        ensureLoaded()
        val server = LisyServer(name.trim(), url.trim(), mac.trim().replace('-', ':').uppercase())
        LisyServerStorage.save(loadedProfile!!, json.encodeToString(LisyServer.serializer(), server))
        state.value = server
        hasServer.value = true
        return null
    }
    fun remove() {
        ensureLoaded()
        LisyServerStorage.save(loadedProfile!!, null)
        state.value = null
        hasServer.value = false
    }
}
internal expect object LisyServerStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, value: String?)
    fun loadTvPreference(key: String): String?
    fun saveTvPreference(key: String, value: String)
}
