package com.nuvio.app.features.lisy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LiveTvGuideCoverageTest {
    @Test
    fun coveredViewportReadsNoPagesAndRefreshUsesVisibleDayBeforeYesterday() = runTest {
        val date = "2030-01-03"
        val midnight = checkNotNull(com.nuvio.app.features.watchprogress.CurrentDateProvider.localStartOfDayEpochMs(date)) / 1000
        val start = midnight + 1800
        val end = midnight + 7200
        val requests = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            val query = request.url.parameters
            respond(when (query["action"]) {
                "handshake" -> """{"js":{"token":"test"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_simple_data_table" -> {
                    requests += checkNotNull(query["ch_id"]) to checkNotNull(query["date"])
                    """{"js":[{"id":"schedule","name":"Schedule","start_timestamp":$midnight,"stop_timestamp":$end}]}"""
                }
                else -> error("Unexpected request")
            })
        }
        HttpClient(engine).use { http ->
            val portal = LisyPortal(LisyServer("Test", "https://portal.example/c/", "00:11:22:33:44:55"), http)
            fun variant(id: String, archives: Boolean) =
                LiveTvVariant(LisyEntry(id, id, null, emptyList()), null, null, "France", archives)
            val station = LiveTvChannel("tf1", "TF1", null, emptySet(), "France",
                listOf(variant("primary", true), variant("secondary", false)))
            val cached = mapOf("primary" to listOf(LiveTvProgram("First", start, start + 1200)),
                "secondary" to listOf(LiveTvProgram("Following", start + 1200, end)))
            kotlin.test.assertTrue(LiveTvCache.guideWindow(portal, start, end, listOf(station), knownGuide = cached,
                onLoading = { error("Covered rows must remain visible without a spinner") }) { _, _ -> error("Already indexed") })
            assertEquals(emptyList(), requests)

            // A different screen fetched this variant. Its valid table wins over a preferred,
            // uncached variant, and an entirely covered overnight interval never requests yesterday.
            portal.guideForDay(station.copy(variants = station.variants.takeLast(1)), date)
            repeat(2) { attempt ->
                var delivered = emptyList<LiveTvProgram>()
                kotlin.test.assertTrue(LiveTvCache.guideWindow(portal, start, end, listOf(station), knownGuide = cached,
                    refreshKnown = true, forceRefresh = attempt == 1) { _, entries -> delivered = entries })
                assertEquals("Schedule", delivered.single().name)
            }
            assertEquals(listOf("secondary" to date, "primary" to date), requests)
        }
    }

    @Test
    fun laterVariantFillsTheRequestedGapInsteadOfStoppingAtANonemptyDay() = runTest {
        val day = "2030-01-03"
        val midnight = checkNotNull(com.nuvio.app.features.watchprogress.CurrentDateProvider.localStartOfDayEpochMs(day)) / 1000
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            val query = request.url.parameters
            respond(when (query["action"]) {
                "handshake" -> """{"js":{"token":"test"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_simple_data_table" -> {
                    val id = query["ch_id"].orEmpty()
                    requested += id
                    val start = if (id == "partial") midnight + 10 * 3600 else midnight
                    val end = if (id == "partial") midnight + 11 * 3600 else midnight + 2 * 3600
                    """{"js":{"total_items":1,"max_page_items":10,"data":[
                        {"id":"$id","name":"$id","start_timestamp":$start,"stop_timestamp":$end,"mark_archive":0}]}}"""
                }
                else -> error("Unexpected request")
            })
        }
        HttpClient(engine).use { http ->
            val portal = LisyPortal(LisyServer("Test", "https://portal.example/c/", "00:11:22:33:44:55"), http)
            fun variant(id: String) = LiveTvVariant(LisyEntry(id, id, null, emptyList()), null, null, "France")
            val channel = LiveTvChannel("station", "Station", null, emptySet(), "France",
                listOf(variant("partial"), variant("full"), variant("unused")))
            val programmes = portal.guideForDay(channel, day, windowStart = midnight, windowEnd = midnight + 2 * 3600)
            assertEquals(listOf("partial", "full"), requested)
            kotlin.test.assertTrue(coversGuideWindow(programmes, midnight, midnight + 2 * 3600))
            assertEquals(2, programmes.size)
        }
    }

    @Test
    fun requestedDistantDaySurvivesFullBudgetAndAnIncomingCurrentSnapshot() {
        val now = 10_000L
        val requestedDay = now + 100 * 86_400L
        val displayed = LiveTvProgram("Requested programme", requestedDay, requestedDay + 1800)
        fun nearPrograms(label: String) = (1..10).map { index ->
            LiveTvProgram("$label $index", now + index * 60, now + index * 60 + 60)
        }
        val previous = linkedMapOf(
            "earlier-station" to nearPrograms("First"),
            "other-station" to nearPrograms("Other"),
            "visible-station" to listOf(displayed),
        )
        val focus = LiveTvGuideFocus(requestedDay, requestedDay + 86_400, setOf("visible-station"))
        val nextProgramme = displayed.copy(name = "Next requested programme", start = requestedDay + 1800, end = requestedDay + 3600)
        val merged = mergeLiveTvGuide(
            previous, mapOf("visible-station" to listOf(nextProgramme)), now, focus, maxPrograms = 21,
        )
        assertEquals(listOf(displayed, nextProgramme), merged.getValue("visible-station"))
        kotlin.test.assertTrue(merged.values.sumOf { it.size } <= 21)
        val refreshed = mergeLiveTvGuide(
            merged, mapOf("earlier-station" to nearPrograms("New snapshot")), now, focus, maxPrograms = 21,
        )
        assertEquals(listOf(displayed, nextProgramme), refreshed.getValue("visible-station"))
        kotlin.test.assertTrue(refreshed.values.sumOf { it.size } <= 21)
    }

    @Test
    fun onlyChangedStationsAreRebuiltAndSelectedDayWinsThePerStationBudget() {
        val now = 10_000L
        val farDay = now - 100 * 86_400L
        val displayed = LiveTvProgram("Visible older programme", farDay, farDay + 1800)
        val unchanged = listOf(LiveTvProgram("Untouched", now, now + 1000))
        val crowded = (1..750).map { index ->
            LiveTvProgram("Current $index", now + index * 60, now + index * 60 + 60)
        }
        val merged = mergeLiveTvGuide(
            mapOf("visible" to crowded, "untouched" to unchanged),
            mapOf("visible" to listOf(displayed)),
            now,
            LiveTvGuideFocus(farDay, farDay + 86_400, setOf("visible")),
        )
        kotlin.test.assertSame(unchanged, merged["untouched"])
        kotlin.test.assertTrue(displayed in merged.getValue("visible"))
        assertEquals(750, merged.getValue("visible").size)
    }

    @Test
    fun partialRefreshPreservesKnownProgramsAndReplacesUpdatedBroadcasts() {
        val now = 10_000L
        val first = LiveTvProgram("Earlier", now - 1000, now - 500)
        val current = LiveTvProgram("Old title", now - 500, now + 500)
        val future = LiveTvProgram("Later", now + 500, now + 1000)
        val previous = mapOf("station" to listOf(first, current, future))
        assertEquals(previous, mergeLiveTvGuide(previous, mapOf("station" to emptyList()), now))
        val merged = mergeLiveTvGuide(previous, mapOf("station" to listOf(current.copy(name = "Corrected title"))), now)
        assertEquals(listOf("Earlier", "Corrected title", "Later"), merged.getValue("station").map { it.name })
        val older = LiveTvProgram("Older valid guide day", now - 20 * 86_400L, now - 19 * 86_400L)
        assertEquals(4, mergeLiveTvGuide(merged, mapOf("station" to listOf(older)), now).getValue("station").size)
    }

    @Test
    fun datedGuideLoadsFutureUnarchivedProgramsAndSharesTheBoundedDayCache() = runTest {
        var pages = 0
        val engine = MockEngine { request ->
            val query = request.url.parameters
            respond(when (query["action"]) {
                "handshake" -> """{"js":{"token":"test"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_week" -> """{"js":[{"f_mysql":"2030-01-01"},{"f_mysql":"2030-01-02","today":"1"},{"f_mysql":"2030-01-03"}]}"""
                "get_simple_data_table" -> {
                    assertEquals("channel", query["ch_id"])
                    assertEquals("2030-01-03", query["date"])
                    pages++
                    if (query["p"] == "1") {
                        """{"js":{"total_items":3,"max_page_items":2,"data":[
                            {"id":"1","name":"Programme A","start_timestamp":1893628800,"stop_timestamp":1893632400,"mark_archive":0},
                            {"id":"2","name":"Programme B","start_timestamp":1893632400,"stop_timestamp":1893636000,"mark_archive":0}]}}"""
                    } else {
                        """{"js":{"total_items":3,"max_page_items":2,"data":[
                            {"id":"3","name":"Programme C","start_timestamp":1893636000,"stop_timestamp":1893639600,"mark_archive":0}]}}"""
                    }
                }
                else -> error("Unexpected request")
            })
        }
        HttpClient(engine).use { http ->
            val portal = LisyPortal(LisyServer("Test", "https://portal.example/c/", "00:11:22:33:44:55"), http)
            val variant = LiveTvVariant(LisyEntry("channel", "Station", null, emptyList()), null, null, "France", true)
            val channel = LiveTvChannel("station", "Station", null, emptySet(), "France", listOf(variant))
            assertEquals(listOf("2030-01-01", "2030-01-02", "2030-01-03"), portal.guideDays())
            val first = portal.guideForDay(channel, "2030-01-03")
            assertEquals(listOf("Programme A", "Programme B", "Programme C"), first.map { it.name })
            assertEquals(2, pages)
            assertEquals(first, portal.guideForDay(channel, "2030-01-03"))
            assertEquals(emptyList(), portal.replayPrograms(channel, "2030-01-03"))
            assertEquals(2, pages)
            assertEquals(first, portal.guideForDay(channel, "2030-01-03", forceRefresh = true))
            assertEquals(4, pages)
        }
    }

    @Test
    fun scheduleUsesVariantWithKnownEpgBeforeHigherQualityEmptyFeed() = runTest {
        val requestedIds = mutableListOf<String>()
        val engine = MockEngine { request ->
            val query = request.url.parameters
            respond(when (query["action"]) {
                "handshake" -> """{"js":{"token":"test"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_epg_info" -> """{"js":{"fhd":[
                    {"name":"Now","start_timestamp":1000,"stop_timestamp":2000}]}}"""
                "get_simple_data_table" -> {
                    requestedIds += query["ch_id"].orEmpty()
                    """{"js":{"total_items":1,"max_page_items":10,"data":[
                        {"id":"1","name":"Actual schedule","start_timestamp":1000,"stop_timestamp":2000,"mark_archive":0}]}}"""
                }
                else -> error("Unexpected request")
            })
        }
        HttpClient(engine).use { http ->
            val portal = LisyPortal(LisyServer("Test", "https://portal.example/c/", "00:11:22:33:44:55"), http)
            portal.liveGuide()
            fun variant(id: String) = LiveTvVariant(LisyEntry(id, id, null, emptyList()), null, null, "France")
            val channel = LiveTvChannel("station", "Station", null, emptySet(), "France", listOf(variant("uhd"), variant("fhd")))
            assertFalse(channel.variants.any { it.replayAvailable })
            assertEquals("Actual schedule", portal.guideForDay(channel, "2030-01-03").single().name)
            assertEquals(listOf("fhd"), requestedIds)
        }
    }
}
