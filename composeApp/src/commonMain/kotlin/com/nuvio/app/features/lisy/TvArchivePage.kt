package com.nuvio.app.features.lisy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import com.nuvio.app.core.time.daysUntilEpisodeRelease
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.core.format.formatReleaseDateWithoutYear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TvArchivePage(
    channel: LiveTvChannel,
    portal: LisyPortal,
    onDismiss: () -> Unit,
    onPlay: (LiveTvReplayProgram, String) -> Unit,
) {
    var days by remember(channel) { mutableStateOf<List<String>?>(null) }
    var date by remember(channel) { mutableStateOf<String?>(null) }
    var programs by remember(channel, date) { mutableStateOf<List<LiveTvReplayProgram>?>(null) }
    var loadFailed by remember(channel, date) { mutableStateOf(false) }
    var playFailed by remember(channel, date) { mutableStateOf(false) }
    var playing by remember(channel) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    if (LocalNavigationEventDispatcherOwner.current != null) {
        PlatformBackHandler(enabled = LocalScreenActive.current, onBack = onDismiss)
    }
    LaunchedEffect(channel, date, attempt) {
        loadFailed = false
        try {
            if (days == null) {
                val result = portal.replayDays()
                days = result
                date = result.firstOrNull()
            } else {
                date?.let { programs = portal.replayPrograms(channel, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    TvArchiveContent(channel, days, date, programs, playing, loadFailed, playFailed,
            onDate = { date = it }, onDismiss = onDismiss, onRetry = { attempt++ },
            onProgram = { program ->
                if (playing == null) {
                    playing = program.id
                    playFailed = false
                    scope.launch {
                        try {
                            onPlay(program, portal.replayStream(program))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            playFailed = true
                        } finally {
                            playing = null
                        }
                    }
                }
            })
}

private enum class AirPeriod(val label: org.jetbrains.compose.resources.StringResource) {
    Evening(Res.string.tv_archive_evening), Afternoon(Res.string.tv_archive_afternoon),
    Morning(Res.string.tv_archive_morning), Night(Res.string.tv_archive_night), Unspecified(Res.string.tv_archive_other_time)
}
private fun LiveTvReplayProgram.period(): AirPeriod = when (startLabel.substringBefore(':').toIntOrNull()) {
    in 18..23 -> AirPeriod.Evening
    in 12..17 -> AirPeriod.Afternoon
    in 6..11 -> AirPeriod.Morning
    in 0..5 -> AirPeriod.Night
    else -> AirPeriod.Unspecified
}

@Composable
internal fun TvArchiveContent(
    channel: LiveTvChannel, days: List<String>?, date: String?, programs: List<LiveTvReplayProgram>?,
    playing: String?, loadFailed: Boolean, playFailed: Boolean,
    onDate: (String) -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit, onProgram: (LiveTvReplayProgram) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val available = days.orEmpty()
    val selectedIndex = available.indexOf(date)
    val today = CurrentDateProvider.todayIsoDate()
    val byTime = remember(programs) { programs.orEmpty().sortedByDescending { p ->
        (p.startLabel.substringBefore(':').toIntOrNull() ?: -1) * 60 +
            (p.startLabel.substringAfter(':').take(2).toIntOrNull() ?: 0)
    } }
    val periods = remember(byTime) { byTime.map { it.period() }.distinct() }
    var period by remember(date) { mutableStateOf<AirPeriod?>(null) }
    var showPeriods by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalIconButton(onClick = onDismiss, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.ArrowBack, stringResource(Res.string.tv_archive_back))
            }
            TvStationLogo(channel, Modifier.size(62.dp, 40.dp))
            Text(channel.flaggedName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (periods.size > 1) Box {
                TextButton(onClick = { showPeriods = true }, shape = CircleShape) {
                    Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(period?.label ?: Res.string.tv_archive_all_day))
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = showPeriods, onDismissRequest = { showPeriods = false }, shape = RoundedCornerShape(16.dp)) {
                    (listOf(null) + periods).forEach { option ->
                        DropdownMenuItem(text = { Text(stringResource(option?.label ?: Res.string.tv_archive_all_day)) },
                            trailingIcon = { if (option == period) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) },
                            onClick = { period = option; showPeriods = false })
                    }
                }
            }
        }
        if (available.isNotEmpty() && date != null) {
            val dates = rememberLazyListState()
            LaunchedEffect(selectedIndex) {
                if (selectedIndex >= 0 && dates.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex })
                    dates.scrollToItem(selectedIndex)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyRow(Modifier.weight(1f), state = dates, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(available, key = { it }) { day ->
                        val selected = day == date
                        Surface(onClick = { onDate(day) }, enabled = playing == null, shape = CircleShape,
                            color = if (selected) colors.onBackground else colors.surfaceVariant,
                            contentColor = if (selected) colors.background else colors.onSurfaceVariant) {
                            Text(archiveDate(day, today), Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
                if (available.size > 1) {
                    IconButton(onClick = { onDate(available[selectedIndex + 1]) },
                        enabled = playing == null && selectedIndex in 0 until available.lastIndex) {
                        Icon(Icons.Rounded.ChevronLeft, stringResource(Res.string.tv_archive_previous_day))
                    }
                    IconButton(onClick = { onDate(available[selectedIndex - 1]) }, enabled = playing == null && selectedIndex > 0) {
                        Icon(Icons.Rounded.ChevronRight, stringResource(Res.string.tv_archive_next_day))
                    }
                }
            }
        }
        if (playFailed) Text(stringResource(Res.string.tv_guide_replay_play_error),
            Modifier.padding(vertical = 8.dp), color = colors.error)
        when {
            loadFailed -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.lisy_tv_archive_error), color = colors.onBackground)
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
            }
            days?.isEmpty() == true || programs?.isEmpty() == true ->
                Text(stringResource(Res.string.lisy_tv_no_archive_programmes), Modifier.padding(vertical = 30.dp),
                    color = colors.onSurfaceVariant)
            programs == null -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            else -> key(date, period) {
                val entries = remember(byTime, period) { byTime.filter { period == null || it.period() == period } }
                val grid = rememberLazyGridState()
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(GridCells.Adaptive(218.dp), state = grid, contentPadding = PaddingValues(top = 14.dp, end = 8.dp, bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(entries, key = { it.id }) { program ->
                            ArchiveProgrammeCard(channel, program, playing, onProgram)
                        }
                    }
                    NuvioDesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun ArchiveProgrammeCard(channel: LiveTvChannel, program: LiveTvReplayProgram, playing: String?,
                                 onProgram: (LiveTvReplayProgram) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface)
        .border(1.dp, if (hovered || focused) colors.onSurface else colors.onSurface.copy(alpha = .09f), shape)
        .clickable(interactionSource = interaction, indication = null, enabled = playing == null, role = Role.Button,
            onClickLabel = stringResource(Res.string.tv_archive_watch)) { onProgram(program) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 8f).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center) {
            TvStationLogo(channel, Modifier.fillMaxSize(.54f))
            TvProgramArtwork(program.title, program.category, program.description, program.imageUrl, Modifier.fillMaxSize(), channel)
            Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = CircleShape,
                color = Color.Black.copy(alpha = .72f), contentColor = Color.White) {
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    if (playing == program.id) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            val info = listOfNotNull(listOf(program.startLabel, program.endLabel).filter(String::isNotBlank)
                .joinToString(" – ").takeIf { it.isNotEmpty() }, program.category?.takeIf { it.isNotBlank() })
            if (info.isNotEmpty()) Text(info.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(program.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (program.description.isNotBlank()) Text(program.description, style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun archiveDate(date: String, today: String): String {
    val formatted = formatReleaseDateWithoutYear(date)
    val prefix = when (daysUntilEpisodeRelease(today, date)) {
        0 -> stringResource(Res.string.tv_archive_today)
        -1 -> stringResource(Res.string.tv_archive_yesterday)
        else -> null
    }
    return listOfNotNull(prefix, formatted).joinToString(" · ")
}
