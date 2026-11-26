package com.nuvio.app.features.lisy

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.features.home.components.HomePosterPreviewPositionProvider
import com.nuvio.app.isDesktop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TvQualityChoice(channel: LiveTvChannel, selected: String?, onSelect: (String?) -> Unit,
                            isolated: Boolean, onSourceSeparation: ((String, Boolean) -> Unit)?,
                            onMenu: (Boolean) -> Unit = {}) {
    val labels = remember(channel) { channel.sourceLabels() }
    var open by remember { mutableStateOf(false) }
    val auto = stringResource(Res.string.tv_source_auto)
    val quality = stringResource(Res.string.tv_source_quality)
    fun toggle(value: Boolean) { open = value; onMenu(value) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .78f))
            .clickable(role = Role.Button) { toggle(true) }
            .semantics { contentDescription = quality }
            .padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(13.dp), tint = Color.White)
            Spacer(Modifier.width(4.dp))
            Text(labels[selected] ?: auto, style = MaterialTheme.typography.labelMedium, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(15.dp), tint = Color.White)
        }
        DropdownMenu(open, { toggle(false) }, shape = RoundedCornerShape(16.dp)) {
            (listOf(null to auto) + labels.toList()).forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, trailingIcon = {
                    if (id == selected) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                }, onClick = { toggle(false); onSelect(id) })
            }
            if (onSourceSeparation != null && (isolated || selected != null)) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(
                    if (isolated) Res.string.tv_restore_source else Res.string.tv_separate_source
                )) }, onClick = {
                    toggle(false)
                    onSourceSeparation(if (isolated) channel.id else checkNotNull(selected), !isolated)
                })
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvHoverCard(
    channel: LiveTvChannel, programme: LiveTvProgram?, next: LiveTvProgram?, now: Long, favorite: Boolean,
    selected: String?, loading: Boolean, enabled: Boolean, hoverEnabled: Boolean, expanded: Boolean,
    muted: Boolean, onToggleSound: () -> Unit,
    onExpand: () -> Unit, onDismiss: () -> Unit, onPlay: () -> Unit, onFavorite: () -> Unit,
    onSelect: (String?) -> Unit, resolve: (suspend (LiveTvChannel, String?) -> String?)?,
    isolated: Boolean, onSourceSeparation: ((String, Boolean) -> Unit)?,
) {
    val anchor = remember { MutableInteractionSource() }
    val overlay = remember { MutableInteractionSource() }
    val overCard by anchor.collectIsHoveredAsState()
    val overPopup by overlay.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    var anchorMenu by remember { mutableStateOf(false) }
    var dismissedByScroll by remember { mutableStateOf(false) }
    LaunchedEffect(overCard, overPopup, menu, anchorMenu, hoverEnabled, expanded, dismissedByScroll) {
        if (dismissedByScroll && !overCard && !overPopup) dismissedByScroll = false
        if (!hoverEnabled || !isDesktop || anchorMenu || dismissedByScroll) {
            if (expanded) onDismiss()
        } else if (overCard || overPopup || menu) {
            if (!expanded && overCard) { delay(1_000); onExpand() }
        } else if (expanded) {
            delay(180)
            onDismiss()
        }
    }
    Box(Modifier.hoverable(anchor)) {
        TvChannelCard(channel, programme, now, favorite, loading, enabled, selected, onSelect, onFavorite, onPlay,
            onMenu = { anchorMenu = it }, isolated = isolated, onSourceSeparation = onSourceSeparation)
        if (!expanded || !hoverEnabled) return@Box
        val density = LocalDensity.current
        val position = remember(density) { with(density) { HomePosterPreviewPositionProvider(12.dp.roundToPx(), 24.dp.roundToPx()) } }
        Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
            val colors = MaterialTheme.colorScheme
            val shape = RoundedCornerShape(20.dp)
            var entered by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { entered = true }
            val reveal by animateFloatAsState(if (entered) 1f else 0f, tween(180))
            Column(Modifier.width(384.dp).hoverable(overlay)
                .onPointerEvent(PointerEventType.Scroll) { dismissedByScroll = true; onDismiss() }
                .graphicsLayer { alpha = reveal; scaleX = .94f + reveal * .06f; scaleY = scaleX }
                .shadow(18.dp, shape).clip(shape)
                .background(colors.surface).border(1.dp, colors.onSurface.copy(alpha = .2f), shape)) {
                var lightPlate by remember(channel.logo) { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(if (lightPlate) Color(0xffcbced4) else Color(0xff24262b))
                    .clickable(onClickLabel = stringResource(Res.string.lisy_tv_play), onClick = onPlay),
                    contentAlignment = Alignment.Center) {
                    if (channel.logo == null) Text(channel.name, color = Color.White)
                    else NuvioAsyncImage(channel.logo, null, Modifier.fillMaxSize(.50f),
                        onSuccess = { lightPlate = it.painter.needsLightBackground() })
                    programme?.let { TvProgramArtwork(it.name, it.category, it.description, it.imageUrl, Modifier.fillMaxSize(), channel) }
                    val candidates = remember(channel, selected) {
                        selected?.let(::listOf) ?: channel.variants.map { it.entry.id }.distinct()
                    }
                    var index by remember(candidates) { mutableIntStateOf(0) }
                    var stream by remember(channel, candidates, index) { mutableStateOf<String?>(null) }
                    var ready by remember(channel, candidates, index) { mutableStateOf(false) }
                    val failed = index >= candidates.size
                    LaunchedEffect(channel, candidates, index) {
                        val candidate = candidates.getOrNull(index) ?: return@LaunchedEffect
                        stream = try { resolve?.invoke(channel, candidate) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        if (stream == null) index++
                    }
                    stream?.let { url ->
                        val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(180))
                        key(index) {
                            TvLiveVideo(url, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, muted = muted,
                                onReady = { ready = true }, onEnd = { index++ })
                        }
                    }
                    if (failed) {
                        Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = RoundedCornerShape(50),
                            color = Color.Black.copy(alpha = .78f), contentColor = Color.White) {
                            Text(stringResource(Res.string.tv_stream_unavailable), Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (!failed) {
                        IconButton(
                            onClick = onToggleSound,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color.Black.copy(alpha = .74f), contentColor = Color.White,
                            ),
                        ) {
                            Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                stringResource(if (muted) Res.string.tv_preview_unmute else Res.string.tv_preview_mute),
                                Modifier.size(19.dp))
                        }
                    }
                    if (!failed && !ready) CircularProgressIndicator(Modifier.align(Alignment.Center)
                        .padding(12.dp).size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    if (channel.variants.size > 1 || isolated) Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                        TvQualityChoice(channel, selected, onSelect, isolated, onSourceSeparation) { menu = it }
                    }
                }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(channel.flaggedName, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            color = colors.onSurface, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            stringResource(if (favorite) Res.string.lisy_tv_unfavorite else Res.string.lisy_tv_favorite),
                            tint = colors.onSurface) }
                    }
                    programme?.let { Text("${formatLocalTime(it.start * 1000)}  ·  ${it.name}",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 2) }
                    programme?.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(description, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                    }
                    next?.let { Text("${stringResource(Res.string.lisy_tv_next)}  ·  ${formatLocalTime(it.start * 1000)}  ${it.name}",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2) }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onPlay, shape = RoundedCornerShape(50)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.lisy_tv_play))
                    }
                }
            }
        }
    }
}
