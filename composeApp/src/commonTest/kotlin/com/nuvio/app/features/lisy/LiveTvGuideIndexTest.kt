package com.nuvio.app.features.lisy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

class LiveTvGuideIndexTest {
    private val server = LisyServer("Fixture", "https://iptv.example/c", "00:11:22:33:44:55")
    private val previousDay = 1_000_000L
    private val now = previousDay + 2 * 86_400

    private class Storage : LiveTvGuideStorage {
        val snapshots = mutableMapOf<Pair<Int, LisyServer>, LiveTvGuideSnapshot>()
        override suspend fun read(server: LisyServer, profile: Int) = snapshots[profile to server]
        override suspend fun write(server: LisyServer, profile: Int, snapshot: LiveTvGuideSnapshot) {
            snapshots[profile to server] = snapshot
        }
    }

    @Test
    fun keyingGuideDatesUsesLocalMidnightsForBothClockChanges() {
        val boundaries = mapOf(
            "2026-03-29" to "2026-03-28T23:00:00Z", "2026-03-30" to "2026-03-29T22:00:00Z",
            "2026-03-31" to "2026-03-30T22:00:00Z", "2026-10-25" to "2026-10-24T22:00:00Z",
            "2026-10-26" to "2026-10-25T23:00:00Z", "2026-10-27" to "2026-10-26T23:00:00Z",
        ).mapValues { Instant.parse(it.value).toEpochMilliseconds() }
        val days = listOf("2026-03-29", "2026-03-30", "2026-10-25", "2026-10-26")
        val updates = (1..150).associate { station ->
            station.toString() to days.flatMap { day ->
                val start = boundaries.getValue(day) / 1000
                val end = boundaries.values.first { it > start * 1000 } / 1000
                listOf(start, start + station, end - 1).map { LiveTvProgram("Programme", it, it + 3600) }
            }
        }
        var conversions = 0
        val covered = coveredGuideDays(updates, dateAt = { epoch ->
            conversions++
            boundaries.entries.last { it.value <= epoch }.key
        }, localMidnight = boundaries::get)
        assertEquals(days.toSet(), covered)
        assertEquals(4, conversions)
    }

    @Test
    fun recreatedScreenImmediatelyFindsThePreviouslyVisitedGuideDaysAfterLivePlayback() = runTest {
        val storage = Storage()
        val index = LiveTvGuideIndex(storage, backgroundScope) { now }
        val day24 = LiveTvProgram("Retour programme 24", previousDay, previousDay + 1800)
        val day25 = LiveTvProgram("Retour programme 25", previousDay + 86_400, previousDay + 88_200)
        val current = LiveTvProgram("En direct", now, now + 1800)
        val variant = LiveTvVariant(LisyEntry("station", "TF1", null, emptyList()), null, null, "France", true)
        val channel = LiveTvChannel("tf1", "TF1", null, emptySet(), "France", listOf(variant))

        val firstScreen = index.state(server, 1)
        index.merge(server, 1, mapOf("station" to listOf(day24)))
        index.merge(server, 1, mapOf("station" to listOf(day25)))
        assertEquals(2, findTvBroadcasts(listOf(channel), firstScreen.value.programs, tvSearchTerm("Retour programme"), now, false).programmes.size)

        // A new composition subscribes to the same index after the player route is popped.
        val recreatedScreen = index.state(server, 1)
        assertEquals(listOf(day24, day25), recreatedScreen.value.programs.getValue("station"))
        index.merge(server, 1, mapOf("station" to listOf(current)))
        assertEquals(listOf(day24, day25, current), recreatedScreen.value.programs.getValue("station"))
        assertEquals(2, findTvBroadcasts(listOf(channel), recreatedScreen.value.programs, tvSearchTerm("Retour programme"), now, false).programmes.size)

        index.flush(server, 1)
        val restartedApp = LiveTvGuideIndex(storage, backgroundScope) { now + 1 }
        restartedApp.restore(server, 1)
        assertEquals(recreatedScreen.value.programs, restartedApp.state(server, 1).value.programs)
        assertTrue(restartedApp.state(server, 2).value.programs.isEmpty())
        assertTrue(restartedApp.state(server.copy(mac = "00:11:22:33:44:66"), 1).value.programs.isEmpty())
    }

    @Test
    fun restorationAndConcurrentWindowLoadsNeverOverwriteOneAnother() = runTest {
        val diskStarted = CompletableDeferred<Unit>()
        val allowDiskRead = CompletableDeferred<Unit>()
        val old = LiveTvProgram("Disk programme", previousDay, previousDay + 1800)
        val fromNetwork = LiveTvProgram("Network programme", now, now + 1800)
        val storage = object : LiveTvGuideStorage {
            override suspend fun read(server: LisyServer, profile: Int): LiveTvGuideSnapshot {
                diskStarted.complete(Unit)
                allowDiskRead.await()
                return LiveTvGuideSnapshot(now - 1, mapOf("old" to listOf(old)))
            }
            override suspend fun write(server: LisyServer, profile: Int, snapshot: LiveTvGuideSnapshot) {}
        }
        val index = LiveTvGuideIndex(storage, backgroundScope) { now }
        val restoration = async { index.restore(server, 1) }
        diskStarted.await()
        val update = async { index.merge(server, 1, mapOf("new" to listOf(fromNetwork))) }
        allowDiskRead.complete(Unit)
        restoration.await()
        update.await()
        assertEquals(mapOf("old" to listOf(old), "new" to listOf(fromNetwork)), index.state(server, 1).value.programs)
    }
}
