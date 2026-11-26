package com.nuvio.app.features.lisy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class TvPage { Live, Guide, Replay }

private const val Missing = "\u0000"
private val pill = RoundedCornerShape(50)
private val tvRed = Color(0xFFDF263B)
private val categoryOrder = listOf(
    "Généralistes", "Cinéma", "Séries", "Sport", "Actualités", "Jeunesse", "Découverte",
    "Divertissement", "Musique", "Événements", "Régionales", "Adulte", Missing,
).withIndex().associate { it.value to it.index }

@Composable
internal fun TvBrowser(
    channels: List<LiveTvChannel>?,
    guide: Map<String, List<LiveTvProgram>>,
    now: Long,
    favorites: Set<String>,
    playingId: String?,
    topPadding: Dp?,
    loadFailed: Boolean,
    playbackFailed: Boolean,
    onRetry: () -> Unit,
    onFavorite: (LiveTvChannel) -> Unit,
    onLive: (LiveTvChannel) -> Unit,
    onArchive: (LiveTvChannel) -> Unit,
    sourceRevision: Int = 0,
    selectedSource: (LiveTvChannel) -> String? = { null },
    onSelectSource: (LiveTvChannel, String?) -> Unit = { _, _ -> },
    previewStream: (suspend (LiveTvChannel, String?) -> String?)? = null,
    lookupReplay: suspend (LiveTvChannel, LiveTvProgram) -> LiveTvReplayProgram? = { _, _ -> null },
    onReplay: suspend (LiveTvChannel, LiveTvReplayProgram) -> Unit = { _, _ -> },
    isolatedSources: Set<String> = emptySet(),
    onSourceSeparation: ((String, Boolean) -> Unit)? = null,
    archiveContent: (@Composable () -> Unit)? = null,
    onExitArchive: () -> Unit = {},
    onWindowChanged: (Long, Long, List<LiveTvChannel>) -> Unit = { _, _, _ -> },
    guideDays: List<String> = emptyList(),
    guideLoading: Boolean = false,
    guideFailed: Boolean = false,
    onRefreshGuide: () -> Unit = {},
    playbackFailure: String? = null,
    retryPlayback: (() -> Unit)? = null,
    replaySearch: TvGuideReplaySearch? = null,
    adultChannelsLocked: Boolean = false,
    requestAdultAccess: () -> Unit = {},
    relockAdultChannels: (() -> Unit)? = null,
) {
    var page by rememberSaveable { mutableStateOf(TvPage.Live) }
    var previewMuted by rememberSaveable { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var countryMenu by remember { mutableStateOf(false) }
    var adultCountryReset by rememberSaveable { mutableStateOf(false) }
    fun openAdultChannels() {
        adultCountryReset = country != null
        country = null
        favoritesOnly = false
        query = ""
        category = "Adulte"
        if (adultChannelsLocked) requestAdultAccess()
    }
    val all = channels.orEmpty()
    val countries = remember(all) { all.map { it.country ?: Missing }.distinct().sorted() }
    val filters = remember(all, page, favoritesOnly, favorites, country) {
        all.filter { channel ->
            (!favoritesOnly || channel.id in favorites) &&
                (page != TvPage.Replay || channel.variants.any { it.replayAvailable }) &&
                (country == null || (channel.country ?: Missing) == country)
        }
    }
    val term = remember(query) { tvSearchTerm(query) }
    var results by remember(term, filters, page) { mutableStateOf<TvSearchResults?>(null) }
    LaunchedEffect(term, filters, guide, page, now / 60) {
        if (term.isNotEmpty()) results = withContext(Dispatchers.Default) {
            findTvBroadcasts(filters, guide, term, now, replayOnly = page == TvPage.Replay)
        }
    }
    val archiveStations = remember(filters, favorites, category) {
        filters.filter { c -> c.variants.any { it.replayAvailable } &&
            (category == null || category in c.categories || category == Missing && c.categories.isEmpty()) }
            .sortedByDescending { it.id in favorites }
    }
    val archiveSearch = rememberTvReplayResults(replaySearch, archiveStations, term,
        prepare = page == TvPage.Guide || page == TvPage.Replay, now = now)
    val broadcastMatches = remember(results, archiveSearch.matches, page, replaySearch, now / 60) {
        val guideMatches = if (page == TvPage.Replay && replaySearch != null) emptyList() else results?.programmes.orEmpty()
        (guideMatches + archiveSearch.matches).distinctBy { (channel, program) -> channel.id to program.start }
            .sortedWith(tvBroadcastOrder(now))
    }
    val scoped = remember(term, filters, results, broadcastMatches, page, replaySearch) {
        if (term.isEmpty()) filters else {
            val stations = results?.stations.orEmpty().filter { page != TvPage.Replay || replaySearch == null ||
                tvSearchTerm(it.name).contains(term) || it.variants.any { v -> tvSearchTerm(v.entry.title).contains(term) } }
            (stations + broadcastMatches.map { it.first }).distinctBy { it.id }
        }
    }
    val categories = remember(scoped, category, adultChannelsLocked) {
        // Keep an active filter available even if it currently has no matching channels.
        (scoped.flatMap { it.categories.ifEmpty { setOf(Missing) } } + listOfNotNull(category) + (if (adultChannelsLocked) listOf("Adulte") else emptyList()))
            .distinct().sortedWith(compareBy<String> { categoryOrder[it] ?: categoryOrder.size }.thenBy { it })
    }
    val visible = remember(scoped, category, guide, page, now / 60) {
        val matches = scoped.filter { category == null || category in it.categories || category == Missing && it.categories.isEmpty() }
        if (guide.isEmpty() || page == TvPage.Replay) matches else {
            // Keep the portal order inside each group, including channels without a listing.
            val (listed, unlisted) = matches.partition { channel ->
                channel.variants.any { variant -> guide[variant.entry.id].orEmpty().any { now in it.start until it.end } }
            }
            listed + unlisted
        }
    }
    val notifyWindow by rememberUpdatedState(onWindowChanged)
    LaunchedEffect(page, visible.isEmpty()) {
        if (page == TvPage.Replay || visible.isEmpty()) notifyWindow(0, 0, emptyList())
    }
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
            val pad = if (maxWidth >= 780.dp) 40.dp else 20.dp
            Column(Modifier.fillMaxSize().padding(top = (topPadding ?: 0.dp) + 16.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = pad),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.lisy_tv_title), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold)
                    Row(Modifier.clip(pill).background(colors.surfaceVariant).padding(4.dp).selectableGroup()) {
                        TvPage.entries.forEach { tab ->
                            val selected = tab == page
                            val label = when (tab) {
                                TvPage.Live -> Res.string.lisy_tv_direct
                                TvPage.Guide -> Res.string.lisy_tv_guide
                                TvPage.Replay -> Res.string.lisy_tv_replay
                            }
                            Box(Modifier.clip(pill).background(if (selected) colors.onSurface else Color.Transparent)
                                .selectable(selected, role = Role.Tab) { if (tab != page) onExitArchive(); page = tab }
                                .padding(horizontal = 16.dp, vertical = 9.dp)) {
                                Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) colors.surface else colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (page == TvPage.Replay && archiveContent != null) {
                    Box(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 12.dp)) { archiveContent() }
                    return@Column
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = pad),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    TvSearch(query, { query = it })
                    Box {
                        TextButton(onClick = { countryMenu = true }, shape = pill) {
                            Icon(Icons.Rounded.Public, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(when (country) {
                                null -> stringResource(Res.string.lisy_tv_all_countries)
                                Missing -> stringResource(Res.string.lisy_tv_unknown_country)
                                else -> country.orEmpty()
                            })
                            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(countryMenu, { countryMenu = false }, shape = RoundedCornerShape(18.dp)) {
                            (listOf<String?>(null) + countries).forEach { value ->
                                DropdownMenuItem(text = { Text(when (value) {
                                    null -> stringResource(Res.string.lisy_tv_all_countries)
                                    Missing -> stringResource(Res.string.lisy_tv_unknown_country)
                                    else -> value
                                }) }, onClick = { country = value; countryMenu = false; adultCountryReset = false })
                            }
                        }
                    }
                    Row(
                        Modifier.clip(pill).toggleable(favoritesOnly, role = Role.Switch) { favoritesOnly = it }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(Res.string.lisy_tv_favorites), style = MaterialTheme.typography.labelLarge)
                        Switch(checked = favoritesOnly, onCheckedChange = null)
                    }
                    if (adultChannelsLocked || all.any { it.requiresParentalPin }) {
                        TextButton(
                            onClick = ::openAdultChannels,
                            shape = pill,
                        ) {
                            Icon(if (adultChannelsLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.tv_parental_channels))
                        }
                    }
                }
                val categoryList = rememberLazyListState()
                val scope = rememberCoroutineScope()
                Row(Modifier.fillMaxWidth().padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically) {
                    if (categoryList.canScrollBackward) {
                        IconButton(onClick = { scope.launch { categoryList.animateScrollBy(-categoryList.layoutInfo.viewportSize.width * .8f) } }) {
                            Icon(Icons.Rounded.ChevronLeft, stringResource(Res.string.lisy_tv_previous_categories))
                        }
                    }
                    LazyRow(
                        state = categoryList, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(listOf<String?>(null) + categories) { value ->
                            val label = when (value) {
                                null -> stringResource(Res.string.lisy_tv_all_channels)
                                Missing -> stringResource(Res.string.lisy_tv_no_category)
                                "Adulte" -> stringResource(Res.string.tv_parental_channels)
                                else -> value
                            }
                            val selected = category == value
                            Box(Modifier.clip(pill).background(if (selected) tvRed.copy(alpha = .22f) else Color.Transparent)
                                .selectable(selected, role = Role.RadioButton) {
                                    if (value == "Adulte") openAdultChannels() else category = value
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    if (value == "Adulte" && adultChannelsLocked)
                                        Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                                    Text(label, style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) Color(0xFFFFA8AF) else colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (categoryList.canScrollForward) {
                        IconButton(onClick = { scope.launch { categoryList.animateScrollBy(categoryList.layoutInfo.viewportSize.width * .8f) } }) {
                            Icon(Icons.Rounded.ChevronRight, stringResource(Res.string.lisy_tv_next_categories))
                        }
                    }
                }
                if (category == "Adulte" && adultCountryReset) {
                    Text(stringResource(Res.string.tv_adult_catalog_all_countries),
                        Modifier.padding(horizontal = pad, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (category == "Adulte" && adultChannelsLocked) {
                    Column(Modifier.fillMaxWidth().padding(pad), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(Res.string.tv_parental_locked), color = colors.onSurfaceVariant)
                        TextButton(onClick = requestAdultAccess) { Text(stringResource(Res.string.tv_parental_unlock)) }
                    }
                    return@Column
                }
                if (category == "Adulte" && relockAdultChannels != null) {
                    TextButton(onClick = relockAdultChannels, modifier = Modifier.padding(start = pad)) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.tv_parental_relock))
                    }
                }
                val matchingProgrammes = remember(broadcastMatches, category) {
                    broadcastMatches.filter { (c, _) -> category == null || category in c.categories || category == Missing && c.categories.isEmpty() }
                }
                if (term.isNotEmpty() && (page == TvPage.Guide || page == TvPage.Replay)) {
                    val message = when {
                        archiveSearch.busy -> Res.string.tv_search_building_history
                        archiveSearch.partial -> Res.string.tv_search_partial_history
                        else -> null
                    }
                    if (message != null) Text(if (archiveSearch.busy) stringResource(message, archiveSearch.percent) else stringResource(message),
                        Modifier.padding(start = pad, end = pad, bottom = 8.dp),
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (matchingProgrammes.isNotEmpty()) {
                    TvBroadcastResults(matchingProgrammes, now, playingId, pad, lookupReplay, onLive, onReplay)
                }
                if (playbackFailed) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically) {
                        Text(playbackFailure ?: stringResource(Res.string.lisy_tv_play_error), Modifier.weight(1f), color = colors.error)
                        if (retryPlayback != null) TextButton(onClick = retryPlayback) { Text(stringResource(Res.string.action_retry)) }
                    }
                }
                when {
                    loadFailed -> Column(Modifier.padding(pad)) {
                        Text(stringResource(Res.string.lisy_tv_load_error))
                        TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
                    }
                    channels == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    term.isNotEmpty() && results == null -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    visible.isEmpty() && term.isNotEmpty() && archiveSearch.busy -> Unit
                    visible.isEmpty() -> Column(Modifier.fillMaxWidth().padding(pad), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(if (page == TvPage.Replay && all.none { c -> c.variants.any { it.replayAvailable } })
                            Res.string.lisy_tv_no_archive_channels else if (query.isNotBlank()) Res.string.tv_search_no_matches else Res.string.lisy_tv_empty))
                        if (query.isNotBlank() || favoritesOnly || category != null || country != null) {
                            TextButton(onClick = { query = ""; favoritesOnly = false; country = null; category = null }) {
                                Text(stringResource(Res.string.lisy_tv_reset))
                            }
                        }
                    }
                    page == TvPage.Replay -> key(category, country, favoritesOnly, query) {
                        TvReplayChannels(visible, favorites, pad, onArchive, onFavorite)
                    }
                    page == TvPage.Guide -> key(category, country, favoritesOnly, query) {
                        TvGuide(visible, guide, now, pad, playingId, onLive, lookupReplay = lookupReplay, onReplay = onReplay,
                            guideDays = guideDays, onWindowChanged = onWindowChanged, guideLoading = guideLoading, guideFailed = guideFailed,
                            onRefreshGuide = onRefreshGuide)
                    }
                    else -> key(page, category, country, favoritesOnly, query) {
                        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                        var previewId by remember { mutableStateOf<String?>(null) }
                        val active = com.nuvio.app.core.ui.LocalScreenActive.current
                        val viewportCallback by rememberUpdatedState(onWindowChanged)
                        val halfHour = now / 1800 * 1800
                        LaunchedEffect(grid, visible, halfHour, active) {
                            if (active) snapshotFlow { grid.layoutInfo.visibleItemsInfo.mapNotNull { visible.getOrNull(it.index) } }
                                .collect { stations -> if (stations.isNotEmpty()) viewportCallback(halfHour, halfHour + 3600, stations) }
                        }
                        LazyVerticalGrid(
                            state = grid, columns = GridCells.Adaptive(200.dp), modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = pad, end = pad, bottom = 32.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            items(visible, key = { it.id }) { channel ->
                                val schedule = remember(guide, channel) { channel.scheduleFrom(guide) }
                                val current = schedule.firstOrNull { now in it.start until it.end }
                                val preferred = remember(channel, sourceRevision) { selectedSource(channel) }
                                TvHoverCard(
                                    channel = channel, programme = current, next = schedule.firstOrNull { it.start > now },
                                    now = now, favorite = channel.id in favorites, selected = preferred,
                                    loading = playingId == channel.id, enabled = playingId == null,
                                    hoverEnabled = active && !grid.isScrollInProgress && playingId == null && previewStream != null,
                                    expanded = previewId == channel.id,
                                    onExpand = { previewId = channel.id },
                                    onDismiss = { if (previewId == channel.id) previewId = null },
                                    onPlay = { previewId = null; onLive(channel) },
                                    onFavorite = { onFavorite(channel) },
                                    onSelect = { onSelectSource(channel, it) },
                                    resolve = previewStream, isolated = channel.id in isolatedSources,
                                    onSourceSeparation = onSourceSeparation,
                                    muted = previewMuted,
                                    onToggleSound = { previewMuted = !previewMuted },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSearch(query: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(Res.string.lisy_tv_search)
    BasicTextField(
        query, onChange, Modifier.widthIn(min = 140.dp, max = 300.dp).clip(pill)
            .background(colors.surfaceVariant).semantics { contentDescription = label },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.onSurface),
        decorationBox = { field ->
            Row(Modifier.padding(start = 14.dp, end = 8.dp).height(42.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    field()
                }
                if (query.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(Res.string.lisy_tv_reset_search), Modifier.size(16.dp))
                }
            }
        },
    )
}

@Composable
internal fun TvChannelCard(
    channel: LiveTvChannel, programme: LiveTvProgram?, now: Long, favorite: Boolean,
    loading: Boolean, enabled: Boolean, selected: String?, onSelect: (String?) -> Unit,
    onFavorite: () -> Unit, onPlay: () -> Unit, onMenu: (Boolean) -> Unit,
    isolated: Boolean, onSourceSeparation: ((String, Boolean) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val action = stringResource(Res.string.lisy_tv_play)
    val progress = programme?.let { ((now - it.start).toFloat() / (it.end - it.start)).coerceIn(0f, 1f) }
    var lightPlate by remember(channel.logo) { mutableStateOf(false) }
    val plate = if (lightPlate) listOf(Color(0xFFDFE1E6), Color(0xFFB9BFCA))
        else listOf(colors.surfaceVariant, colors.surface)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.clip(RoundedCornerShape(16.dp))
        .clickable(interactionSource = interaction, indication = null, enabled = enabled,
            onClickLabel = action, role = Role.Button, onClick = onPlay)
        .semantics { contentDescription = "${channel.name}, $action" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(plate))
            .border(if (focused || hovered) 2.dp else 1.dp,
                colors.onSurface.copy(alpha = if (focused || hovered) .85f else .1f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            var loaded by remember(channel.logo) { mutableStateOf(false) }
            if (!loaded) Text(channel.name, Modifier.padding(16.dp), color = colors.onSurface,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            channel.logo?.let {
                NuvioAsyncImage(it, null, Modifier.fillMaxSize(.53f),
                    onSuccess = { loaded = true; lightPlate = it.painter.needsLightBackground() },
                    onError = { loaded = false; lightPlate = false })
            }
            programme?.let { TvProgramArtwork(it.name, it.category, it.description, it.imageUrl, Modifier.fillMaxSize(), channel) }
            if (channel.variants.size > 1 || isolated) Box(Modifier.align(Alignment.TopEnd).padding(7.dp)) {
                TvQualityChoice(channel, selected, onSelect, isolated, onSourceSeparation, onMenu)
            }
            Surface(Modifier.align(Alignment.BottomEnd).padding(10.dp), shape = CircleShape,
                color = Color.Black.copy(alpha = .6f), contentColor = Color.White) {
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                }
            }
            if (progress != null) LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                color = tvRed, trackColor = colors.onSurface.copy(alpha = .15f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(channel.flaggedName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(programme?.name ?: stringResource(Res.string.lisy_tv_no_programme),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconToggleButton(checked = favorite, onCheckedChange = { onFavorite() }) {
                Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    stringResource(if (favorite) Res.string.lisy_tv_unfavorite else Res.string.lisy_tv_favorite),
                    Modifier.size(19.dp), tint = if (favorite) colors.onSurface else colors.onSurfaceVariant)
            }
        }
    }
}

// Transparent black wordmarks need a lighter tile. Sample the decoded painter only on load.
internal fun Painter.needsLightBackground(): Boolean {
    val side = 24
    val thumbnail = ImageBitmap(side, side)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(thumbnail), Size(side.toFloat(), side.toFloat())) {
        with(this@needsLightBackground) { draw(size) }
    }
    val pixels = thumbnail.toPixelMap()
    var visible = 0
    var dark = 0
    for (y in 0 until side) for (x in 0 until side) {
        val color = pixels[x, y]
        if (color.alpha >= .5f) {
            visible++
            if (color.luminance() < .18f) dark++
        }
    }
    return visible in 1 until (side * side * .95f).toInt() && dark > visible * .55f
}
