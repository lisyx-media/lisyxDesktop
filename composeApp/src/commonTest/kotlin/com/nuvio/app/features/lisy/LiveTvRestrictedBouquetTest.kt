package com.nuvio.app.features.lisy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvRestrictedBouquetTest {
    private val server = LisyServer("Fixture", "https://portal.example/c", "00:11:22:33:44:55")

    @Test
    fun loadsEveryPageOfLabelledRestrictedBouquetsOmittedFromTheWholeLineup() = runTest {
        val pages = mutableListOf<Int>()
        val engine = MockEngine { request ->
            if (request.url.parameters["action"] != "handshake") {
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
            }
            respond(when (request.url.parameters["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_genres" -> """{"js":[{"id":"public","title":"Généralistes"},{"id":"restricted","title":"FOR ADULTS"}]}"""
                "get_all_channels" -> """{"js":{"data":[{"id":"public","name":"Public station","tv_genre_id":"public"}]}}"""
                "get_ordered_list" -> {
                    assertEquals("restricted", request.url.parameters["genre"])
                    assertEquals("restricted", request.url.parameters["category"])
                    val page = request.url.parameters["p"]!!.toInt().also(pages::add)
                    val rows = ((page - 1) * 14 until minOf(page * 14, 187)).joinToString(",") {
                        // Real portals may omit the genre and restriction flags on these rows.
                        """{"id":"private-$it","name":"Restricted station $it","cmd":"play-$it"}"""
                    }
                    """{"js":{"data":[$rows],"total_items":"187","max_page_items":"14"}}"""
                }
                else -> error("Unexpected action")
            })
        }
        val channels = HttpClient(engine).use { LisyPortal(server, it).liveChannels() }
        assertEquals((1..14).toList(), pages)
        assertEquals(188, channels.size)
        assertFalse(channels.single { it.id == "public" }.requiresParentalPin)
        val restricted = channels.filter { it.id != "public" }
        assertEquals(187, restricted.size)
        assertTrue(restricted.all { it.requiresParentalPin && it.categories == setOf("Adulte") })
        assertEquals(187, restricted.flatMap { it.variants }.map { it.entry.id }.toSet().size)
    }

    @Test
    fun reconcilesDuplicateIdsAndStopsAPortalThatRepeatsTheSamePage() = runTest {
        var pageCalls = 0
        val engine = MockEngine { request ->
            respond(when (request.url.parameters["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_genres" -> """{"js":[{"id":"protected","title":"Provider bouquet","lock":1}]}"""
                "get_all_channels" -> """{"js":[{"id":"shared","name":"Ordinary title","cmd":"play-original","tv_archive_duration":"72"}]}"""
                "get_ordered_list" -> {
                    pageCalls++
                    """{"js":[{"id":"shared","name":"Ordinary title"},{"id":"new","name":"Restricted station"}]}"""
                }
                else -> error("Unexpected action")
            })
        }
        val channels = HttpClient(engine).use { LisyPortal(server, it).liveChannels() }
        assertEquals(2, pageCalls)
        assertEquals(setOf("shared", "new"), channels.map { it.id }.toSet())
        assertTrue(channels.all { it.requiresParentalPin })
        assertEquals("play-original", channels.single { it.id == "shared" }.variants.single().entry.command)
        assertEquals(72, channels.single { it.id == "shared" }.variants.single().archiveHours)
    }

    @Test
    fun unavailableRestrictedBouquetPreservesThePublicLineup() = runTest {
        val engine = MockEngine { request ->
            if (request.url.parameters["action"] == "get_ordered_list") {
                respond("Unavailable", status = HttpStatusCode.Forbidden)
            } else {
                respond(when (request.url.parameters["action"]) {
                    "handshake" -> """{"js":{"token":"fixture"}}"""
                    "get_profile" -> """{"js":{}}"""
                    "get_genres" -> """{"js":[{"id":"protected","title":"18+"}]}"""
                    "get_all_channels" -> """{"js":{"data":[{"id":"safe","name":"Public station"}]}}"""
                    else -> error("Unexpected action")
                })
            }
        }
        val channels = HttpClient(engine).use { LisyPortal(server, it).liveChannels() }
        assertEquals(listOf("safe"), channels.map { it.id })
        assertFalse(channels.single().requiresParentalPin)
    }

    @Test
    fun ordinaryAdultSwimAndAdultContemporaryCategoriesDoNotTriggerRestrictedRequests() = runTest {
        var restrictedCalls = 0
        val engine = MockEngine { request ->
            respond(when (request.url.parameters["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_genres" -> """{"js":[{"id":"swim","title":"Adult Swim"},{"id":"music","title":"Adult Contemporary"}]}"""
                "get_all_channels" -> """{"js":[{"id":"safe","name":"Adult Swim","tv_genre_id":"swim"}]}"""
                "get_ordered_list" -> {
                    restrictedCalls++
                    """{"js":[]}"""
                }
                else -> error("Unexpected action")
            })
        }
        val channels = HttpClient(engine).use { LisyPortal(server, it).liveChannels() }
        assertEquals(0, restrictedCalls)
        assertFalse(channels.single().requiresParentalPin)
    }

    @Test
    fun ignoredGenreFilterDoesNotMovePublicStationsIntoTheRestrictedBouquet() = runTest {
        val engine = MockEngine { request ->
            respond(when (request.url.parameters["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_genres" -> """{"js":[{"id":"public","title":"Généralistes"},{"id":"private","title":"FOR ADULTS"}]}"""
                "get_all_channels", "get_ordered_list" -> """{"js":{"data":[{"id":"safe","name":"Public station","tv_genre_id":"public","tv_archive_duration":24}]}}"""
                else -> error("Unexpected action")
            })
        }
        val channels = HttpClient(engine).use { LisyPortal(server, it).liveChannels() }
        assertEquals(listOf("safe"), channels.map { it.id })
        assertFalse(channels.single().requiresParentalPin)
        assertEquals(24, channels.single().variants.single().archiveHours)
    }
}
