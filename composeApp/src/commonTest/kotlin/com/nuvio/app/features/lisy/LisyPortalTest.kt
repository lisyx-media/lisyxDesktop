package com.nuvio.app.features.lisy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LisyPortalTest {
    @Test
    fun explicitIdentityWinsAndTitleFallbackRequiresMatchingYear() {
        val title = listOf("L’Odyssée")
        val row = LisyEntry("1", "FR - L'Odyssée (2026) [4K]", "test", emptyList())
        assertTrue(matchesLisyTitle(row, "42", title, "2026"))
        assertFalse(matchesLisyTitle(row, "42", title, "2025"))
        assertFalse(matchesLisyTitle(row.copy(tmdbId = "41"), "42", title, "2026"))
        assertTrue(matchesLisyTitle(row.copy(tmdbId = "42"), "42", title, null))
    }

    @Test
    fun authenticatesListsAndResolvesSeriesEpisode() = runTest {
        val actions = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals("/stalker_portal/server/load.php", request.url.encodedPath)
            assertEquals("mac=00%3A11%3A22%3A33%3A44%3A55; stb_lang=fr; timezone=Europe%2FParis", request.headers[HttpHeaders.Cookie])
            val action = checkNotNull(request.url.parameters["action"])
            actions.add(action)
            if (action != "handshake") assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            respond(when (action) {
                "handshake" -> """{"js":{"token":"test-token"}}"""
                "get_profile" -> """{"js":{"id":1}}"""
                "get_ordered_list" -> {
                    assertEquals("42", request.url.parameters["movie_id"])
                    """{"js":{"data":[{"id":"12","name":"Saison 1","cmd":"ffmpeg http://portal.example/play?stream=7","series":[1,2]}],"total_items":1}}"""
                }
                "create_link" -> {
                    assertEquals("vod", request.url.parameters["type"])
                    assertEquals("2", request.url.parameters["series"])
                    """{"js":{"cmd":"ffmpeg https://video.example/play?stream="}}"""
                }
                else -> error("Unexpected action")
            })
        }
        HttpClient(engine).use { client ->
            val portal = LisyPortal(LisyServer("Test", "https://portal.example/stalker_portal/c/", "00:11:22:33:44:55"), client)
            val row = portal.list(LisyCatalog.Series, 1, "", seriesId = "42").entries.single()
            assertEquals(listOf(1, 2), row.episodes)
            assertEquals("https://video.example/play?stream=7", portal.stream(LisyCatalog.Series, row, 2))
        }
        assertEquals(listOf("handshake", "get_profile", "get_ordered_list", "create_link"), actions)
    }
}
