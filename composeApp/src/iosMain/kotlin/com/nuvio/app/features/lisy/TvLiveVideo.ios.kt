package com.nuvio.app.features.lisy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun TvLiveVideo(url: String, modifier: Modifier, onReady: () -> Unit, onEnd: () -> Unit, muted: Boolean) =
    SystemTvLiveVideo(url, modifier, onReady, onEnd, muted)
