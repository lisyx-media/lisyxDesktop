package com.nuvio.app.features.lisy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTvCatalogTest {
    @Test
    fun groupsQualityVariantsAcrossCategoriesAndKeepsDifferentFeeds() = runTest {
        val server = LisyServer("Fixture", "https://portal.example/c", "00:11:22:33:44:55")
        val actions = mutableListOf<String>()
        val engine = MockEngine { request ->
            val action = checkNotNull(request.url.parameters["action"])
            actions += action
            respond(when (action) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{"id":1}}"""
                "get_genres" -> """{"js":[{"id":"1","title":"FR | Généralistes"},{"id":"2","title":"FR | 4K"}]}"""
                "get_all_channels" -> """{"js":{"data":[
                    {"id":"1","name":"FR | TF1 HD","tv_genre_id":"1"},
                    {"id":"2","name":"[FR] TF1 (Full HD)","tv_genre_id":"1"},
                    {"id":"3","name":"FR - TF1 4K","tv_genre_id":"2"},
                    {"id":"4","name":"BE | TF1 HD"},
                    {"id":"5","name":"FR | TF1 +1 HD"},
                    {"id":"6","name":"Local TV"}
                ]}}"""
                "get_epg_info" -> """{"js":{"data":{"1":[
                    {"name":"Journal","start_timestamp":"1000","stop_timestamp":2000},
                    {"name":"Invalid","start_timestamp":"2000","stop_timestamp":1000}
                ]}}}"""
                else -> error("Unexpected action")
            })
        }
        HttpClient(engine).use { client ->
            val portal = LisyPortal(server, client)
            val cache = LiveTvLineups(storage = null)
            val channels = cache.get(server, 1, portal::liveChannels)
            assertEquals(channels, cache.get(server, 1, portal::liveChannels))
            assertEquals(1, actions.count { it == "get_all_channels" })
            assertEquals(listOf("1", "4", "5", "6"), channels.map { it.id })
            val tf1 = channels.first()
            assertEquals("TF1", tf1.name)
            assertEquals("France", tf1.country)
            assertEquals(listOf("3", "2", "1"), tf1.variants.map { it.entry.id })
            assertEquals(setOf("Généralistes"), tf1.categories)
            val guide = LiveTvCache.guide(server, portal)
            assertEquals(listOf(LiveTvProgram("Journal", 1000, 2000)), guide["1"])
            assertEquals(guide, LiveTvCache.guide(server, portal))
            assertEquals(1, actions.count { it == "get_epg_info" })
        }
    }
}
