package com.nuvio.app.features.lisy

import com.nuvio.app.core.time.isoEpochDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TvReplaySearchIndexTest {
    private val server = LisyServer("Fixture", "https://portal.example/c", "00:11:22:33:44:55")
    private val station = channel("1")
    private val timestamp = isoEpochDay("2026-11-26") * 86_400 + 43_200
    private fun channel(id: String) = LiveTvChannel(id, "Station $id", null, emptySet(), null,
        listOf(LiveTvVariant(LisyEntry(id, "Station $id", null, emptyList()), null, null, null, replayAvailable = true)))
    private class Disk : TvReplaySearchStorage {
        val saved = mutableMapOf<String, TvReplaySearchSnapshot>()
        var writes = 0
        private fun key(server: LisyServer, profile: Int) = "$profile/${server.url}/${server.mac}"
        override suspend fun read(server: LisyServer, profile: Int) = saved[key(server, profile)]
        override suspend fun write(server: LisyServer, profile: Int, snapshot: TvReplaySearchSnapshot) { writes++; saved[key(server, profile)] = snapshot }
    }

    @Test
    fun keepsProviderConfirmedReplayOnTheOldestPartialDay() = runTest {
        val capable = station.copy(variants = station.variants.map { it.copy(archiveHours = 48) })
        val index = TvReplaySearchIndex(null, { timestamp })
        val title = LiveTvProgram("Journal", timestamp - 50 * 3600, timestamp - 49 * 3600)
        val requested = mutableListOf<String>()
        index.prepare(server, 1, listOf(capable), listOf("2026-11-24", "2026-11-23"), { _, day ->
            requested += day
            listOf(title)
        })
        assertEquals(listOf("2026-11-24"), requested)
        assertEquals(listOf(capable to title), index.search(server, 1, listOf(capable), "journal"))
    }

    @Test
    fun restartRetainsMultipleDatesAndNegativeCacheWithoutNetworkPerQuery() = runTest {
        val disk = Disk()
        var clock = timestamp
        val index = TvReplaySearchIndex(disk, { clock })
        var loads = 0
        val dates = listOf("2026-11-25", "2026-11-24", "2026-11-23")
        index.prepare(server, 1, listOf(station), dates, { _, date ->
            loads++
            if (date.endsWith("23")) emptyList() else listOf(LiveTvProgram("C’est Cantéloup", isoEpochDay(date) * 86_400 + 100, isoEpochDay(date) * 86_400 + 200))
        })
        assertEquals(3, loads)
        assertEquals(2, index.search(server, 1, listOf(station), "c est canteloup").size)
        assertEquals(2, index.search(server, 1, listOf(station), "C’EST CANTÉLOUP").size)
        assertTrue(index.search(server, 1, listOf(channel("2")), "canteloup").isEmpty())
        val recreated = TvReplaySearchIndex(disk, { clock })
        recreated.restore(server, 1)
        assertEquals(2, recreated.search(server, 1, listOf(station), "canteloup").size)
        recreated.prepare(server, 1, listOf(station), dates, { _, _ -> error("Fresh positive and empty dates must not reload") })
        assertEquals(3, recreated.state(server, 1).value.days.size)
        recreated.restore(server, 2)
        assertTrue(recreated.search(server, 2, listOf(station), "canteloup").isEmpty())
        clock += 24 * 3600 + 1
        recreated.prepare(server, 1, listOf(station), dates, { _, _ -> loads++; emptyList() })
        assertEquals(6, loads)
    }

    @Test
    fun cancellationPersistsCompletedWorkAndFailuresRemainRetryable() = runTest {
        val disk = Disk()
        val index = TvReplaySearchIndex(disk, { timestamp })
        val dates = listOf("2026-11-25", "2026-11-24", "2026-11-23")
        assertFailsWith<CancellationException> {
            index.prepare(server, 1, listOf(station), dates, { _, date ->
                if (date.endsWith("23")) throw CancellationException("leave TV")
                listOf(LiveTvProgram(date, isoEpochDay(date) * 86_400 + 100, isoEpochDay(date) * 86_400 + 200))
            })
        }
        val recreated = TvReplaySearchIndex(disk, { timestamp })
        var calls = 0
        assertFalse(recreated.prepare(server, 1, listOf(station), dates, { _, _ -> calls++; error("offline") }))
        assertEquals(1, calls)
        assertTrue(recreated.prepare(server, 1, listOf(station), dates, { _, _ -> calls++; emptyList() }))
        assertEquals(2, calls)
        assertEquals(3, recreated.state(server, 1).value.days.size)
    }

    @Test
    fun currentDatesRefreshSoonerThanHistoricalOnesAndNetworkConcurrencyIsTwo() = runTest {
        var clock = timestamp
        val index = TvReplaySearchIndex(null, { clock })
        var active = 0
        var maximum = 0
        val calls = mutableListOf<String>()
        val loader: suspend (LiveTvChannel, String) -> List<LiveTvProgram> = { _, date ->
            active++
            maximum = maxOf(maximum, active)
            calls += date
            delay(10)
            active--
            emptyList()
        }
        val dates = listOf("2026-11-25", "2026-11-24")
        index.prepare(server, 1, listOf(station, channel("2")), dates, loader)
        assertEquals(2, maximum)
        assertEquals(4, calls.size)
        calls.clear()
        clock += 901
        index.prepare(server, 1, listOf(station, channel("2")), dates, loader)
        assertEquals(listOf("2026-11-25", "2026-11-25"), calls)
    }

    @Test
    fun priorityCoversThreeRecentDaysBeforeLargeBouquetAndBoundsMemory() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val disk = Disk()
        val index = TvReplaySearchIndex(disk, { timestamp }, maxPrograms = 10)
        val stations = (1..30).map { channel(it.toString()) }
        val dates = listOf("2026-11-25", "2026-11-24", "2026-11-23", "2026-11-22")
        index.prepare(server, 1, stations, dates, { channel, date ->
            calls += channel.id to date
            listOf(LiveTvProgram("Archive", isoEpochDay(date) * 86_400 + 100, isoEpochDay(date) * 86_400 + 200))
        })
        assertEquals(dates.take(3).toSet(), calls.take(24).map { it.second }.toSet())
        assertTrue(calls.take(24).all { it.first.toInt() <= 8 })
        assertTrue(index.state(server, 1).value.days.sumOf { it.programs.size } <= 10)
        assertTrue(index.state(server, 1).value.days.all { it.date == "2026-11-25" })
        assertEquals(1, disk.writes, "Fast preparation batches must be saved once at completion")
    }
}
