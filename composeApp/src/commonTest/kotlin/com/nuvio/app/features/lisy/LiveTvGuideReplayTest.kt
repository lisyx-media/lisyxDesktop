package com.nuvio.app.features.lisy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class LiveTvGuideReplayTest {
    private fun variant(id: String) = LiveTvVariant(LisyEntry(id, "Journal", null, emptyList()), null, null, "France", replayAvailable = true)
    private val channel = LiveTvChannel("c", "TV", null, emptySet(), "France", listOf(variant("a"), variant("b")))
    private val program = LiveTvProgram("Journal", 2000, 2300, portalStartTime = "2025-08-10 20:00:00")

    @Test
    fun theExactAiringOnASecondVariantIsResolvedAndArchivePagesAreReused() = runTest {
        val archiveRequests = mutableListOf<String>()
        val engine = MockEngine { request ->
            val query = request.url.parameters
            respond(when (query["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_simple_data_table" -> {
                    assertEquals("2025-08-10", query["date"])
                    archiveRequests += query["ch_id"].orEmpty()
                    if (query["ch_id"] == "a") """{"js":[{"id":"wrong","name":"Journal","start_timestamp":1000,"mark_archive":1}]}"""
                    else """{"js":[{"id":"other","name":"Magazine","start_timestamp":2000,"mark_archive":1},
                        {"id":"unavailable","name":"Journal","start_timestamp":2000,"mark_archive":0},
                        {"id":"correct","name":"JOURNAL","start_timestamp":2000,"mark_archive":1}]}"""
                }
                "create_link" -> {
                    assertEquals("auto /media/correct.ts", query["cmd"])
                    """{"js":{"cmd":"ffmpeg https://media.example/exact.ts"}}"""
                }
                else -> error("Unexpected action")
            })
        }
        HttpClient(engine).use { client ->
            val portal = LisyPortal(LisyServer("Fixture", "https://example.test/c", "00:11:22:33:44:55"), client)
            val archived = checkNotNull(portal.guideReplay(channel, program))
            assertEquals("https://media.example/exact.ts", portal.replayStream(archived))
            assertNull(portal.guideReplay(channel, program.copy(start = 3000)))
            assertEquals(listOf("a", "b"), archiveRequests)
        }
    }

    @Test
    fun leavingTheProgrammeCancelsItsLookupWithoutCachingAPartialDay() = runTest {
        val started = CompletableDeferred<Unit>()
        var reads = 0
        val engine = MockEngine { request ->
            when (request.url.parameters["action"]) {
                "handshake" -> respond("""{"js":{"token":"fixture"}}""")
                "get_profile" -> respond("""{"js":{}}""")
                else -> {
                    reads++
                    if (reads == 1) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    respond("""{"js":[{"id":"exact","name":"Journal","start_timestamp":2000,"mark_archive":1}]}""")
                }
            }
        }
        HttpClient(engine).use { client ->
            val portal = LisyPortal(LisyServer("Fixture", "https://example.test/c", "00:11:22:33:44:55"), client)
            val station = channel.copy(variants = listOf(variant("a")))
            val leaving = launch { portal.guideReplay(station, program) }
            started.await()
            leaving.cancelAndJoin()
            assertEquals("exact", portal.guideReplay(station, program)?.id)
            assertEquals(2, reads)
        }
    }

    @Test
    fun ambiguousWallTimesNeverOpenTheWrongProgrammeAndFailuresCanBeRetried() = runTest {
        var reads = 0
        val engine = MockEngine { request ->
            when (request.url.parameters["action"]) {
                "handshake" -> respond("""{"js":{"token":"fixture"}}""")
                "get_profile" -> respond("""{"js":{}}""")
                else -> {
                    reads++
                    if (reads == 1) respond("failure", HttpStatusCode.ServiceUnavailable)
                    else respond("""{"js":[{"id":"x","name":"Journal","time":"2025-08-10 20:00:00","mark_archive":1},
                        {"id":"y","name":"Journal","time":"2025-08-10 20:00:00","mark_archive":1}]}""")
                }
            }
        }
        HttpClient(engine).use { client ->
            val portal = LisyPortal(LisyServer("Fixture", "https://example.test/c", "00:11:22:33:44:55"), client)
            val station = channel.copy(variants = listOf(variant("a")))
            assertFails { portal.guideReplay(station, program) }
            assertNull(portal.guideReplay(station, program))
            assertEquals(2, reads)
        }
    }
}
