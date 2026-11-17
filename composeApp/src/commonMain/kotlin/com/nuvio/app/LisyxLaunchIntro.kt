package com.nuvio.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.lisyx_skip_intro
import org.jetbrains.compose.resources.stringResource
import kotlin.math.pow
import kotlin.math.sin

/** One startup ident per desktop window. The application loads behind it. */
@Composable
internal fun LisyxLaunchIntro() {
    var finished by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        progress.animateTo(1f, tween(4400, easing = LinearEasing))
        finished = true
    }
    if (finished) return
    val fade = phase(progress.value, .94f, 1f)
    Box(Modifier.fillMaxSize().clipToBounds().graphicsLayer { alpha = 1f - fade }
        .background(Color.Black)
        .focusRequester(focus).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyUp && it.key in listOf(Key.Escape, Key.Enter, Key.Spacebar)) finished = true
            true
        }.focusable()
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Lisyx" }) {
            drawLisyxIdent(progress.value)
        }
        Text(stringResource(Res.string.lisyx_skip_intro), color = Color(0xFF999999), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp).clickable { finished = true }.padding(12.dp))
    }
}

private fun phase(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)
private fun smooth(p: Float) = p * p * (3f - 2f * p)

/** The filaments originate inside the L; the camera travels into its upright. */
internal fun DrawScope.drawLisyxIdent(p: Float) {
    val stemReveal = smooth(phase(p, .03f, .22f))
    val footReveal = smooth(phase(p, .16f, .34f))
    val dissolve = smooth(phase(p, .44f, .66f))
    val travel = phase(p, .48f, .86f)
    val zoom = 1f + 55f * travel.pow(3.2f)
    val opacity = 1f - smooth(phase(p, .87f, .97f))
    val unit = size.minDimension * .235f / 116f
    val red = Color(0xFFE50914)
    val letter = Path().apply {
        moveTo(5f, 0f); lineTo(37f, 0f); quadraticTo(42f, 0f, 42f, 5f)
        lineTo(42f, 81f); lineTo(94f, 81f); quadraticTo(102f, 81f, 98f, 88f)
        lineTo(81f, 113f); quadraticTo(80f, 116f, 77f, 116f)
        lineTo(5f, 116f); quadraticTo(0f, 116f, 0f, 111f)
        lineTo(0f, 5f); quadraticTo(0f, 0f, 5f, 0f); close()
    }
    val palette = listOf(Color(0xFFFF410C), Color(0xFFFF9600), Color(0xFFFAE6C6), Color(0xFFE50914),
        Color(0xFFC40054), Color(0xFF7630FF), Color(0xFF1754DF), Color(0xFF41BBFF))
    withTransform({
        translate(center.x, center.y)
        scale(unit * zoom, unit * zoom, Offset.Zero)
        translate(-50f + 29f * smooth(travel), -58f)
    }) {
        // Unfold the vertical and horizontal strokes independently.
        clipPath(letter) {
            clipRect(right = 42f, bottom = 116f * stemReveal) {
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF9E0007), red, Color(0xFFFF2730)), 0f, 42f),
                    size = Size(100f, 116f), alpha = opacity * (1f - dissolve * .8f))
            }
            clipRect(left = 42f, top = 81f, right = 42f + 58f * footReveal) {
                drawRect(Brush.linearGradient(listOf(Color(0xFFFF3038), red, Color(0xFF8C0007)), Offset(42f, 81f), Offset(100f, 116f)),
                    size = Size(100f, 116f), alpha = opacity * (1f - dissolve))
            }
            // Fine red fibers remain attached to the letter as the camera approaches.
            repeat(180) { i ->
                val seed = ((i * 73 + 19) % 181) / 181f
                val x = i / 179f * 100f
                val reveal = if (x < 42f) stemReveal else footReveal
                drawRect(Brush.verticalGradient(listOf(red.copy(alpha = .2f), Color(0xFFFF5C54), red), 0f, 116f),
                    Offset(x, 0f), Size(.07f + seed * .22f, 116f * reveal), alpha = (.2f + seed * .5f) * opacity)
            }
        }
        if (dissolve > 0f) {
            repeat(240) { i ->
                val seed = ((i * 97 + 31) % 241) / 241f
                val x = 2f + i / 239f * 38f
                val width = .025f + seed.pow(3) * .19f
                val colored = lerp(red, palette[(i / 9 + i % 3) % palette.size], smooth(phase(p, .56f, .72f)))
                val extinguish = 1f - smooth(phase(p, .81f + seed * .10f, .91f + seed * .07f))
                val alpha = dissolve * (.4f + seed * .6f) * extinguish
                val top = -180f * dissolve + seed * 18f
                val height = 116f + 360f * dissolve
                val beam = Brush.verticalGradient(listOf(Color.Transparent, colored, colored, Color.Transparent), top, top + height)
                drawRect(beam, Offset(x - width * 2, top), Size(width * 5, height), alpha = alpha * .10f)
                drawRect(beam, Offset(x, top), Size(width, height), alpha = alpha)
                if (i % 11 == 0) drawRect(Color.White.copy(alpha = alpha * .28f), Offset(x, 0f), Size(width * .25f, 116f))
            }
        }
    }
}
