package com.nuvio.app.features.player

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.lisy.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal data class PlayerTvSources(
    val items: List<PlayerControlSourceItem>,
    val context: String,
    val terminalError: String?,
    val select: (Int) -> Unit,
    val retry: () -> Unit,
    val fail: (String) -> Unit,
    val observe: (String, PlayerPlaybackSnapshot, Boolean) -> Unit,
)

@Composable
internal fun rememberPlayerTvSources(channelId: String, initialUrl: String, initialTried: Set<String>, onOpen: (String) -> Unit): PlayerTvSources? {
    val server by LisyServerRepository.uiState.collectAsStateWithLifecycle()
    val configured = server ?: return null
    return key(configured, channelId) {
        val portal = remember { LisyPortal(configured) }
        val channel by produceState<LiveTvChannel?>(null) {
            try {
                value = LiveTvCache.channels(configured, portal).firstOrNull { it.id == channelId }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { /* A previously opened stream can still play. */ }
        }
        channel?.let { station -> stationSourceControls(configured, station, initialUrl, initialTried, portal, onOpen) }
    }
}

@Composable
internal fun stationSourceControls(
    server: LisyServer, station: LiveTvChannel, initialUrl: String, initialTried: Set<String>, portal: LisyPortal, onOpen: (String) -> Unit,
): PlayerTvSources {
    val revision by TvLiveSources.choices.collectAsStateWithLifecycle()
    val selected = remember(station, revision) { TvLiveSources.selected(server, station) }
    val labels = remember(station) { station.sourceLabels() }
    val ids = remember(station) { listOf(null) + station.variants.map { it.entry.id } }
    val auto = stringResource(Res.string.player_tv_auto_source)
    val autoFailure = stringResource(Res.string.tv_live_all_streams_failed, station.name)
    val manualFailure = stringResource(Res.string.tv_live_selected_stream_failed, station.name)
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    val clock = remember { TimeSource.Monotonic.markNow() }
    var monitor by remember { mutableStateOf(LiveTvPlaybackWatchdog()) }
    var expectedUrl by remember { mutableStateOf(initialUrl) }
    var attempted by remember { mutableStateOf(initialTried) }
    var pending by remember { mutableStateOf(false) }
    var terminalError by remember { mutableStateOf<String?>(null) }

    fun resolve(mode: String?, retryAll: Boolean) {
        if (pending) return
        pending = true
        terminalError = null
        val previous = if (retryAll) emptySet() else attempted
        scope.launch {
            try {
                val stream = TvLiveSources.resolveFrom(portal, station, mode, previous)
                if (stream == null) {
                    terminalError = if (mode == null) autoFailure else manualFailure
                } else {
                    attempted = stream.attemptedIds
                    monitor = LiveTvPlaybackWatchdog()
                    expectedUrl = stream.url
                    open(stream.url)
                }
            } finally {
                pending = false
            }
        }
    }

    fun fail(url: String) {
        if (url != expectedUrl || pending || terminalError != null) return
        val mode = TvLiveSources.selected(server, station)
        if (mode == null) resolve(null, retryAll = false)
        else terminalError = manualFailure
    }

    return PlayerTvSources(
        items = ids.mapIndexed { index, id ->
            PlayerControlSourceItem(index = index, label = labels[id] ?: auto, isCurrent = selected == id, isEnabled = !pending)
        },
        context = if (pending) stringResource(Res.string.player_tv_source_connecting) else station.name,
        terminalError = terminalError,
        select = { index ->
            if (index in ids.indices && !pending) {
                val id = ids[index]
                TvLiveSources.select(server, station, id)
                resolve(id, retryAll = true)
            }
        },
        retry = { resolve(TvLiveSources.selected(server, station), retryAll = true) },
        fail = ::fail,
        observe = { url, snapshot, shouldPlay ->
            if (url == expectedUrl && !pending && terminalError == null && monitor.failed(snapshot, shouldPlay, clock.elapsedNow().inWholeMilliseconds)) fail(url)
        },
    )
}
