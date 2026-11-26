package com.nuvio.app.features.lisy

import androidx.compose.runtime.*
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

internal class TvGuideReplaySearch(
    val server: LisyServer,
    val profile: Int,
    private val portal: LisyPortal = LisyPortal(server),
    val index: TvReplaySearchIndex = TvReplaySearchIndex.shared,
) {
    suspend fun prepare(stations: List<LiveTvChannel>, progress: (Int, Int) -> Unit): Boolean {
        val days = portal.replayDays()
        return index.prepare(server, profile, stations, days, portal::searchableReplayDay, progress)
    }
}

internal data class TvReplayResults(
    val matches: List<Pair<LiveTvChannel, LiveTvProgram>>,
    val busy: Boolean,
    val partial: Boolean,
    val percent: Int,
)

@Composable
internal fun rememberTvReplayResults(
    search: TvGuideReplaySearch?, stations: List<LiveTvChannel>, term: String, prepare: Boolean, now: Long,
): TvReplayResults {
    val active = LocalScreenActive.current
    var busy by remember(search, stations, prepare, active) { mutableStateOf(false) }
    var partial by remember(search, stations, prepare, active) { mutableStateOf(false) }
    var percent by remember(search, stations) { mutableIntStateOf(0) }
    val today = remember(now / 60) { CurrentDateProvider.todayIsoDate() }
    LaunchedEffect(search) {
        if (search != null) withContext(Dispatchers.Default) { search.index.restore(search.server, search.profile) }
    }
    LaunchedEffect(search, stations, active, prepare, today) {
        if (search == null || !active || !prepare || stations.isEmpty()) return@LaunchedEffect
        while (true) {
            busy = true
            partial = false
            try {
                partial = !withContext(Dispatchers.Default) {
                    search.prepare(stations) { done, total ->
                        percent = if (total > 0) done * 100 / total else 100
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                partial = true
            } finally {
                busy = false
            }
            delay(if (partial) 60_000L else 15 * 60_000L)
        }
    }
    var matches by remember(search, stations, term) { mutableStateOf(emptyList<Pair<LiveTvChannel, LiveTvProgram>>()) }
    LaunchedEffect(search, stations, term, active, now / 60) {
        if (search == null || term.isEmpty() || !active) return@LaunchedEffect
        search.index.state(search.server, search.profile).map { it.revision }.conflate().collect {
            matches = withContext(Dispatchers.Default) { search.index.search(search.server, search.profile, stations, term) }
            // While the first import is running, coalesce arriving pages between result updates.
            delay(350)
        }
    }
    return TvReplayResults(matches, busy, partial, percent)
}
