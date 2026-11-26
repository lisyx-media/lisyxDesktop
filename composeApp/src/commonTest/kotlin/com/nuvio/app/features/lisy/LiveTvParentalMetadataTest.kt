package com.nuvio.app.features.lisy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvParentalMetadataTest {
    @Test
    fun portalLocksPropagateAcrossQualityVariantsAndGenreMetadata() = runTest {
        val channels = readChannels("""[
            {"id":"1","name":"FR - Fixture HD","country":"FR","tv_genre_id":"safe","lock":1},
            {"id":"2","name":"FR - Fixture FHD","country":"FR","tv_genre_id":"safe","lock":0},
            {"id":"3","name":"Censored station","tv_genre_id":"safe","censored":true},
            {"id":"4","name":"Genre station","tv_genre_id":"restricted"},
            {"id":"5","name":"Adult Swim","tv_genre_id":"swim","lock":0,"censored":false},
            {"id":"6","name":"Smooth Radio","tv_genre_id":"music","lock":"0"}
        ]""", """[
            {"id":"safe","title":"Généralistes"},
            {"id":"restricted","title":"Films","locked":"true"},
            {"id":"swim","title":"Adult Swim"},
            {"id":"music","title":"Adult Contemporary"}
        ]""")
        val merged = channels.single { channel -> channel.variants.any { it.entry.id == "1" } }
        assertEquals(setOf("1", "2"), merged.variants.map { it.entry.id }.toSet())
        assertTrue(merged.requiresParentalPin)
        assertEquals(setOf("Adulte"), merged.categories)
        assertFalse(merged.variants.single { it.entry.id == "2" }.parentalLock)
        for (id in listOf("3", "4")) {
            val channel = channels.single { c -> c.variants.any { it.entry.id == id } }
            assertTrue(channel.requiresParentalPin, "Portal restriction on $id must survive grouping")
            assertEquals(setOf("Adulte"), channel.categories)
        }
        for (id in listOf("5", "6")) {
            val channel = channels.single { c -> c.variants.any { it.entry.id == id } }
            assertFalse(channel.requiresParentalPin, "Ordinary entertainment/music must remain accessible")
            assertFalse("Adulte" in channel.categories)
        }
    }

    @Test
    fun everySupportedPortalRestrictionFieldAcceptsNumericAndBooleanValues() = runTest {
        val fields = listOf("lock", "censored", "locked", "is_adult", "adult", "parental_lock")
        for (value in listOf("1", "true", "\"true\"", "\"2\"")) {
            val rows = fields.mapIndexed { index, field ->
                """{"id":"$index","name":"Fixture $index","$field":$value}"""
            }.joinToString(",", "[", "]")
            val channels = readChannels(rows, "[]")
            assertEquals(fields.size, channels.size)
            assertTrue(channels.all { it.requiresParentalPin }, "Restriction value $value")
        }
    }

    private suspend fun readChannels(rows: String, genres: String): List<LiveTvChannel> {
        val engine = MockEngine { request ->
            respond(when (request.url.parameters["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_all_channels" -> """{"js":{"data":$rows}}"""
                "get_genres" -> """{"js":$genres}"""
                else -> error("Unexpected portal action")
            })
        }
        return HttpClient(engine).use { client ->
            LisyPortal(LisyServer("Fixture", "https://portal.example/c", "00:11:22:33:44:55"), client).liveChannels()
        }
    }
}
