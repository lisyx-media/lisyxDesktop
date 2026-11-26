package com.nuvio.app.features.trailer

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntOffset
import com.nuvio.app.core.ui.isFullscreenActionActive
import com.nuvio.app.core.ui.toggleFullscreenAction
import kotlin.math.roundToInt

internal data class DesktopTrailerRequest(
    val title: String, val youtubeUrl: String, val source: TrailerPlaybackSource,
    val positionMs: Long, val muted: Boolean, val bounds: Rect,
)

internal data class PosterOverlay(
    val anchor: Rect,
    val position: PopupPositionProvider,
    val content: @Composable () -> Unit,
)

internal class DesktopPreviewSession {
    var overlay by mutableStateOf<PosterOverlay?>(null)
    var request by mutableStateOf<DesktopTrailerRequest?>(null)
    var expanded by mutableStateOf(false)
    var pointerInside by mutableStateOf(false)
}

internal val LocalDesktopPreview = staticCompositionLocalOf<DesktopPreviewSession?> { null }

@Composable
internal fun DesktopTrailerHost(content: @Composable () -> Unit) {
    val session = remember { DesktopPreviewSession() }
    val fullscreen by rememberUpdatedState(isFullscreenActionActive())
    var restoreWindow by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    fun close() {
        session.request = null
        session.expanded = false
        session.pointerInside = false
        if (restoreWindow && fullscreen) toggleFullscreenAction()
        restoreWindow = false
    }
    DisposableEffect(Unit) { onDispose { if (restoreWindow && fullscreen) toggleFullscreenAction() } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        CompositionLocalProvider(LocalDesktopPreview provides session) {
            content()
            session.overlay?.takeUnless { session.expanded }?.let { overlay ->
                Layout(content = overlay.content, modifier = Modifier.fillMaxSize()) { children, constraints ->
                    val size = IntSize(constraints.maxWidth, constraints.maxHeight)
                    val anchor = overlay.anchor.translate(-origin)
                    val bounds = IntRect(anchor.left.roundToInt(), anchor.top.roundToInt(), anchor.right.roundToInt(), anchor.bottom.roundToInt())
                    val panels = children.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                    layout(size.width, size.height) {
                        panels.forEach { panel ->
                            val point = overlay.position.calculatePosition(bounds, size, layoutDirection, IntSize(panel.width, panel.height))
                            panel.place(point.x, point.y)
                        }
                    }
                }
            }
        }
        session.request?.let { request ->
            key(request.source) {
                val state = remember { TrailerPlaybackState(request.positionMs, request.muted) }
                val rect = request.bounds
                val frame = if (session.expanded) Modifier.fillMaxSize() else with(LocalDensity.current) {
                    Modifier.offset { IntOffset((rect.left-origin.x).roundToInt(), (rect.top-origin.y).roundToInt()) }
                        .size(rect.width.toDp(), rect.height.toDp())
                }
                TrailerPlayer(
                    source = request.source, state = state, title = request.title,
                    isLoading = false, errorMessage = null, onRetry = null, modifier = frame,
                    compact = !session.expanded,
                    onPreviewHover = { session.pointerInside = it },
                    onToggleFullscreen = {
                        if (session.expanded) close() else {
                            session.expanded = true
                            restoreWindow = !fullscreen
                            if (restoreWindow) toggleFullscreenAction()
                        }
                    },
                    onBack = ::close, onExitFullscreen = ::close, onEscapeFullscreen = ::close,
                    onOpenInBrowser = { uri.openUri(request.youtubeUrl) },
                )
            }
        }
    }
}
