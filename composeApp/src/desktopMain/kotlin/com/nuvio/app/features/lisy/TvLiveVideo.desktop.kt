package com.nuvio.app.features.lisy

import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

private val activeDecoder = Mutex()

@Composable
internal actual fun TvLiveVideo(url: String, modifier: Modifier, onReady: () -> Unit, onEnd: () -> Unit, muted: Boolean) {
    if (DesktopHostOs.current != DesktopHostOs.MACOS) {
        SystemTvLiveVideo(url, modifier, onReady, onEnd, muted)
        return
    }
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val currentMuted by rememberUpdatedState(muted)
    val ready by rememberUpdatedState(onReady)
    val end by rememberUpdatedState(onEnd)
    LaunchedEffect(url) {
        try {
            readLiveFrames(url, muted = { currentMuted }) { image ->
                if (frame == null) ready()
                frame = image
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the artwork and the full player action when the preview is unavailable.
        }
        end()
    }
    frame?.let { Image(it, null, modifier.testTag("tv-live-preview-frame"), contentScale = ContentScale.Fit) }
}

internal suspend fun readLiveFrames(url: String, muted: () -> Boolean, onFrame: (ImageBitmap) -> Unit) = withContext(Dispatchers.IO) {
    activeDecoder.withLock {
        NativePlayerBridge.ensureLoaded()
        val handle = NativePlayerBridge.openLivePreview(url, muted())
        if (handle == 0L) return@withLock
        try {
            val width = 416
            val height = 234
            val pixels = ByteArray(width * height * 4)
            val buffer = ByteBuffer.allocateDirect(pixels.size)
            val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            var lastFrame = TimeSource.Monotonic.markNow()
            while (isActive && lastFrame.elapsedNow() < 12.seconds) {
                when (NativePlayerBridge.readLivePreview(handle, buffer, width, height, muted())) {
                    -1 -> break
                    1 -> {
                        buffer.rewind()
                        buffer.get(pixels)
                        val bitmap = Bitmap().apply { check(installPixels(info, pixels, width * 4)) }
                        onFrame(bitmap.asComposeImageBitmap())
                        lastFrame = TimeSource.Monotonic.markNow()
                    }
                }
                delay(50)
            }
        } finally {
            NativePlayerBridge.closeLivePreview(handle)
        }
    }
}
