package com.nuvio.app.features.lisy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.ScreenActivityEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LiveTvScreen(
    topPadding: Dp?,
    onPlay: (String, String, String, String?, Set<String>) -> Unit,
    onReplayPlay: (LiveTvChannel, String, String, String) -> Unit,
) {
    val server by LisyServerRepository.uiState.collectAsStateWithLifecycle()
    if (server == null) {
        Box(Modifier.fillMaxSize().padding(top = topPadding ?: 24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.lisy_tv_no_server), color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }
    val configuredServer = checkNotNull(server)
    val portal = remember(configuredServer) { LisyPortal(configuredServer) }
    val guidePortal = remember(configuredServer) { LisyPortal(configuredServer) }
    var guideWindow by remember(guidePortal) { mutableStateOf<LiveTvGuideWindow?>(null) }
    var guideDays by remember(guidePortal) { mutableStateOf(emptyList<String>()) }
    var guideLoading by remember(guidePortal) { mutableStateOf(false) }
    var guideFailed by remember(guidePortal) { mutableStateOf(false) }
    var guideRefresh by remember(guidePortal) { mutableIntStateOf(0) }
    var refreshPending by remember(guidePortal) { mutableStateOf(false) }
    var guide by remember(guidePortal) { mutableStateOf<Map<String, List<LiveTvProgram>>>(emptyMap()) }
    var now by remember { mutableLongStateOf(Clock.System.now().epochSeconds) }
    var channels by remember(portal) { mutableStateOf<List<LiveTvChannel>?>(null) }
    var loadFailed by remember(portal) { mutableStateOf(false) }
    var retry by remember(portal) { mutableIntStateOf(0) }
    var playingId by remember(portal) { mutableStateOf<String?>(null) }
    var failedChannel by remember(portal) { mutableStateOf<LiveTvChannel?>(null) }
    val profile = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId
    val favoriteKey = remember(configuredServer, profile) {
        "tv_favorites_${profile}_${(configuredServer.url.trimEnd('/') + "|" + configuredServer.mac.uppercase()).hashCode()}"
    }
    var favorites by remember(favoriteKey) {
        mutableStateOf(LisyServerStorage.loadTvPreference(favoriteKey)?.let { raw ->
            runCatching { kotlinx.serialization.json.Json.decodeFromString<Set<String>>(raw) }.getOrNull()
        }.orEmpty())
    }
    val favoriteChannels = remember(channels, favorites) {
        if (favorites.isEmpty()) emptySet() else channels.orEmpty()
            .filter { channel -> channel.variants.any { it.entry.id in favorites } }
            .mapTo(mutableSetOf()) { it.id }
    }
    val sourceRevision by TvLiveSources.choices.collectAsStateWithLifecycle()
    val previewPortal = remember(configuredServer) { LisyPortal(configuredServer) }
    val scope = rememberCoroutineScope()
    val screenActive by rememberUpdatedState(LocalScreenActive.current)

    val separationRevision by TvSourceSeparations.changes.collectAsStateWithLifecycle()
    val isolatedSources = remember(configuredServer, separationRevision) { TvSourceSeparations.ids(configuredServer) }
    ScreenActivityEffect(portal, retry, separationRevision) { active ->
        if (!active) return@ScreenActivityEffect
        while (true) {
            loadFailed = false
            try {
                channels = LiveTvCache.channels(configuredServer, portal)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                loadFailed = channels == null
            }
            delay(if (loadFailed) 60_000 else 15 * 60_000)
        }
    }
    ScreenActivityEffect(guidePortal, channels != null, guideRefresh) { active ->
        if (!active || channels == null) return@ScreenActivityEffect
        while (true) {
            try {
                val snapshot = LiveTvCache.guide(configuredServer, guidePortal)
                guide = mergeLiveTvGuide(guide, snapshot, Clock.System.now().epochSeconds, guideWindow?.focus())
                val coveredDays = snapshot.values.flatten().mapNotNull { programme ->
                    com.nuvio.app.core.time.EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(programme.start * 1000)
                }.distinct().sorted()
                if (guideDays.isEmpty()) guideDays = coveredDays
                try {
                    val calendar = guidePortal.guideDays()
                    if (calendar.isNotEmpty()) guideDays = calendar
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (guideDays.isEmpty()) guideDays = coveredDays
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A refresh failure preserves the guide already displayed.
            }
            delay(15 * 60_000)
        }
    }
    ScreenActivityEffect(guidePortal, guideWindow, guideRefresh) { active ->
        val window = guideWindow ?: return@ScreenActivityEffect
        if (!active) return@ScreenActivityEffect
        var force = refreshPending
        refreshPending = false
        while (true) {
            guideLoading = true
            guideFailed = false
            try {
                guideFailed = !LiveTvCache.guideWindow(
                    portal = guidePortal,
                    start = window.start,
                    end = window.end,
                    channels = window.channels,
                    forceRefresh = force,
                ) { channel, programmes ->
                    val stationId = channel.variants.firstOrNull()?.entry?.id
                    if (stationId != null) {
                        val updates = mapOf(stationId to programmes)
                        guide = mergeLiveTvGuide(guide, updates, Clock.System.now().epochSeconds, guideWindow?.focus())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                guideFailed = true
            } finally {
                guideLoading = false
            }
            force = false
            delay(if (guideFailed) 60_000 else 3 * 60_000)
        }
    }
    ScreenActivityEffect { active ->
        if (!active) return@ScreenActivityEffect
        while (true) {
            now = Clock.System.now().epochSeconds
            delay(15_000)
        }
    }
    val replayPortal = remember(configuredServer) { LisyPortal(configuredServer) }
    var archive by remember(configuredServer) { mutableStateOf<LiveTvChannel?>(null) }

    val playLive: (LiveTvChannel) -> Unit = { channel ->
        if (playingId == null) {
            failedChannel = null
            playingId = channel.id
            scope.launch {
                try {
                    val stream = TvLiveSources.resolveFrom(portal, channel, TvLiveSources.selected(configuredServer, channel))
                    if (stream == null) failedChannel = channel
                    else if (screenActive && server == configuredServer) onPlay(channel.id, channel.name, stream.url, channel.logo, stream.attemptedIds)
                } finally {
                    playingId = null
                }
            }
        }
    }

    TvBrowser(
        channels = channels,
        guide = guide,
        guideDays = guideDays,
        guideLoading = guideLoading,
        guideFailed = guideFailed,
        onRefreshGuide = { refreshPending = true; guideRefresh++ },
        onWindowChanged = { start, end, visibleChannels ->
            guideWindow = if (visibleChannels.isEmpty()) null else LiveTvGuideWindow(start, end, visibleChannels.take(24))
        },
        now = now,
        favorites = favoriteChannels,
        topPadding = topPadding,
        loadFailed = loadFailed,
        playbackFailed = failedChannel != null,
        playbackFailure = failedChannel?.let { channel ->
            stringResource(if (TvLiveSources.selected(configuredServer, channel) == null) Res.string.tv_live_all_streams_failed
                else Res.string.tv_live_selected_stream_failed, channel.name)
        },
        retryPlayback = { failedChannel?.let(playLive) },
        playingId = playingId,
        onRetry = { retry++ },
        onFavorite = { channel ->
            val variantIds = channel.variants.map { it.entry.id }.toSet()
            val next = if (channel.id in favoriteChannels) favorites - variantIds else favorites + variantIds
            LisyServerStorage.saveTvPreference(favoriteKey, kotlinx.serialization.json.Json.encodeToString(next))
            favorites = next
        },
        sourceRevision = sourceRevision,
        isolatedSources = isolatedSources,
        onSourceSeparation = { id, separate ->
            if (separate) TvSourceSeparations.separate(configuredServer, id) else TvSourceSeparations.restore(configuredServer, id)
        },
        selectedSource = { TvLiveSources.selected(configuredServer, it) },
        onSelectSource = { channel, id -> TvLiveSources.select(configuredServer, channel, id) },
        previewStream = { channel, id -> TvLiveSources.resolve(previewPortal, channel, id) },
        onArchive = { archive = it },
        onExitArchive = { archive = null },
        archiveContent = archive?.let { channel ->
            @Composable {
                TvArchivePage(channel, replayPortal, onDismiss = { archive = null }) { program, url ->
                    if (screenActive && server == configuredServer) onReplayPlay(channel, program.id, program.title, url)
                }
            }
        },
        lookupReplay = replayPortal::guideReplay,
        onReplay = { channel, program ->
            val url = replayPortal.replayStream(program)
            if (screenActive && server == configuredServer) onReplayPlay(channel, program.id, program.title, url)
        },
        onLive = playLive,
    )
}


private data class LiveTvGuideWindow(
    val start: Long,
    val end: Long,
    val channels: List<LiveTvChannel>,
) {
    fun focus(): LiveTvGuideFocus {
        val startDate = com.nuvio.app.core.time.EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(start * 1000)
        val endDate = com.nuvio.app.core.time.EpisodeReleaseDatePlatform.localIsoDateAtEpochMs((end - 1) * 1000)
        val first = startDate?.let(com.nuvio.app.features.watchprogress.CurrentDateProvider::localStartOfDayEpochMs)?.div(1000) ?: start
        val last = endDate?.let(com.nuvio.app.features.watchprogress.CurrentDateProvider::localStartOfDayEpochMs)?.div(1000)?.plus(86_400) ?: end
        return LiveTvGuideFocus(first, last, channels.mapNotNull { it.variants.firstOrNull()?.entry?.id }.toSet())
    }
}
