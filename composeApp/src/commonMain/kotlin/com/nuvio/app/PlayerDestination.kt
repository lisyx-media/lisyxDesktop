package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.lisy.LisyServerRepository
import com.nuvio.app.features.lisy.requiresParentalPin
import com.nuvio.app.features.lisy.RestrictedTvType
import com.nuvio.app.features.lisy.TvParentalControls
import com.nuvio.app.features.profiles.ProfileRepository
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.player.ExternalPlayerIntentResult
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute

private enum class TvPlaybackAccess { Loading, Public, Private, Unavailable }

@Composable
internal fun PlayerDestination(
    route: PlayerRoute,
    navController: NuvioNavigator,
    externalPlayerId: String?,
    externalPlayerNotConfiguredText: String,
    externalPlayerFailedText: String,
    onExternalPlayerLaunch: (PlayerLaunch) -> Unit,
    launchExternalPlayer: (ExternalPlayerIntentResult.Success) -> Boolean,
    openExternalStreamUrl: (String) -> Boolean,
    onSystemBackHandlerChanged: (PlayerRoute, (() -> Unit)?) -> Unit,
    onOpenPartyMedia: (PartyMedia) -> Unit,
) {
    val popBack = rememberGuardedPopBackStack(
        navController = navController,
        route = route,
        beforePop = ResumePromptRepository::markPlayerExitedNormally,
    )
    val launch = remember(route.launchId) { PlayerLaunchStore.get(route.launchId) }
    if (launch == null) {
        LaunchedEffect(route.launchId) {
            popBack()
        }
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    var restricted = launch.parentMetaType == RestrictedTvType
    if (launch.parentMetaId.startsWith("lisy-tv:") || restricted) {
        val server by LisyServerRepository.uiState.collectAsStateWithLifecycle()
        val launchServer = remember(route.launchId) { server }
        val profiles by ProfileRepository.state.collectAsStateWithLifecycle()
        val profile = profiles.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
        val parental by TvParentalControls.state.collectAsStateWithLifecycle()
        val access = key(route.launchId, server, profile) {
            produceState<TvPlaybackAccess>(TvPlaybackAccess.Loading) {
                val channel = com.nuvio.app.features.lisy.LiveTvCache.channelForPlayback(server, launch.parentMetaId)
                value = when {
                    channel == null -> TvPlaybackAccess.Unavailable
                    channel.requiresParentalPin || launch.parentMetaType == RestrictedTvType -> TvPlaybackAccess.Private
                    else -> TvPlaybackAccess.Public
                }
            }.value
        }
        restricted = restricted || access == TvPlaybackAccess.Private
        val denied = profile != launch.profileId || launchServer != server || access == TvPlaybackAccess.Unavailable ||
            (restricted && (!parental.unlocked || !TvParentalControls.isUnlocked(profile, server)))
        if (denied || access == TvPlaybackAccess.Loading) {
            if (denied) LaunchedEffect(route.launchId) { popBack() }
            Box(Modifier.fillMaxSize())
            return
        }
    }
    val currentFullscreen = com.nuvio.app.core.ui.isFullscreenActionActive()
    val initialFullscreen = remember { currentFullscreen }
    val onBackBase = rememberGuardedPlayerPopBackStack(
        navController = navController,
        route = route,
        beforePop = ResumePromptRepository::markPlayerExitedNormally,
    )
    val onBack = remember(onBackBase, initialFullscreen, currentFullscreen) {
        { releaseBeforeBack: com.nuvio.app.features.player.PlayerReleaseBeforeBack ->
            if (currentFullscreen != initialFullscreen) {
                com.nuvio.app.core.ui.toggleFullscreenAction()
            }
            onBackBase(releaseBeforeBack)
        }
    }
    val registerSystemBack = remember(route, onSystemBackHandlerChanged) {
        { handler: (() -> Unit)? -> onSystemBackHandlerChanged(route, handler) }
    }
    LaunchedEffect(launch.videoId) {
        if (!restricted) launch.videoId?.let { ResumePromptRepository.markPlayerEntered(it) }
    }
    PlayerScreen(
        profileId = launch.profileId,
        title = launch.title,
        sourceUrl = launch.sourceUrl,
        sourceAudioUrl = launch.sourceAudioUrl,
        sourceHeaders = launch.sourceHeaders,
        sourceResponseHeaders = launch.sourceResponseHeaders,
        externalSubtitles = launch.externalSubtitles,
        streamType = launch.streamType,
        logo = launch.logo,
        poster = launch.poster,
        background = launch.background,
        seasonNumber = launch.seasonNumber,
        episodeNumber = launch.episodeNumber,
        episodeTitle = launch.episodeTitle,
        episodeThumbnail = launch.episodeThumbnail,
        streamTitle = launch.streamTitle,
        streamSubtitle = launch.streamSubtitle,
        initialBingeGroup = launch.bingeGroup,
        pauseDescription = launch.pauseDescription,
        providerName = launch.providerName,
        providerAddonId = launch.providerAddonId,
        contentType = launch.contentType,
        videoId = launch.videoId,
        parentMetaId = launch.parentMetaId,
        parentMetaType = if (restricted) RestrictedTvType else launch.parentMetaType,
        torrentInfoHash = launch.torrentInfoHash,
        torrentFileIdx = launch.torrentFileIdx,
        torrentFilename = launch.torrentFilename,
        torrentTrackers = launch.torrentTrackers,
        initialPositionMs = launch.initialPositionMs,
        initialProgressFraction = launch.initialProgressFraction,
        contentLanguage = launch.contentLanguage,
        subtitleExtras = launch.subtitleExtras,
        launchId = route.launchId,
        sourceFilename = launch.sourceFilename,
        stream = launch.stream,
        onBack = onBack,
        onSystemBackHandlerChanged = registerSystemBack,
        onOpenInExternalPlayer = if (externalPlayerSupported && !restricted) { { request ->
            val playerLaunch = PlayerLaunch(
                profileId = launch.profileId,
                title = launch.title,
                sourceUrl = request.sourceUrl,
                sourceHeaders = request.sourceHeaders,
                logo = launch.logo,
                poster = launch.poster,
                background = launch.background,
                seasonNumber = launch.seasonNumber,
                episodeNumber = launch.episodeNumber,
                episodeTitle = launch.episodeTitle,
                episodeThumbnail = launch.episodeThumbnail,
                streamTitle = request.streamTitle ?: launch.streamTitle,
                streamSubtitle = launch.streamSubtitle,
                bingeGroup = launch.bingeGroup,
                pauseDescription = launch.pauseDescription,
                providerName = launch.providerName,
                providerAddonId = launch.providerAddonId,
                contentType = launch.contentType,
                videoId = launch.videoId,
                parentMetaId = launch.parentMetaId,
                parentMetaType = launch.parentMetaType,
                initialPositionMs = request.resumePositionMs,
                subtitleExtras = launch.subtitleExtras,
            )
            onExternalPlayerLaunch(playerLaunch)
            val intentResult = ExternalPlayerPlatform.buildIntent(
                request = request,
                playerId = externalPlayerId,
            )
            when (intentResult) {
                is ExternalPlayerIntentResult.Success -> {
                    val launched = launchExternalPlayer(intentResult)
                    if (!launched) {
                        NuvioToastController.show(externalPlayerFailedText)
                    } else if (externalPlayerId == "infuse") {
                        popBack()
                    }
                }
                ExternalPlayerIntentResult.NotConfigured -> {
                    NuvioToastController.show(externalPlayerNotConfiguredText)
                }
                ExternalPlayerIntentResult.Failed -> {
                    NuvioToastController.show(externalPlayerFailedText)
                }
            }
        } } else null,
        onOpenExternalUrl = { url ->
            !restricted && openExternalStreamUrl(url)
        },
        onOpenPartyMedia = { if (!restricted) onOpenPartyMedia(it) },
        modifier = Modifier.fillMaxSize(),
    )
}
