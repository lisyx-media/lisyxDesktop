package com.nuvio.app.features.tmdb

import com.nuvio.app.isDesktop

internal val defaultTmdbLanguage: String
    get() = if (isDesktop) "fr-FR" else "en"

data class TmdbSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val language: String = defaultTmdbLanguage,
    val useTrailers: Boolean = true,
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useSeasonPosters: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useCollections: Boolean = true,
)
