package com.nuvio.app.features.lisy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.ScreenActivityEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private const val Uncategorized = "\u0000"

@Composable
internal fun LiveTvScreen(
    topPadding: Dp?,
    onPlay: (String, String, String, String?) -> Unit,
) {
    val server by LisyServerRepository.uiState.collectAsStateWithLifecycle()
    if (server == null) {
        Box(Modifier.fillMaxSize().padding(top = topPadding ?: 24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.lisy_tv_no_server))
        }
        return
    }
    val configuredServer = checkNotNull(server)
    val portal = remember(configuredServer) { LisyPortal(configuredServer) }
    var channels by remember(portal) { mutableStateOf<List<LiveTvChannel>?>(null) }
    var loadFailed by remember(portal) { mutableStateOf(false) }
    var retry by remember(portal) { mutableIntStateOf(0) }
    var playingId by remember(portal) { mutableStateOf<String?>(null) }
    var playbackFailed by remember(portal) { mutableStateOf(false) }
    var favorites by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var country by remember(portal) { mutableStateOf<String?>(null) }
    var category by remember(portal) { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var countriesExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val screenActive by rememberUpdatedState(LocalScreenActive.current)

    ScreenActivityEffect(portal, retry) { active ->
        if (!active || channels != null) return@ScreenActivityEffect
        loadFailed = false
        try {
            channels = portal.liveChannels()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    val all = channels.orEmpty()
    val countries = remember(all) { all.mapNotNull { it.country }.distinct().sorted() }
    val categories = remember(all) { all.flatMap { it.categories }.distinct().sorted() }
    val favoritePrefix = "${configuredServer.hashCode()}:"
    val visible = remember(all, country, category, query, favoritesOnly, favorites, favoritePrefix) {
        all.filter { channel ->
            (!favoritesOnly || favoritePrefix + channel.id in favorites) &&
                (country == null || (channel.country ?: Uncategorized) == country) &&
                (category == null || category in channel.categories ||
                    category == Uncategorized && channel.categories.isEmpty()) &&
                (query.isBlank() || channel.variants.any { it.entry.title.contains(query.trim(), ignoreCase = true) })
        }
    }
    val allCountries = stringResource(Res.string.lisy_tv_all_countries)
    val unknownCountry = stringResource(Res.string.lisy_tv_unknown_country)
    val noCategory = stringResource(Res.string.lisy_tv_no_category)
    val categoryLabels = categories + listOfNotNull(Uncategorized.takeIf { all.any { it.categories.isEmpty() } })

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(top = (topPadding ?: 0.dp) + 18.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            val compact = maxWidth < 650.dp
            if (compact) {
                Text(stringResource(Res.string.lisy_tv_live), style = MaterialTheme.typography.headlineSmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.lisy_tv_live), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        label = { Text(stringResource(Res.string.lisy_tv_search)) })
                }
            }
            if (compact) OutlinedTextField(
                query, { query = it },
                modifier = Modifier.align(Alignment.TopEnd).width(170.dp),
                singleLine = true,
                label = { Text(stringResource(Res.string.lisy_tv_search)) },
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                Box {
                    OutlinedButton(onClick = { countriesExpanded = true }) {
                        Text(when (country) { null -> allCountries; Uncategorized -> unknownCountry; else -> country.orEmpty() })
                        Text(" ▾")
                    }
                    DropdownMenu(expanded = countriesExpanded, onDismissRequest = { countriesExpanded = false }) {
                        (listOf<String?>(null) + countries +
                            listOfNotNull(Uncategorized.takeIf { all.any { it.country == null } })).forEach { value ->
                            DropdownMenuItem(
                                text = { Text(when (value) { null -> allCountries; Uncategorized -> unknownCountry; else -> value }) },
                                onClick = { country = value; countriesExpanded = false },
                            )
                        }
                    }
                }
            }
            item {
                FilterChip(favoritesOnly, { favoritesOnly = true; category = null },
                    label = { Text(stringResource(Res.string.lisy_tv_favorites)) })
            }
            item {
                FilterChip(!favoritesOnly && category == null, { favoritesOnly = false; category = null },
                    label = { Text(stringResource(Res.string.lisy_tv_all_channels)) })
            }
            items(categoryLabels, key = { it }) { label ->
                FilterChip(category == label && !favoritesOnly, { category = label; favoritesOnly = false },
                    label = { Text(if (label == Uncategorized) noCategory else label) })
            }
        }

        if (playbackFailed) {
            Text(stringResource(Res.string.lisy_tv_play_error), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
        when {
            loadFailed -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.lisy_tv_load_error))
                OutlinedButton(onClick = { retry++ }) { Text(stringResource(Res.string.action_retry)) }
            }
            channels == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.lisy_tv_empty))
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(190.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(visible, key = { it.id }) { channel ->
                    val favoriteId = favoritePrefix + channel.id
                    TvChannelCard(
                        channel = channel,
                        favorite = favoriteId in favorites,
                        loading = playingId == channel.id,
                        playbackAvailable = playingId == null,
                        onFavorite = {
                            favorites = ArrayList(if (favoriteId in favorites) favorites - favoriteId else favorites + favoriteId)
                        },
                        onPlay = {
                            playingId = channel.id
                            playbackFailed = false
                            scope.launch {
                                try {
                                    val url = channel.variants.firstNotNullOfOrNull { variant ->
                                        try {
                                            portal.stream(LisyCatalog.Live, variant.entry)
                                        } catch (failure: CancellationException) {
                                            throw failure
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                    if (url == null) playbackFailed = true
                                    else if (screenActive && server == configuredServer) onPlay(channel.id, channel.name, url, channel.logo)
                                } finally {
                                    playingId = null
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvChannelCard(
    channel: LiveTvChannel,
    favorite: Boolean,
    loading: Boolean,
    playbackAvailable: Boolean,
    onFavorite: () -> Unit,
    onPlay: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(8.dp)
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
                .background(Brush.verticalGradient(listOf(surface, MaterialTheme.colorScheme.surface)))
                .clickable(enabled = playbackAvailable, onClickLabel = stringResource(Res.string.lisy_tv_play), onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            var showLogo by remember(channel.logo) { mutableStateOf(false) }
            if (!showLogo) Text(channel.name, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            channel.logo?.let {
                NuvioAsyncImage(it, null, Modifier.fillMaxSize(.58f), onSuccess = { showLogo = true },
                    onError = { showLogo = false })
            }
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFFAD1220),
                contentColor = Color.White,
            ) {
                Text(stringResource(Res.string.lisy_tv_live_badge), Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.PlayArrow, contentDescription = null)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(channel.country, channel.categories.firstOrNull()).distinct()
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(if (favorite) Res.string.lisy_tv_unfavorite else Res.string.lisy_tv_favorite))
            }
        }
    }
}
