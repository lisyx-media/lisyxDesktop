package com.nuvio.app.features.lisy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvSourceSeparationTest {
    private fun variant(id: String, quality: String) = LiveTvVariant(
        entry = LisyEntry(id, "FR - CANAL+ SPORT $quality", "play-$id", emptyList()),
        logo = null,
        category = "Sport",
        country = "France",
        guideStationId = "CanalPlusSport.fr",
    )

    @Test
    fun separatesOnlyFlaggedSourceAndRetainsItsCommandGuideAndCategory() {
        val uhd = variant("uhd", "UHD")
        val fhd = variant("fhd", "FHD")
        val original = groupLiveTvChannels(listOf(uhd, fhd))
        val result = separateLiveTvSources(original, setOf("uhd"))
        assertEquals(2, result.size)
        assertEquals(listOf("fhd"), result.first().variants.map { it.entry.id })
        assertEquals("France", result.first().country)
        assertEquals("CANAL+ SPORT · UHD", result.last().name)
        assertNull(result.last().country)
        assertNull(result.last().variants.single().country)
        assertEquals(uhd.entry, result.last().variants.single().entry)
        assertEquals(uhd.guideStationId, result.last().variants.single().guideStationId)
        assertEquals(setOf("Sport"), result.last().categories)
        assertEquals(2, original.single().variants.size)
    }

    @Test
    fun allFlaggedSourcesStayPlayableWithUniqueIdsAndNoEmptyChannel() {
        val original = groupLiveTvChannels(listOf(variant("uhd", "UHD"), variant("fhd", "FHD")))
        val result = separateLiveTvSources(original, setOf("uhd", "fhd", "missing"))
        assertEquals(setOf("uhd", "fhd"), result.map { it.id }.toSet())
        assertTrue(result.all { it.variants.size == 1 && it.country == null })
        assertEquals(original.flatMap { it.variants }.map { it.entry }.toSet(),
            result.flatMap { it.variants }.map { it.entry }.toSet())
    }

    @Test
    fun noFlagOrUnknownSourceLeavesTheLineupUntouched() {
        val original = groupLiveTvChannels(listOf(variant("fhd", "FHD")))
        assertEquals(original, separateLiveTvSources(original, emptySet()))
        assertEquals(original, separateLiveTvSources(original, setOf("another-source")))
    }
}
