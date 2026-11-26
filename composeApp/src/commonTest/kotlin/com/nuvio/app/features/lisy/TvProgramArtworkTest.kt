package com.nuvio.app.features.lisy

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvProgramArtworkTest {
    private fun show(id: String, title: String, year: String = "1999") =
        TmdbEntityMediaType.TV to MetaPreview(
            id = id, type = "series", name = title, releaseInfo = year,
            banner = "https://image.tmdb.org/t/p/w1280/$id.jpg",
        )

    @Test
    fun realPortalTitleMatchesTheLocalizedSeriesDespitePunctuation() {
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/2734.jpg",
            strictProgramArtwork("New York, unité spéciale", listOf(show("2734", "New York Unité Spéciale"))),
        )
    }

    @Test
    fun genericGuideRowsAndAmbiguousTitlesNeverReceiveInventedArtwork() {
        assertNull(artworkQuery("Programmes de nuit - TF1"))
        assertNull(strictProgramArtwork("Journal", listOf(show("1", "Journal"))))
        assertNull(strictProgramArtwork("Dune", listOf(show("1", "Dune", "2000"), show("2", "Dune", "2021"))))
        assertNull(strictProgramArtwork("Dune", listOf(show("1", "Dune", "2000"), show("2", "Dune", "2021").let { it.first to it.second.copy(banner = null) })))
        assertNull(strictProgramArtwork("New York, unité spéciale", listOf(show("3", "New York Police Judiciaire"))))
    }

    @Test
    fun explicitReleaseYearDisambiguatesWithoutDiscardingIt() {
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/2.jpg",
            strictProgramArtwork("Dune (2021)", listOf(show("1", "Dune", "2000"), show("2", "Dune", "2021"))),
        )
    }
}
