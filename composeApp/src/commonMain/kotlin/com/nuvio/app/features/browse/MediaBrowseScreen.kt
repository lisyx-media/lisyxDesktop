package com.nuvio.app.features.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.ui.LocalNuvioNavBarScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.home.rememberHomeHeroState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalVerticalBarPadding
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.nuvioHorizontalScrollBleed
import com.nuvio.app.core.ui.posterGridColumnCountForViewport
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.HomeSkeletonHero
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.tmdb.tmdbBrowseRowTitle
import com.nuvio.app.features.tmdb.TmdbBrowseRows
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingState
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MediaBrowseScreen(
    mediaType: TmdbEntityMediaType,
    isSelectedTab: Boolean,
    modifier: Modifier = Modifier,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    topChromePadding: Dp? = null,
) {
    val settings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val apiKey = settings.apiKey.ifBlank { TmdbSettingsRepository.effectiveApiKey() }
    val language = settings.language
    var retryAttempt by remember { mutableIntStateOf(0) }
    var browse by remember(mediaType, apiKey, language, retryAttempt) {
        mutableStateOf<TmdbBrowseRows?>(null)
    }
    ScreenActivityEffect(mediaType, apiKey, language, retryAttempt) { active ->
        if (active && browse == null) {
            browse = TmdbMetadataService.fetchBrowseRows(mediaType, language)
        }
    }

    val watched by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val fullyWatchedSeriesKeys by WatchedRepository.fullyWatchedSeriesKeys.collectAsStateWithLifecycle()
    val rows = browse?.rows.orEmpty()
    val genres = remember(rows) { rows.map { it.id }.filter { it in BrowseGenres } }
    var selectedGenre by rememberSaveable(mediaType.name) { mutableStateOf<String?>(null) }
    val visibleGenre = selectedGenre?.takeIf { it in genres }
    val genreItems = rows.firstOrNull { it.id == visibleGenre }?.items.orEmpty()
    val heroItems = remember(rows) {
        rows.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { !it.banner.isNullOrBlank() }
            .distinctBy(MetaPreview::stableKey)
            .take(5)
            .toList()
    }
    val hero = rememberHomeHeroState(heroItems)
    val listState = rememberLazyListState()
    val navigationScroll = LocalNuvioNavBarScrollState.current
    LaunchedEffect(listState, navigationScroll, mediaType) {
        snapshotFlow { listState.firstVisibleItemIndex * 80f + listState.firstVisibleItemScrollOffset }
            .collect { offset -> navigationScroll?.updateScrollOffset(
                if (mediaType == TmdbEntityMediaType.MOVIE) AppScreenTab.Films else AppScreenTab.Series, offset,
            ) }
    }
    val hasHero = visibleGenre == null && heroItems.isNotEmpty()
    LaunchedEffect(isSelectedTab, visibleGenre, hasHero) {
        if (isSelectedTab) listState.requestScrollToItem(0)
    }
    val pageTitle = stringResource(
        if (mediaType == TmdbEntityMediaType.MOVIE) Res.string.media_movies else Res.string.media_series,
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sectionPadding = (maxWidth * .045f).coerceIn(24.dp, 80.dp)
        val viewportHeight = maxHeight
        val heroModifier = Modifier.nuvioHorizontalScrollBleed(LocalVerticalBarPadding.current)
        val posterStyle = rememberPosterCardStyleUiState()
        val gridColumns = posterGridColumnCountForViewport(
            screenWidth = maxWidth - sectionPadding * 2,
            screenHeight = maxHeight,
            basePosterWidthDp = posterStyle.widthDp,
        )

        NuvioScreen(
            horizontalPadding = 0.dp,
            topPadding = if (visibleGenre == null) 0.dp else topChromePadding,
            listState = listState,
        ) {
            // Keep the first item's key stable so loading the catalog doesn't anchor the list to the header.
            if (hasHero || (visibleGenre == null && browse == null)) {
                item(key = "browse_hero") {
                    if (browse == null) {
                        HomeSkeletonHero(
                            modifier = heroModifier,
                            viewportHeight = viewportHeight,
                            sectionPadding = sectionPadding,
                        )
                    } else {
                        HomeHeroSection(
                            items = hero.items,
                            modifier = heroModifier,
                            viewportHeight = viewportHeight,
                            sectionPadding = sectionPadding,
                            onItemClick = onPosterClick,
                            desktopTopInset = topChromePadding ?: 0.dp,
                        )
                    }
                }
            }
            item(key = "browse_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sectionPadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pageTitle,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (genres.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "all") {
                                FilterChip(
                                    selected = visibleGenre == null,
                                    onClick = { selectedGenre = null },
                                    label = { Text(stringResource(Res.string.media_browse_all)) },
                                    shape = RoundedCornerShape(4.dp),
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.White, selectedLabelColor = Color.Black),
                                )
                            }
                            items(genres, key = { it }) { genre ->
                                FilterChip(
                                    selected = visibleGenre == genre,
                                    onClick = { selectedGenre = genre },
                                    label = { Text(stringResource(tmdbBrowseRowTitle(genre, mediaType))) },
                                    shape = RoundedCornerShape(4.dp),
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.White, selectedLabelColor = Color.Black),
                                )
                            }
                        }
                    }
                }
            }

            when {
                browse == null -> {
                    item(key = "browse_rows_loading") { HomeSkeletonRow(horizontalPadding = sectionPadding) }
                }

                rows.isEmpty() -> {
                    item(key = "browse_empty") {
                        HomeEmptyStateCard(
                            title = stringResource(
                                if (browse?.hasFailures == true) Res.string.home_discovery_unavailable_title
                                else Res.string.media_browse_empty,
                            ),
                            message = stringResource(Res.string.media_browse_refresh_message),
                            modifier = Modifier.padding(horizontal = sectionPadding),
                            actionLabel = stringResource(Res.string.action_retry),
                            onActionClick = { retryAttempt++ },
                        )
                    }
                }

                visibleGenre != null -> {
                    items(genreItems.chunked(gridColumns), key = { it.first().stableKey() }) { posters ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = sectionPadding),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            posters.forEach { poster ->
                                Box(Modifier.weight(1f)) {
                                    HomePosterCard(
                                        item = poster,
                                        forcePoster = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        isWatched = WatchingState.isPosterWatched(
                                            watchedKeys = watched.watchedKeys,
                                            item = poster,
                                            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                                        ),
                                        onClick = onPosterClick?.let { { it(poster) } },
                                        onLongClick = onPosterLongClick?.let { { it(poster) } },
                                    )
                                }
                            }
                            repeat(gridColumns - posters.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                else -> {
                    items(rows, key = { it.id }, contentType = { "browse_shelf" }) { row ->
                        HomeCatalogRowSection(
                            title = stringResource(tmdbBrowseRowTitle(row.id, mediaType)),
                            entries = row.items,
                            sectionPadding = sectionPadding,
                            modifier = Modifier.padding(bottom = 12.dp),
                            watchedKeys = watched.watchedKeys,
                            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                            onPosterClick = onPosterClick,
                            onPosterLongClick = onPosterLongClick,
                        )
                    }
                    if (browse?.hasFailures == true) {
                        item(key = "browse_partial_error") {
                            HomeEmptyStateCard(
                                title = stringResource(Res.string.media_browse_partial_error),
                                message = stringResource(Res.string.media_browse_refresh_message),
                                modifier = Modifier.padding(horizontal = sectionPadding),
                                actionLabel = stringResource(Res.string.action_retry),
                                onActionClick = { retryAttempt++ },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val BrowseGenres = setOf("action", "comedy", "drama")
