package com.nuvio.app.features.lisy

import com.nuvio.app.core.network.API_RESPONSE_MAX_BYTES
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeLiveLineupTest {
    @Test fun loadsBulkChannelResponseAboveTheGeneralApiLimit() = runTest {
        val station = """{"id":1,"name":"Station","tv_genre_id":"private"}"""
        val body = """{"js":{"extra":"${" ".repeat(API_RESPONSE_MAX_BYTES)}","data":[$station]}}"""
        verifyLineup(body)
    }

    @Test fun alsoLoadsPortalsWithTheArrayDirectlyUnderJs() = runTest {
        verifyLineup("""{"js":[{"id":1,"name":"Station","tv_genre_id":"private"}]}""")
    }

    private suspend fun verifyLineup(body: String) {
        val engine = MockEngine { request ->
            respond(when (request.url.parameters["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_genres" -> """{"js":[{"id":"private","censored":1}]}"""
                "get_all_channels" -> body
                else -> error("Unexpected action")
            })
        }
        HttpClient(engine).use { client ->
            val station = LisyPortal(LisyServer("Test", "https://portal.example/c", "00:11:22:33:44:55"), client).liveChannels().single()
            assertEquals("1", station.id)
            assertTrue(station.requiresParentalPin)
        }
    }
}
