package com.nuvio.app.features.lisy

import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.core.format.formatReleaseDateWithoutYear
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class Airing { Past, Live, Future }
private sealed interface ProgrammeReplay {
    data object Checking : ProgrammeReplay
    data object Missing : ProgrammeReplay
    data object Error : ProgrammeReplay
    data class Ready(val program: LiveTvReplayProgram) : ProgrammeReplay
}

@Composable
internal fun GuideProgrammeDetail(
    channel: LiveTvChannel, program: LiveTvProgram?, now: Long, launching: Boolean,
    lookupReplay: suspend (LiveTvChannel, LiveTvProgram) -> LiveTvReplayProgram?,
    onLive: (LiveTvChannel) -> Unit, onReplay: suspend (LiveTvChannel, LiveTvReplayProgram) -> Unit, onDismiss: () -> Unit,
) {
    val airing = when {
        program == null || now in program.start until program.end -> Airing.Live
        now < program.start -> Airing.Future
        else -> Airing.Past
    }
    val eligible = airing == Airing.Past && channel.variants.any { it.replayAvailable }
    val lookup by rememberUpdatedState(lookupReplay)
    var attempt by remember(channel, program) { mutableIntStateOf(0) }
    var replay by remember(channel, program, eligible, attempt) {
        mutableStateOf<ProgrammeReplay>(if (eligible) ProgrammeReplay.Checking else ProgrammeReplay.Missing)
    }
    LaunchedEffect(channel, program, eligible, attempt) {
        if (eligible && program != null) {
            replay = try {
                lookup(channel, program)?.let(ProgrammeReplay::Ready) ?: ProgrammeReplay.Missing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProgrammeReplay.Error
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    var starting by remember(channel, program) { mutableStateOf(false) }
    var playbackFailed by remember(channel, program) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BoxWithConstraints(Modifier.padding(12.dp).widthIn(max = 660.dp).fillMaxWidth().heightIn(max = 540.dp)
        .clip(shape).background(colors.surface)
        .border(1.dp, colors.onSurface.copy(alpha = .12f), shape)
        .onPreviewKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                onDismiss()
                true
            } else false
        }.focusRequester(focus).focusable()) {
        val showImage = maxWidth >= 480.dp
        val inset = if (showImage) 24.dp else 16.dp
        val shade = Color(0xFFEF4158)
        Column {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(colors.surfaceVariant, colors.surface)))
                    .padding(start = inset, end = inset, top = 24.dp, bottom = 19.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showImage) {
                        Box(Modifier.width(152.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp))
                            .background(colors.background), contentAlignment = Alignment.Center) {
                            TvStationLogo(channel, Modifier.fillMaxWidth(.77f).fillMaxHeight(.72f))
                            if (program != null) {
                                TvProgramArtwork(program.name, program.category, program.description, program.imageUrl, Modifier.fillMaxSize(), channel)
                                if (airing == Airing.Live && program.end > program.start) {
                                    val progress = ((now - program.start).toFloat() / (program.end - program.start)).coerceIn(0f, 1f)
                                    Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter).background(Color.White.copy(alpha = .25f))) {
                                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(shade))
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth().padding(end = 34.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val status = when (airing) {
                                Airing.Past -> Res.string.tv_guide_aired
                                Airing.Live -> Res.string.tv_guide_live
                                Airing.Future -> Res.string.upcoming_section_title
                            }
                            val badgeColor = if (airing == Airing.Live) Color(0xFFFFACB6) else colors.onSurfaceVariant
                            Row(Modifier.clip(CircleShape).background(
                                if (airing == Airing.Live) Color(0xFF4B212C) else colors.onSurface.copy(alpha = .08f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (airing == Airing.Live) Box(Modifier.size(6.dp).clip(CircleShape).background(shade))
                                Text(stringResource(status), style = MaterialTheme.typography.labelSmall, color = badgeColor)
                            }
                            Text(channel.flaggedName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(program?.name ?: stringResource(Res.string.lisy_tv_no_programme),
                            modifier = Modifier.padding(end = 25.dp), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (program != null) {
                            val date = EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(program.start * 1000)?.let(::formatReleaseDateWithoutYear)
                            val range = "${formatLocalTime(program.start * 1000)} – ${formatLocalTime(program.end * 1000)}"
                            val minutes = ((program.end - program.start + 59) / 60).takeIf { it > 0 }?.let { "$it min" }
                            val info = listOfNotNull(date, range, minutes, program.category?.takeIf(String::isNotBlank))
                            Text(info.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                program?.description?.trim()?.takeIf { it.isNotEmpty() }?.let { description ->
                    Text(description, Modifier.fillMaxWidth().padding(start = inset, end = inset, top = 13.dp, bottom = 17.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
            }
            HorizontalDivider(color = colors.onSurface.copy(alpha = .08f))
            if (playbackFailed) Text(stringResource(Res.string.tv_guide_replay_play_error),
                Modifier.padding(start = inset, end = inset, top = 10.dp), style = MaterialTheme.typography.bodySmall, color = colors.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 12.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                val archive = replay as? ProgrammeReplay.Ready
                val enabled = !launching && !starting && (airing != Airing.Past || archive != null)
                val action = if (starting) stringResource(Res.string.tv_guide_starting_replay) else when (airing) {
                    Airing.Live -> stringResource(Res.string.lisy_tv_play)
                    Airing.Future -> stringResource(Res.string.tv_guide_watch_channel, channel.name)
                    Airing.Past -> stringResource(when (replay) {
                        is ProgrammeReplay.Ready -> Res.string.tv_guide_play_replay
                        ProgrammeReplay.Checking -> Res.string.tv_guide_checking_replay
                        ProgrammeReplay.Error -> Res.string.tv_guide_unverified_replay
                        ProgrammeReplay.Missing -> Res.string.tv_guide_unavailable_replay
                    })
                }
                Button(onClick = {
                    if (airing != Airing.Past) onLive(channel)
                    else if (archive != null && !starting) {
                        starting = true
                        playbackFailed = false
                        scope.launch {
                            try {
                                onReplay(channel, archive.program)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                playbackFailed = true
                            } finally {
                                starting = false
                            }
                        }
                    }
                }, enabled = enabled, shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface,
                        disabledContainerColor = colors.onSurface.copy(alpha = .09f), disabledContentColor = colors.onSurfaceVariant)) {
                    if (starting || airing == Airing.Past && replay == ProgrammeReplay.Checking) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
                    } else {
                        Icon(if (airing == Airing.Past) Icons.Rounded.History else Icons.Rounded.PlayArrow, null, Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(action, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
                if (airing == Airing.Past && replay == ProgrammeReplay.Error) {
                    IconButton(onClick = { attempt++ }) {
                        Icon(Icons.Rounded.Refresh, stringResource(Res.string.action_retry), Modifier.size(19.dp))
                    }
                }
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(9.dp).size(30.dp).clip(CircleShape)
            .background(colors.onSurface.copy(alpha = .06f))) {
            Icon(Icons.Rounded.Close, stringResource(Res.string.lisy_tv_close), Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        }
    }
}
