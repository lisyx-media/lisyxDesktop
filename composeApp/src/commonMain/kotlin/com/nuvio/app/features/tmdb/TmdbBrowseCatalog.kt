package com.nuvio.app.features.tmdb

import com.nuvio.app.features.watchprogress.CurrentDateProvider
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

internal data class TmdbBrowseRequest(
    val id: String,
    val endpoint: String,
    val query: Map<String, String> = emptyMap(),
)

/** Unknown or malformed dates are not evidence that a title has been released. */
internal fun isReleasedTmdbTitle(date: String?, today: String): Boolean =
    date != null && Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) &&
        CurrentDateProvider.localStartOfDayEpochMs(date) != null && date <= today

internal fun tmdbBrowseRequests(type: TmdbEntityMediaType, today: String, language: String): List<TmdbBrowseRequest> {
    val movie = type == TmdbEntityMediaType.MOVIE
    val media = type.value
    val released = mapOf(
        (if (movie) "primary_release_date.lte" else "first_air_date.lte") to today,
        "include_adult" to "false", "include_null_first_air_dates" to "false",
    )
    fun discover(id: String, vararg extra: Pair<String, String>) = TmdbBrowseRequest(
        id, "discover/$media", released + mapOf("sort_by" to "popularity.desc") + mapOf(*extra),
    )
    return buildList {
        add(TmdbBrowseRequest("trending", "trending/$media/week"))
        add(discover("popular"))
        if (!movie) add(TmdbBrowseRequest("airing_today", "tv/airing_today", mapOf("timezone" to "Europe/Paris")))
        add(TmdbBrowseRequest("top_rated", "$media/top_rated"))
        add(TmdbBrowseRequest("new", "$media/${if (movie) "now_playing" else "on_the_air"}", mapOf("region" to "FR")))
        add(discover("year", (if (movie) "primary_release_year" else "first_air_date_year") to today.take(4)))
        add(discover("language", "with_original_language" to language.substringBefore('-')))
        for ((id, provider) in listOf("netflix" to "8", "disney" to "337", "prime" to "119")) {
            add(discover(id, "with_watch_providers" to provider, "watch_region" to "FR", "with_watch_monetization_types" to "flatrate"))
        }
        add(discover("international", "with_origin_country" to "FR|DE|IT|ES|KR|JP|IN"))
        if (movie) add(discover("independent", "with_keywords" to "281237"))
        else add(discover("limited", "with_type" to "2"))
        add(discover("documentary", "with_genres" to "99"))
        add(discover("family", "with_genres" to "10751"))
        add(discover("action", "with_genres" to if (movie) "28" else "10759"))
        add(discover("comedy", "with_genres" to "35"))
        add(discover("drama", "with_genres" to "18"))
    }
}

internal fun tmdbBrowseRowTitle(id: String, type: TmdbEntityMediaType): StringResource {
    val movie = type == TmdbEntityMediaType.MOVIE
    return when (id) {
        "trending" -> if (movie) Res.string.home_discovery_trending_movies else Res.string.home_discovery_trending_series
        "popular" -> if (movie) Res.string.lisyx_catalog_popular_movies else Res.string.lisyx_catalog_popular_series
        "top_rated" -> if (movie) Res.string.lisyx_catalog_acclaimed_movies else Res.string.lisyx_catalog_acclaimed_series
        "airing_today" -> Res.string.lisyx_catalog_airing_today
        "new" -> if (movie) Res.string.lisyx_catalog_new_movies else Res.string.lisyx_catalog_new_series
        "year" -> if (movie) Res.string.lisyx_catalog_year_movies else Res.string.lisyx_catalog_year_series
        "language" -> if (movie) Res.string.lisyx_catalog_language_movies else Res.string.lisyx_catalog_language_series
        "netflix" -> if (movie) Res.string.lisyx_catalog_netflix_movies else Res.string.lisyx_catalog_netflix_series
        "disney" -> if (movie) Res.string.lisyx_catalog_disney_movies else Res.string.lisyx_catalog_disney_series
        "prime" -> if (movie) Res.string.lisyx_catalog_prime_movies else Res.string.lisyx_catalog_prime_series
        "international" -> if (movie) Res.string.lisyx_catalog_international_movies else Res.string.lisyx_catalog_international_series
        "independent" -> Res.string.lisyx_catalog_independent_movies
        "limited" -> Res.string.lisyx_catalog_limited_series
        "documentary" -> if (movie) Res.string.lisyx_catalog_documentary_movies else Res.string.lisyx_catalog_documentary_series
        "family" -> if (movie) Res.string.lisyx_catalog_family_movies else Res.string.lisyx_catalog_family_series
        "action" -> if (movie) Res.string.collections_editor_tmdb_genre_action else Res.string.media_browse_action_adventure
        "comedy" -> Res.string.collections_editor_tmdb_genre_comedy
        "drama" -> Res.string.collections_editor_tmdb_genre_drama
        else -> Res.string.details_browse_rail_popular
    }
}
