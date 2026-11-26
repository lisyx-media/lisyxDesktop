package com.nuvio.app.features.lisy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal val LiveTvChannel.flaggedName: String
    get() = liveTvCountryFlag(country)?.let { "$it  $name" } ?: name

@Composable
internal fun TvReplayChannels(
    channels: List<LiveTvChannel>, favorites: Set<String>, pad: Dp,
    onArchive: (LiveTvChannel) -> Unit, onFavorite: (LiveTvChannel) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Adaptive(285.dp), state = state,
            contentPadding = PaddingValues(start = pad, end = pad, bottom = 24.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(channels, key = { it.id }) { channel ->
                val action = stringResource(Res.string.lisy_tv_view_replay)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)
                    .clickable(role = Role.Button, onClickLabel = action) { onArchive(channel) }
                    .semantics { contentDescription = "${channel.name}, $action" }
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    TvStationLogo(channel, Modifier.size(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(channel.flaggedName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val info = listOfNotNull(channel.country, channel.categories.firstOrNull()).joinToString(" · ")
                        if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val marked = channel.id in favorites
                    IconToggleButton(checked = marked, onCheckedChange = { onFavorite(channel) }, modifier = Modifier.size(32.dp)) {
                        Icon(if (marked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            stringResource(if (marked) Res.string.lisy_tv_unfavorite else Res.string.lisy_tv_favorite), Modifier.size(17.dp))
                    }
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
        NuvioDesktopVerticalScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
internal fun TvStationLogo(channel: LiveTvChannel, modifier: Modifier) {
    var loaded by remember(channel.logo) { mutableStateOf(false) }
    var light by remember(channel.logo) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(11.dp))
        .background(if (light) Color(0xFFDADDE3) else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        if (!loaded) Icon(Icons.Rounded.LiveTv, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        channel.logo?.let {
            NuvioAsyncImage(it, null, Modifier.fillMaxSize(.80f),
                onSuccess = { state -> loaded = true; light = state.painter.needsLightBackground() },
                onError = { loaded = false; light = false })
        }
    }
}
