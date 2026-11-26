package com.nuvio.app.features.lisy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.core.format.formatReleaseDateWithoutYear
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.flow.distinctUntilChanged
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private const val HalfHour = 1800L
private val GuideAccent = Color(0xFFFF6475)
private val GuidePill = RoundedCornerShape(50)
private fun clock(seconds: Long) = formatLocalTime(seconds * 1000)

@Composable
internal fun TvGuide(
    channels: List<LiveTvChannel>, guide: Map<String, List<LiveTvProgram>>, now: Long,
    pad: Dp, playingId: String?, onLive: (LiveTvChannel) -> Unit,
    lookupReplay: suspend (LiveTvChannel, LiveTvProgram) -> LiveTvReplayProgram?,
    onReplay: suspend (LiveTvChannel, LiveTvReplayProgram) -> Unit,
    onWindowChanged: (Long, Long, List<LiveTvChannel>) -> Unit = { _, _, _ -> },
    guideLoading: Boolean = false,
    guideFailed: Boolean = false,
    onRefreshGuide: () -> Unit = {},
    guideDays: List<String> = emptyList(),
) {
    var userStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var selection by remember(channels) { mutableStateOf<Pair<LiveTvChannel, LiveTvProgram?>?>(null) }
    val start = userStart ?: (now / HalfHour * HalfHour)
    val limits = remember(guide) {
        var earliest = Long.MAX_VALUE
        var latest = Long.MIN_VALUE
        guide.values.forEach { list ->
            list.forEach { p -> earliest = minOf(earliest, p.start); latest = maxOf(latest, p.end) }
        }
        earliest to latest
    }
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val reportWindow by rememberUpdatedState(onWindowChanged)
    val days = remember(guideDays) { guideDays.distinct().sorted() }
    var dateMenu by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = pad)) {
        val stationWidth = if (maxWidth >= 660.dp) 142.dp else 104.dp
        val tickCount = when {
            maxWidth >= 1000.dp -> 4
            maxWidth >= 670.dp -> 3
            maxWidth >= 430.dp -> 2
            else -> 1
        }
        val span = HalfHour * tickCount
        val end = start + span
        val day = EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(start * 1000).orEmpty()
        val dayIndex = days.indexOf(day)
        val dateText = day.takeIf(String::isNotEmpty)?.let(::formatReleaseDateWithoutYear).orEmpty()
        val earliest = days.firstOrNull()?.let(CurrentDateProvider::localStartOfDayEpochMs)?.div(1000) ?: limits.first
        val lastDayStart = days.lastOrNull()?.let(CurrentDateProvider::localStartOfDayEpochMs)?.div(1000)
        val latest = lastDayStart?.plus(86_400) ?: limits.second
        val dateWidth = (maxWidth * .35f).coerceAtMost(240.dp)
        fun selectDay(date: String) {
            val midnight = CurrentDateProvider.localStartOfDayEpochMs(date)?.div(1000) ?: return
            val previousMidnight = CurrentDateProvider.localStartOfDayEpochMs(day)?.div(1000) ?: start
            userStart = midnight + (start - previousMidnight).coerceIn(0, 86_399)
            selection = null
            dateMenu = false
        }
        LaunchedEffect(start, end, channels, listState) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.mapNotNull { item -> channels.getOrNull(item.index) }
                    .ifEmpty { channels.take(12) }
            }.distinctUntilChanged().collect { visible ->
                if (visible.isNotEmpty()) reportWindow(start, end, visible)
            }
        }
        Column {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                IconButton(onClick = { days.getOrNull(dayIndex - 1)?.let(::selectDay) },
                    enabled = dayIndex > 0, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.ChevronLeft, stringResource(Res.string.tv_guide_previous_day), Modifier.size(19.dp))
                }
                Box(Modifier.widthIn(max = dateWidth)) {
                    TextButton(onClick = { dateMenu = true }, enabled = days.isNotEmpty(), shape = GuidePill) {
                        Icon(Icons.Rounded.CalendarToday, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(dateText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (days.isNotEmpty()) Icon(Icons.Rounded.ExpandMore, null, Modifier.size(17.dp))
                    }
                    DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false },
                        modifier = Modifier.heightIn(max = 360.dp), shape = RoundedCornerShape(16.dp)) {
                        days.forEach { date ->
                            DropdownMenuItem(
                                text = { Text(formatReleaseDateWithoutYear(date)) },
                                trailingIcon = { if (date == day) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) },
                                onClick = { selectDay(date) },
                            )
                        }
                    }
                }
                IconButton(onClick = { days.getOrNull(dayIndex + 1)?.let(::selectDay) },
                    enabled = dayIndex >= 0 && dayIndex < days.lastIndex, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.ChevronRight, stringResource(Res.string.tv_guide_next_day), Modifier.size(19.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { userStart = null; selection = null }, shape = GuidePill) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(GuideAccent))
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(Res.string.lisy_tv_now))
                }
                IconButton(onClick = onRefreshGuide, enabled = !guideLoading, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Refresh, stringResource(Res.string.tv_guide_refresh), Modifier.size(19.dp))
                }
                Row(Modifier.clip(GuidePill).background(colors.surfaceVariant)) {
                    IconButton(onClick = { userStart = maxOf(start - span, earliest); selection = null },
                        enabled = earliest < start, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.ChevronLeft, stringResource(Res.string.tv_earlier))
                    }
                    IconButton(onClick = { userStart = end; selection = null }, enabled = end < latest,
                        modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.ChevronRight, stringResource(Res.string.tv_later))
                    }
                }
            }
            if (guideLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                Text(stringResource(Res.string.tv_guide_updating), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            }
            if (guideFailed) {
                Text(stringResource(Res.string.tv_guide_refresh_failed), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            selection?.let { (channel, program) ->
                key(channel.id, program) {
                    Dialog(onDismissRequest = { selection = null },
                        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true,
                            usePlatformDefaultWidth = false)) {
                        GuideProgrammeDetail(channel, program, now, playingId != null, lookupReplay,
                            onLive = { onLive(it); selection = null },
                            onReplay = { station, archive -> onReplay(station, archive); selection = null },
                            onDismiss = { selection = null })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.tv_station_column), Modifier.width(stationWidth),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                repeat(tickCount) { i ->
                    Text(clock(start + HalfHour * i), Modifier.weight(1f).padding(start = 5.dp),
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val trackWidth = (maxWidth - stationWidth).coerceAtLeast(0.dp)
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(channels, key = { it.id }) { channel ->
                        val schedule = remember(channel, guide) { channel.scheduleFrom(guide) }
                        val visible = remember(schedule, start, end) { schedule.filter { it.start < end && it.end > start } }
                        Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.width(stationWidth).fillMaxHeight().padding(end = 6.dp)
                                .clip(RoundedCornerShape(10.dp)).background(colors.surface)
                                .clickable(role = Role.Button) { selection = channel to (schedule.firstOrNull { start in it.start until it.end } ?: schedule.firstOrNull { it.start < end && it.end > start }) }
                                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (stationWidth >= 140.dp && channel.logo != null) TvStationLogo(channel, Modifier.size(32.dp))
                                Text(channel.flaggedName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.width(trackWidth).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(colors.surface)) {
                                repeat(tickCount) { tick ->
                                    Box(Modifier.offset(x = trackWidth * tick / tickCount).width(1.dp).fillMaxHeight()
                                        .background(colors.onSurface.copy(alpha = .08f)))
                                }
                                if (visible.isEmpty()) Text(stringResource(Res.string.lisy_tv_no_programme),
                                    Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                visible.forEach { p ->
                                    val left = trackWidth * ((maxOf(p.start, start) - start).toFloat() / span)
                                    val width = trackWidth * ((minOf(p.end, end) - maxOf(p.start, start)).toFloat() / span)
                                    val active = now in p.start until p.end
                                    val selected = selection?.let { it.first.id == channel.id && it.second == p } == true
                                    val shape = RoundedCornerShape(8.dp)
                                    Column(Modifier.offset(x = left).width(width).fillMaxHeight().padding(end = minOf(3.dp, width / 4))
                                        .clip(shape).background(if (active) Color(0xFF3A242B) else colors.surfaceVariant)
                                        .then(if (selected) Modifier.border(1.dp, colors.onSurface, shape) else Modifier)
                                        .clickable(role = Role.Button, onClickLabel = p.name) { selection = channel to p }
                                        .padding(horizontal = if (width > 60.dp) 9.dp else 3.dp, vertical = 5.dp),
                                        verticalArrangement = Arrangement.Center) {
                                        if (width >= 65.dp) Text(clock(p.start), style = MaterialTheme.typography.labelSmall,
                                            color = if (active) Color(0xFFFFB6BE) else colors.onSurfaceVariant)
                                        Text(p.name, maxLines = if (width < 115.dp) 1 else 2,
                                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
                if (now in start until end) {
                    val offset = stationWidth + trackWidth * ((now - start).toFloat() / span)
                    val lineHeight = minOf(maxHeight, 66.dp * channels.size.coerceAtMost(1000) - 4.dp).coerceAtLeast(0.dp)
                    Box(Modifier.offset(x = offset).width(1.dp).height(lineHeight).background(GuideAccent.copy(alpha = .75f)))
                    Box(Modifier.offset(x = offset - 3.dp).size(7.dp).clip(CircleShape).background(GuideAccent))
                }
                NuvioDesktopVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}
