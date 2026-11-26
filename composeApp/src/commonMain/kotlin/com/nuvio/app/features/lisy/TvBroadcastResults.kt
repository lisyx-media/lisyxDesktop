package com.nuvio.app.features.lisy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.core.format.formatReleaseDateWithoutYear
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform

internal fun tvSearchTerm(text: String) = text.lowercase().foldLisyAccents().filter(Char::isLetterOrDigit)

internal data class TvSearchResults(
    val stations: List<LiveTvChannel>,
    val programmes: List<Pair<LiveTvChannel, LiveTvProgram>>,
)

internal fun findTvBroadcasts(
    stations: List<LiveTvChannel>, guide: Map<String, List<LiveTvProgram>>, term: String, now: Long, replayOnly: Boolean,
): TvSearchResults {
    val matchingStations = mutableListOf<LiveTvChannel>()
    val broadcasts = mutableListOf<Pair<LiveTvChannel, LiveTvProgram>>()
    for (channel in stations) {
        val hits = channel.scheduleFrom(guide).filter {
            (!replayOnly || it.end <= now) && tvSearchTerm(it.name).contains(term)
        }
        if (hits.isNotEmpty() || tvSearchTerm(channel.name).contains(term) ||
            channel.variants.any { tvSearchTerm(it.entry.title).contains(term) }) {
            matchingStations += channel
            hits.forEach { broadcasts += channel to it }
        }
    }
    val chronological = compareBy<Pair<LiveTvChannel, LiveTvProgram>> {
        when { now in it.second.start until it.second.end -> 0; it.second.start > now -> 1; else -> 2 }
    }.thenBy { kotlin.math.abs(it.second.start - now) }
    return TvSearchResults(matchingStations, broadcasts.sortedWith(chronological))
}

@Composable
internal fun TvBroadcastResults(
    matches: List<Pair<LiveTvChannel, LiveTvProgram>>, now: Long, playingId: String?, pad: Dp,
    lookupReplay: suspend (LiveTvChannel, LiveTvProgram) -> LiveTvReplayProgram?,
    onLive: (LiveTvChannel) -> Unit, onReplay: suspend (LiveTvChannel, LiveTvReplayProgram) -> Unit,
) {
    var selection by remember { mutableStateOf<Pair<LiveTvChannel, LiveTvProgram>?>(null) }
    val colors = MaterialTheme.colorScheme
    LazyRow(contentPadding = PaddingValues(start = pad, end = pad, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(matches, key = { (station, p) -> "${station.id}/${p.start}" }) { (station, p) ->
            Column(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant)
                .clickable(role = Role.Button) { selection = station to p }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val date = EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(p.start * 1000)?.let(::formatReleaseDateWithoutYear)
                Text(listOfNotNull(station.flaggedName, date, formatLocalTime(p.start * 1000)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    selection?.let { (station, p) ->
        Dialog(onDismissRequest = { selection = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            GuideProgrammeDetail(station, p, now, playingId != null, lookupReplay,
                onLive = { onLive(it); selection = null },
                onReplay = { channel, item -> onReplay(channel, item); selection = null },
                onDismiss = { selection = null })
        }
    }
}
