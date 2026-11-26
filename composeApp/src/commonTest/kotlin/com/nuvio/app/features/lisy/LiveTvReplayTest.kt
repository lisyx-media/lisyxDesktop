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

class LiveTvReplayTest {
    @Test
    fun protocolLocaleKeepsAnExactCanteloupArchiveAvailable() = runTest {
        val pages = mutableListOf<Int>()
        val engine = MockEngine { request ->
            val query = request.url.parameters
            val expectedLocale = if (query["action"] == "get_simple_data_table") "en" else "fr"
            assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("stb_lang=$expectedLocale;"))
            respond(when (query["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_simple_data_table" -> {
                    val page = checkNotNull(query["p"]).toInt()
                    pages += page
                    val marked = if (request.headers[HttpHeaders.Cookie].orEmpty().contains("stb_lang=en;")) 1 else 0
                    when (page) {
                        1, 2 -> {
                            val rows = (1..10).joinToString(",") { i ->
                                """{"id":"${page}_$i","name":"Earlier programme","mark_archive":1,"start_timestamp":${1795540000 + page * 100 + i}}"""
                            }
                            """{"js":{"max_page_items":10,"total_items":28,"data":[$rows]}}"""
                        }
                        3 -> """{"js":{"max_page_items":10,"total_items":28,"data":[
                            {"id":"canteloup_archive","name":"C'est Canteloup","mark_archive":$marked,
                             "start_timestamp":1795550700,"time":"2026-11-24 21:05:00","t_time":"21:05","t_time_to":"21:10"}]}}"""
                        else -> error("Unexpected archive page")
                    }
                }
                "create_link" -> {
                    assertEquals("auto /media/canteloup_archive.ts", query["cmd"])
                    """{"js":{"cmd":"ffmpeg https://media.example/canteloup.ts"}}"""
                }
                else -> error("Unexpected portal action")
            })
        }
        HttpClient(engine).use { client ->
            val portal = LisyPortal(LisyServer("Fixture", "https://portal.example/c", "00:11:22:33:44:55"), client)
            val variant = LiveTvVariant(LisyEntry("tf1_fhd", "FR - TF1 FHD", null, emptyList()), null, null, "France", true)
            val channel = LiveTvChannel("tf1", "TF1", null, emptySet(), "France", listOf(variant))
            val epg = LiveTvProgram("C'est Canteloup", 1795550700, 1795551000, portalStartTime = "2026-11-24 21:05:00")
            val archive = kotlin.test.assertNotNull(portal.guideReplay(channel, epg))
            assertEquals("canteloup_archive", archive.id)
            assertEquals("https://media.example/canteloup.ts", portal.replayStream(archive))
            assertEquals(listOf(1, 2, 3), pages)
        }
    }

    @Test
    fun resolvesOnlyAnArchivedProgrammeOnTheReplayCapableVariant() = runTest {
        val pages = mutableListOf<String>()
        val engine = MockEngine { request ->
            val query = request.url.parameters
            respond(when (query["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_all_channels" -> """{"js":[{"id":"21","name":"FR | TF1 HD","enable_tv_archive":"0"},
                    {"id":"22","name":"FR | TF1 FHD","enable_tv_archive":"1"}]}"""
                "get_genres" -> """{"js":[]}"""
                "get_week" -> """{"js":[{"f_mysql":"2025-08-10","f_human":"10 août"},
                    {"f_mysql":"2025-08-11","today":"1"},{"f_mysql":"2025-08-12"}]}"""
                "get_simple_data_table" -> {
                    assertEquals("22", query["ch_id"])
                    assertEquals("2025-08-10", query["date"])
                    pages += query["p"].orEmpty()
                    when (query["p"]) {
                        "1" -> """{"js":{"max_page_items":"1","total_items":2,"data":[
                            {"id":"1","name":"Unavailable","mark_archive":"0"}]}}"""
                        "2" -> """{"js":{"max_page_items":"1","total_items":2,"data":[
                            {"id":"801","name":"Journal","t_time":"20:00","descr":"Édition","category":"Information","image_url":"https://art.example/programme.jpg","mark_archive":"1"}]}}"""
                        else -> error("Unexpected archive page")
                    }
                }
                "create_link" -> {
                    assertEquals("tv_archive", query["type"])
                    assertEquals("auto /media/801.ts", query["cmd"])
                    """{"js":{"cmd":"ffmpeg https://media.example/archive.ts"}}"""
                }
                else -> error("Unexpected portal action")
            })
        }
        HttpClient(engine).use { client ->
            val portal = LisyPortal(LisyServer("Fixture", "https://portal.example/c", "00:11:22:33:44:55"), client)
            val variants = portal.liveChannels().flatMap { it.variants }
            assertFalse(variants.single { it.entry.id == "21" }.replayAvailable)
            assertTrue(variants.single { it.entry.id == "22" }.replayAvailable)
            val channel = LiveTvChannel("fixture", "TF1", null, emptySet(), "France", variants)
            assertEquals(listOf("2025-08-11", "2025-08-10"), portal.replayDays())
            val program = portal.replayPrograms(channel, "2025-08-10").single()
            assertEquals("Journal", program.title)
            assertEquals("20:00", program.startLabel)
            assertEquals("Information", program.category)
            assertEquals("https://art.example/programme.jpg", program.imageUrl)
            assertEquals("https://media.example/archive.ts", portal.replayStream(program))
            assertEquals(listOf("1", "2"), pages)
        }
    }
}
