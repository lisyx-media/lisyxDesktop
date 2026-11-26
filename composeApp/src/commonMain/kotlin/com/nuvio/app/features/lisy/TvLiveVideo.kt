package com.nuvio.app.features.lisy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.components.HeroTrailerPlayerSurface

@Composable
internal expect fun TvLiveVideo(url: String, modifier: Modifier, onReady: () -> Unit, onEnd: () -> Unit, muted: Boolean = false)

@Composable
internal fun SystemTvLiveVideo(url: String, modifier: Modifier, onReady: () -> Unit, onEnd: () -> Unit, muted: Boolean) {
    HeroTrailerPlayerSurface(url, null, playWhenReady = true, muted = muted, modifier = modifier,
        onReady = onReady, onEnded = onEnd, onError = onEnd)
}
