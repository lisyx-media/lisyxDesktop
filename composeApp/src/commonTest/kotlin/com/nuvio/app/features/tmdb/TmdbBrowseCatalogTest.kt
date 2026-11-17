package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class TmdbBrowseCatalogTest {
    @Test
    fun onlyKnownPastOrCurrentReleaseDatesAreEligible() {
        val today = "2026-11-12"
        assertTrue(isReleasedTmdbTitle("2026-11-12", today))
        assertTrue(isReleasedTmdbTitle("2025-12-31", today))
        assertFalse(isReleasedTmdbTitle("2026-11-13", today))
        assertFalse(isReleasedTmdbTitle("2027-01-01", today))
        assertFalse(isReleasedTmdbTitle(null, today))
        assertFalse(isReleasedTmdbTitle("", today))
        assertFalse(isReleasedTmdbTitle("2026", today))
        assertFalse(isReleasedTmdbTitle("2026-02-30", today))
    }

    @Test
    fun discoverQueriesFilterReleaseDatesForBothMediaTypes() {
        for (type in TmdbEntityMediaType.entries) {
            val dateKey = if (type == TmdbEntityMediaType.MOVIE) "primary_release_date.lte" else "first_air_date.lte"
            val rows = tmdbBrowseRequests(type, "2026-11-12", "fr-FR")
            rows.filter { it.endpoint.startsWith("discover/") }.forEach {
                assertEquals("2026-11-12", it.query[dateKey])
                assertEquals("false", it.query["include_adult"])
            }
            assertEquals(rows.size, rows.map { it.id }.distinct().size)
            assertEquals("fr", rows.single { it.id == "language" }.query["with_original_language"])
            for (provider in listOf("netflix", "disney", "prime")) {
                assertEquals("FR", rows.single { it.id == provider }.query["watch_region"])
            }
        }
    }
}
