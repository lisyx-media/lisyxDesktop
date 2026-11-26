package com.nuvio.app.features.lisy

import com.nuvio.app.core.time.isoEpochDay
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

@Serializable
internal data class TvReplaySearchDay(
    val station: String,
    val date: String,
    val checkedAt: Long,
    val programs: List<LiveTvProgram>,
)

@Serializable
internal data class TvReplaySearchSnapshot(
    val revision: Long = 0,
    val days: List<TvReplaySearchDay> = emptyList(),
)

internal interface TvReplaySearchStorage {
    suspend fun read(server: LisyServer, profile: Int): TvReplaySearchSnapshot?
    suspend fun write(server: LisyServer, profile: Int, snapshot: TvReplaySearchSnapshot)
}

internal expect fun platformReplaySearchStorage(): TvReplaySearchStorage?

/** Separate from the viewport guide. [prepare]'s loader must return confirmed replay entries only. */
internal class TvReplaySearchIndex(
    private val storage: TvReplaySearchStorage?,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
    private val maxPrograms: Int = 100_000,
    private val maxDays: Int = 12_000,
) {
    companion object { val shared by lazy { TvReplaySearchIndex(platformReplaySearchStorage()) } }
    private data class Key(val profile: Int, val url: String, val mac: String)
    private data class NormalizedDay(val source: TvReplaySearchDay, val rows: List<Triple<String, LiveTvProgram, String>>)
    private class Entry {
        val worker = Mutex()
        val state = MutableStateFlow(TvReplaySearchSnapshot())
        var restored = false
        val normalizedDays = mutableMapOf<Pair<String, String>, NormalizedDay>()
    }
    private val lock = SynchronizedObject()
    private val entries = linkedMapOf<Key, Entry>()
    private fun entry(server: LisyServer, profile: Int): Entry = synchronized(lock) {
        val key = Key(profile, server.url.trimEnd('/'), server.mac.uppercase())
        entries[key] ?: Entry().also {
            // An active worker or observer must never be evicted mid-operation.
            if (entries.size >= 4) entries.entries.firstOrNull {
                !it.value.worker.isLocked && it.value.state.subscriptionCount.value == 0
            }?.let { entries.remove(it.key) }
            entries[key] = it
        }
    }
    fun state(server: LisyServer, profile: Int): StateFlow<TvReplaySearchSnapshot> = entry(server, profile).state.asStateFlow()

    suspend fun restore(server: LisyServer, profile: Int) {
        val e = entry(server, profile)
        e.worker.withLock { restore(server, profile, e) }
    }
    private suspend fun restore(server: LisyServer, profile: Int, e: Entry) {
        if (e.restored) return
        val saved = try { storage?.read(server, profile) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (saved != null) e.state.value = saved.copy(days = bounded(saved.days))
        e.restored = true
    }

    /** Accepts raw or already-normalized text. No network requests are made by search. */
    fun search(server: LisyServer, profile: Int, channels: List<LiveTvChannel>, query: String): List<Pair<LiveTvChannel, LiveTvProgram>> {
        val term = tvSearchTerm(query)
        if (term.isEmpty()) return emptyList()
        val allowed = channels.associateBy(::stationKey)
        val timestamp = now()
        val e = entry(server, profile)
        val rows = synchronized(lock) {
            val days = e.state.value.days
            val retained = days.map { it.station to it.date }.toSet()
            e.normalizedDays.keys.retainAll(retained)
            // The provider's archive marker is authoritative within its oldest partial day.
            // Some portals still serve a broadcast just past the advertised hourly cutoff.
            days.filter { allowed[it.station]?.replayCanCoverDate(it.date, timestamp) == true }.map { day ->
                val key = day.station to day.date
                val cached = e.normalizedDays[key]
                if (cached != null && cached.source === day) cached.rows else {
                    day.programs.map { Triple(day.station, it, tvSearchTerm(it.name)) }.also {
                        e.normalizedDays[key] = NormalizedDay(day, it)
                    }
                }
            }
        }
        return rows.asSequence().flatten().filter {
            it.third.contains(term) && it.second.end <= timestamp
        }
            .map { checkNotNull(allowed[it.first]) to it.second }.distinctBy { (station, p) -> station.id to p.start }.sortedByDescending { it.second.start }.toList()
    }

    /** Cancellation saves completed batches; failures remain retryable and are never cached as empty. */
    suspend fun prepare(
        server: LisyServer,
        profile: Int,
        channelsPrioritized: List<LiveTvChannel>,
        daysNewestFirst: List<String>,
        load: suspend (LiveTvChannel, String) -> List<LiveTvProgram>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Boolean = coroutineScope {
        val e = entry(server, profile)
        e.worker.withLock {
            restore(server, profile, e)
            val channels = channelsPrioritized.distinctBy(::stationKey)
            val dates = daysNewestFirst.distinct().filter { runCatching { isoEpochDay(it) }.isSuccess }
            val tasks = buildList {
                // Get recent results on favourites/visible stations before scanning a large bouquet.
                dates.take(3).forEach { date -> channels.take(8).forEach { add(it to date) } }
                dates.forEach { date -> channels.forEach { add(it to date) } }
            }.filter { (channel, date) -> channel.replayCanCoverDate(date, now()) }
                .distinctBy { (channel, date) -> stationKey(channel) to date }.take(maxDays)
            val known = channels.associateBy(::stationKey)
            val retained = e.state.value.days.filter { day ->
                (dates.isEmpty() || day.date in dates) && known[day.station]?.replayCanCoverDate(day.date, now()) != false
            }
            val indexed = retained.associateBy { it.station to it.date }.toMutableMap()
            var completed = 0
            var successful = true
            var lastSave = TimeSource.Monotonic.markNow()
            var dirty = retained != e.state.value.days
            if (dirty) e.state.value = TvReplaySearchSnapshot(e.state.value.revision + 1, retained)
            var programCount = indexed.values.sumOf { it.programs.size }
            suspend fun persist() {
                if (!dirty) return
                try {
                    storage?.write(server, profile, e.state.value)
                    dirty = false
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* In-memory search survives an unavailable cache. */ }
                finally { lastSave = TimeSource.Monotonic.markNow() }
            }
            try {
                onProgress(0, tasks.size)
                for (batch in tasks.chunked(2)) {
                    ensureActive()
                    val outcomes = batch.map { (channel, date) -> async {
                        val key = stationKey(channel) to date
                        val cached = indexed[key]
                        val stamp = now()
                        val dayEnd = (isoEpochDay(date) + 1) * 86_400
                        val ttl = if (dayEnd < stamp - 86_400 && date != channel.earliestReplayDate(stamp)) 24 * 3600 else 15 * 60
                        if (cached != null && stamp - cached.checkedAt in 0 until ttl.toLong()) true to null else {
                            try {
                                true to TvReplaySearchDay(key.first, date, stamp, load(channel, date)
                                    .filter { it.end <= stamp && it.end > it.start && it.name.isNotBlank() }
                                    .distinctBy { it.start }.map { it.copy(description = it.description.take(300)) })
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { false to null }
                        }
                    } }.awaitAll()
                    if (outcomes.any { !it.first }) successful = false
                    val updates = outcomes.mapNotNull { it.second }
                    if (updates.isNotEmpty()) {
                        updates.forEach {
                            val old = indexed.put(it.station to it.date, it)
                            programCount += it.programs.size - (old?.programs?.size ?: 0)
                        }
                        if (indexed.size > maxDays || programCount > maxPrograms) {
                            val kept = bounded(indexed.values.toList())
                            indexed.clear(); kept.forEach { indexed[it.station to it.date] = it }
                            programCount = kept.sumOf { it.programs.size }
                        }
                        e.state.value = TvReplaySearchSnapshot(e.state.value.revision + 1, indexed.values.toList())
                        dirty = true
                    }
                    completed += batch.size
                    onProgress(completed, tasks.size)
                    if (lastSave.elapsedNow() >= 30.seconds) persist()
                }
            } finally {
                withContext(NonCancellable) { persist() }
            }
            successful
        }
    }

    private fun bounded(days: List<TvReplaySearchDay>): List<TvReplaySearchDay> {
        var remaining = maxPrograms
        return days.sortedWith(compareByDescending<TvReplaySearchDay> { it.date }.thenByDescending { it.checkedAt })
            .take(maxDays).mapNotNull { day ->
                if (day.programs.size > remaining) null else {
                    remaining -= day.programs.size
                    day
                }
            }
    }
    private fun stationKey(channel: LiveTvChannel) = channel.variants.map { it.entry.id }.sorted().joinToString("|")
}
