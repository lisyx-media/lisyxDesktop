package com.nuvio.app.features.lisy

import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.time.isoEpochDay
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Serializable
internal data class LiveTvGuideSnapshot(
    val savedAt: Long = 0,
    val programs: Map<String, List<LiveTvProgram>> = emptyMap(),
    val days: List<String> = emptyList(),
    val focus: LiveTvGuideFocus? = null,
)

internal interface LiveTvGuideStorage {
    suspend fun read(server: LisyServer, profile: Int): LiveTvGuideSnapshot?
    suspend fun write(server: LisyServer, profile: Int, snapshot: LiveTvGuideSnapshot)
}

internal expect fun platformGuideStorage(): LiveTvGuideStorage?

/** The accumulated searchable guide outlives individual TV and player screens. */
internal class LiveTvGuideIndex(
    private val storage: LiveTvGuideStorage?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    private class Entry {
        val mutex = Mutex()
        val diskMutex = Mutex()
        val state = MutableStateFlow(LiveTvGuideSnapshot())
        var restored = false
        var pendingWrite: Job? = null
    }

    // Keep caller cancellation while using the same dispatcher as background persistence.
    private val worker = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
    private val lock = SynchronizedObject()
    private data class Key(val profile: Int, val url: String, val mac: String)
    private val entries = linkedMapOf<Key, Entry>()

    private fun entry(server: LisyServer, profile: Int): Entry = synchronized(lock) {
        val key = Key(profile, server.url.trimEnd('/'), server.mac.uppercase())
        entries[key]?.let { return@synchronized it }
        if (entries.size >= 4) {
            val inactive = entries.entries.firstOrNull { it.value.state.subscriptionCount.value == 0 }
                ?: entries.entries.first()
            entries.remove(inactive.key)
        }
        Entry().also { entries[key] = it }
    }

    fun state(server: LisyServer, profile: Int): StateFlow<LiveTvGuideSnapshot> =
        entry(server, profile).state.asStateFlow()

    suspend fun restore(server: LisyServer, profile: Int): Unit = withContext(worker) {
        restore(server, profile, entry(server, profile))
    }

    private suspend fun restore(server: LisyServer, profile: Int, entry: Entry) {
        entry.mutex.withLock {
            if (entry.restored) return
            val timestamp = now()
            val saved = try {
                storage?.read(server, profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }?.takeIf { timestamp - it.savedAt in 0..7.days.inWholeSeconds }
            if (saved != null) {
                // Every merge first restores this entry under the same mutex.
                entry.state.value = saved.copy(
                    programs = mergeLiveTvGuide(emptyMap(), saved.programs, timestamp, saved.focus),
                    days = saved.days.distinct().sorted(),
                )
            }
            entry.restored = true
        }
    }

    suspend fun merge(
        server: LisyServer,
        profile: Int,
        updates: Map<String, List<LiveTvProgram>>,
        calendar: List<String>? = null,
        focus: LiveTvGuideFocus? = null,
    ): Unit = withContext(worker) {
        val entry = entry(server, profile)
        restore(server, profile, entry)
        entry.mutex.withLock {
            val previous = entry.state.value
            val retainedFocus = focus ?: previous.focus
            val timestamp = now()
            val programs = if (updates.isEmpty()) previous.programs else
                mergeLiveTvGuide(previous.programs, updates, timestamp, retainedFocus)
            val days = when {
                !calendar.isNullOrEmpty() -> calendar.distinct().sorted()
                updates.isEmpty() -> previous.days
                else -> (previous.days + coveredGuideDays(updates)).distinct().sorted()
            }
            if (programs == previous.programs && days == previous.days && retainedFocus == previous.focus) return@withLock
            entry.state.value = LiveTvGuideSnapshot(timestamp, programs, days, retainedFocus)
            entry.pendingWrite?.cancel()
            if (storage != null) {
                entry.pendingWrite = scope.launch {
                    delay(750)
                    persist(server, profile, entry)
                }
            }
        }
    }

    suspend fun flush(server: LisyServer, profile: Int) {
        val entry = entry(server, profile)
        entry.pendingWrite?.cancelAndJoin()
        persist(server, profile, entry)
    }

    private suspend fun persist(server: LisyServer, profile: Int, entry: Entry) {
        entry.diskMutex.withLock {
            try {
                storage?.write(server, profile, entry.state.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The live guide remains searchable when the disk cache cannot be written.
            }
        }
    }
}


/** Memoize the actual local midnight boundaries, including 23- and 25-hour days. */
internal fun coveredGuideDays(
    updates: Map<String, List<LiveTvProgram>>,
    dateAt: (Long) -> String? = EpisodeReleaseDatePlatform::localIsoDateAtEpochMs,
    localMidnight: (String) -> Long? = CurrentDateProvider::localStartOfDayEpochMs,
): Set<String> {
    val days = mutableSetOf<String>()
    val rangesByUtcDay = mutableMapOf<Long, MutableList<LongRange>>()
    for (station in updates.values) for (program in station) {
        val start = program.start
        val utcDay = start.floorDiv(86_400L)
        if (rangesByUtcDay[utcDay]?.any { start in it } == true) continue
        val date = dateAt(start * 1000) ?: continue
        if (!days.add(date)) continue
        val midnight = localMidnight(date)?.div(1000) ?: continue
        val nextDate = Instant.fromEpochSeconds((isoEpochDay(date) + 1) * 86_400).toString().take(10)
        val nextMidnight = localMidnight(nextDate)?.div(1000) ?: continue
        val range = midnight until nextMidnight
        for (bucket in midnight.floorDiv(86_400L)..(nextMidnight - 1).floorDiv(86_400L)) {
            rangesByUtcDay.getOrPut(bucket) { mutableListOf() }.add(range)
        }
    }
    return days
}
