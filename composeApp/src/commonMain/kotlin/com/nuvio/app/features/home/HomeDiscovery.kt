package com.nuvio.app.features.home

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.tmdb.TmdbBrowseRow
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.isDesktop
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class HomeDiscoveryShelf(val mediaType: TmdbEntityMediaType, val row: TmdbBrowseRow) {
    val key: String get() = "${mediaType.value}:${row.id}"
}

internal data class HomeDiscovery(
    val isLoading: Boolean = false,
    val shelves: List<HomeDiscoveryShelf> = emptyList(),
) {
    val movies: List<MetaPreview> get() = shelves.firstOrNull { it.mediaType == TmdbEntityMediaType.MOVIE && it.row.id == "trending" }?.row?.items.orEmpty()
    val series: List<MetaPreview> get() = shelves.firstOrNull { it.mediaType == TmdbEntityMediaType.TV && it.row.id == "trending" }?.row?.items.orEmpty()
    val hasItems: Boolean get() = shelves.any { it.row.items.isNotEmpty() }
}

@Composable
internal fun rememberHomeDiscovery(refresh: Int): HomeDiscovery {
    if (!isDesktop) return HomeDiscovery()
    val settings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val apiKey = settings.apiKey.ifBlank { TmdbSettingsRepository.effectiveApiKey() }
    val language = settings.language
    var discovery by remember(apiKey, language, refresh) {
        mutableStateOf(HomeDiscovery(isLoading = apiKey.isNotBlank()))
    }
    LaunchedEffect(apiKey, language, refresh) {
        if (apiKey.isBlank()) return@LaunchedEffect
        coroutineScope {
            val movies = async { TmdbMetadataService.fetchWeeklyTrending(TmdbEntityMediaType.MOVIE, language) }
            val series = async { TmdbMetadataService.fetchWeeklyTrending(TmdbEntityMediaType.TV, language) }
            discovery = HomeDiscovery(shelves = listOf(
                HomeDiscoveryShelf(TmdbEntityMediaType.MOVIE, TmdbBrowseRow("trending", movies.await())),
                HomeDiscoveryShelf(TmdbEntityMediaType.TV, TmdbBrowseRow("trending", series.await())),
            ))
        }
        coroutineScope {
            val movies = async { TmdbMetadataService.fetchBrowseRows(TmdbEntityMediaType.MOVIE, language) }
            val series = async { TmdbMetadataService.fetchBrowseRows(TmdbEntityMediaType.TV, language) }
            val movieRows = movies.await().rows.associateBy { it.id }
            val seriesRows = series.await().rows.associateBy { it.id }
            val shelves = buildList {
                for (id in listOf("trending", "popular", "airing_today", "top_rated", "new", "year", "language", "netflix", "disney", "prime", "international", "independent", "limited", "documentary", "family")) {
                    movieRows[id]?.let { add(HomeDiscoveryShelf(TmdbEntityMediaType.MOVIE, it)) }
                    seriesRows[id]?.let { add(HomeDiscoveryShelf(TmdbEntityMediaType.TV, it)) }
                }
            }
            if (shelves.isNotEmpty()) discovery = HomeDiscovery(shelves = shelves)
        }
    }
    return discovery
}
