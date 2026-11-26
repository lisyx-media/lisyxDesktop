package com.nuvio.app.features.lisy

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal object LiveTvCache {
    val guideIndex = LiveTvGuideIndex(platformGuideStorage())
    private val lineups = LiveTvLineups(platformLineupStorage())
    private val guides = Cache<Map<String, List<LiveTvProgram>>>(15.minutes)

    suspend fun channels(server: LisyServer, portal: LisyPortal) =
        TvSourceSeparations.apply(server, lineups.get(server, ProfileRepository.activeProfileId, portal::liveChannels))

    suspend fun channelForPlayback(server: LisyServer?, parentId: String): LiveTvChannel? {
        if (server == null) return null
        val station = parentId.removePrefix("lisy-tv:")
        return try {
            channels(server, LisyPortal(server)).firstOrNull { it.id == station || it.variants.any { v -> v.entry.id == station } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun guide(server: LisyServer, portal: LisyPortal) =
        guides.get(server, portal::liveGuide)

    suspend fun guideWindow(
        portal: LisyPortal,
        start: Long,
        end: Long,
        channels: List<LiveTvChannel>,
        forceRefresh: Boolean = false,
        knownGuide: Map<String, List<LiveTvProgram>> = emptyMap(),
        refreshKnown: Boolean = false,
        onLoading: () -> Unit = {},
        onChannel: suspend (LiveTvChannel, List<LiveTvProgram>) -> Unit,
    ): Boolean = coroutineScope {
        if (end <= start) return@coroutineScope true
        val firstDay = EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(start * 1000)
        val lastDay = EpisodeReleaseDatePlatform.localIsoDateAtEpochMs((end - 1) * 1000)
        val midnight = firstDay?.let(com.nuvio.app.features.watchprogress.CurrentDateProvider::localStartOfDayEpochMs)?.div(1000)
        // A programme that spans midnight can belong to yesterday's table. Check it only
        // after today's schedule and only if the requested early-morning interval still has a gap.
        val previousDay = midnight?.takeIf { start - it < 4 * 3600 }
            ?.let { EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(it * 1000 - 1) }
        val days = listOfNotNull(firstDay, lastDay, previousDay).distinct()
        val stations = Semaphore(3)
        channels.distinctBy { it.id }.take(24).map { channel ->
            async {
                stations.withPermit {
                    val indexed = if (forceRefresh || refreshKnown) emptyList() else channel.variants.flatMap { variant ->
                        knownGuide[variant.entry.id].orEmpty().filter { it.start < end && it.end > start }
                    }
                    if (coversGuideWindow(indexed, start, end)) return@withPermit true
                    onLoading()
                    val received = mutableListOf<LiveTvProgram>()
                    var failure = false
                    for (day in days) {
                        try {
                            received += portal.guideForDay(channel, day, forceRefresh, windowStart = start,
                                windowEnd = end, knownWindow = indexed + received)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failure = true
                        }
                        if (coversGuideWindow(indexed + received, start, end)) break
                    }
                    if (received.isNotEmpty()) onChannel(channel, received.distinctBy { it.start }.sortedBy { it.start })
                    !failure || coversGuideWindow(indexed + received, start, end)
                }
            }
        }.awaitAll().all { it }
    }

    private class Cache<T>(private val lifetime: Duration) {
        private val mutex = Mutex()
        private val entries = mutableMapOf<LisyServer, Pair<TimeMark, T>>()

        suspend fun get(server: LisyServer, load: suspend () -> T): T = mutex.withLock {
            entries[server]?.takeIf { it.first.elapsedNow() < lifetime }?.let { return@withLock it.second }
            val value = load()
            if (entries.size >= 4 && server !in entries) entries.remove(entries.keys.first())
            entries[server] = TimeSource.Monotonic.markNow() to value
            value
        }
    }
}

@Serializable
internal data class LiveTvProgram(
    val name: String,
    val start: Long,
    val end: Long,
    val description: String = "",
    val category: String? = null,
    val imageUrl: String? = null,
    val portalStartTime: String? = null,
)

private const val LINEUP_CLASSIFICATION_VERSION = 6

@Serializable
internal data class LiveTvLineupSnapshot(
    val savedAt: Long,
    val channels: List<LiveTvChannel>,
    val classificationVersion: Int = 0,
)

internal interface LiveTvLineupStorage {
    suspend fun read(server: LisyServer, profile: Int): LiveTvLineupSnapshot?
    suspend fun write(server: LisyServer, profile: Int, snapshot: LiveTvLineupSnapshot)
}

internal expect fun platformLineupStorage(): LiveTvLineupStorage?

internal class LiveTvLineups(
    private val storage: LiveTvLineupStorage?,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<Pair<Int, LisyServer>, LiveTvLineupSnapshot>()

    suspend fun get(server: LisyServer, profile: Int, load: suspend () -> List<LiveTvChannel>): List<LiveTvChannel> =
        mutex.withLock {
            val key = profile to server
            val previous = entries[key] ?: storage?.read(server, profile)
            val snapshot = if (previous?.classificationVersion == LINEUP_CLASSIFICATION_VERSION && now() - previous.savedAt in 0 until 6.hours.inWholeSeconds) {
                previous
            } else {
                try {
                    val channels = load()
                    LiveTvLineupSnapshot(now(), channels, LINEUP_CLASSIFICATION_VERSION).also { storage?.write(server, profile, it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Older lineups discarded provider lock metadata. Refresh them before use.
                    if (previous?.classificationVersion != LINEUP_CLASSIFICATION_VERSION) throw failure
                    previous
                }
            }
            if (entries.size >= 4 && key !in entries) entries.remove(entries.keys.first())
            entries[key] = snapshot
            snapshot.channels
        }
}


@Serializable
internal data class LiveTvGuideFocus(
    val start: Long,
    val end: Long,
    val stationIds: Set<String>,
)

/**
 * Updates only changed station lists. The requested day is retained before older broadcasts;
 * whole stations outside the viewport are evicted first when the shared budget is reached.
 */
internal fun mergeLiveTvGuide(
    previous: Map<String, List<LiveTvProgram>>,
    updates: Map<String, List<LiveTvProgram>>,
    now: Long,
    focus: LiveTvGuideFocus? = null,
    maxPrograms: Int = 30_000,
): Map<String, List<LiveTvProgram>> {
    require(maxPrograms > 0)
    val merged = previous.toMutableMap()
    for ((id, incoming) in updates) {
        if (incoming.isEmpty()) continue
        val broadcasts = (merged[id].orEmpty() + incoming).associateBy { it.start }.values
        val selected = if (broadcasts.size <= 750) {
            broadcasts.sortedBy { it.start }
        } else {
            val focusedStation = id in focus?.stationIds.orEmpty()
            broadcasts.sortedWith(
                compareByDescending<LiveTvProgram> {
                    focusedStation && focus != null && it.start < focus.end && it.end > focus.start
                }.thenBy {
                    kotlin.math.abs(it.start - if (focusedStation && focus != null) focus.start else now)
                },
            ).take(750).sortedBy { it.start }
        }
        // Preserve the insertion order as a station-level recency list.
        merged.remove(id)
        merged[id] = selected
    }
    var total = merged.values.sumOf { it.size }
    if (total <= maxPrograms) return merged
    for (id in merged.keys.toList()) {
        if (total <= maxPrograms) break
        if (id !in focus?.stationIds.orEmpty()) {
            total -= merged.remove(id).orEmpty().size
        }
    }
    // Normally at most 24 visible stations hold at most 750 programmes each.
    // Drop broadcasts outside their selected day before touching anything displayed.
    if (total > maxPrograms && focus != null) {
        for (id in focus.stationIds) {
            if (total <= maxPrograms) break
            val programs = merged[id] ?: continue
            val kept = programs.filter { it.start < focus.end && it.end > focus.start }
            total -= programs.size - kept.size
            merged[id] = kept
        }
    }
    return merged
}


internal fun coversGuideWindow(programs: List<LiveTvProgram>, start: Long, end: Long): Boolean {
    if (end <= start) return true
    var coveredUntil = start
    for (program in programs.filter { it.end > start && it.start < end }.sortedBy { it.start }) {
        if (program.start > coveredUntil) return false
        coveredUntil = maxOf(coveredUntil, program.end)
        if (coveredUntil >= end) return true
    }
    return false
}
