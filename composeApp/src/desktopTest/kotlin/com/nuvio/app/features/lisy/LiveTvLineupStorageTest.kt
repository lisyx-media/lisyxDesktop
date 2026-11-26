package com.nuvio.app.features.lisy

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveTvLineupStorageTest {
    @Test
    fun reclassifiesOlderCachesImmediatelyAndStillDisplaysThemWhenOffline() = runTest {
        val directory = Files.createTempDirectory("lisyx-old-lineup")
        try {
            val storage = DesktopLineupStorage { directory }
            val server = LisyServer("Fixture", "https://iptv.example/c", "00:11:22:33:44:55")
            val variants = listOf("LCI HD", "LCI").mapIndexed { i, name ->
                LiveTvVariant(LisyEntry(i.toString(), name, null, emptyList()), null, null, "France")
            }
            val legacy = variants.flatMap { groupLiveTvChannels(listOf(it)) }
            storage.write(server, 1, LiveTvLineupSnapshot(100_000L, legacy))
            var refreshes = 0
            val cache = LiveTvLineups(storage) { 100_001L }
            val offline = cache.get(server, 1) { refreshes++; error("Offline") }
            assertEquals(2, offline.single().variants.size)
            assertEquals(setOf("Actualités"), offline.single().categories)
            cache.get(server, 1) { refreshes++; groupLiveTvChannels(variants) }
            LiveTvLineups(storage) { 100_002L }.get(server, 1) { error("Unexpected refresh") }
            assertEquals(2, refreshes)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun restoresTheEntireLineupAcrossRestartsAndKeepsItWhenRefreshFails() = runTest {
        val directory = Files.createTempDirectory("lisyx-lineup-test")
        try {
            val storage = DesktopLineupStorage { directory }
            val server = LisyServer("Fixture", "https://iptv.example/c", "00:11:22:33:44:55")
            val lineup = groupLiveTvChannels(listOf("TF1 HEVC", "TF1 HD", "Local TV").mapIndexed { i, title ->
                LiveTvVariant(LisyEntry(i.toString(), title, "play-$i", emptyList()), null, null, "France", i == 1)
            })
            var now = 1000L
            var loads = 0
            fun restart() = LiveTvLineups(storage) { now }
            restart().get(server, 1) { loads++; lineup }
            now += 6 * 3600 - 1
            assertEquals(lineup, restart().get(server, 1) { loads++; emptyList() })
            assertEquals(1, loads)
            assertNull(storage.read(server, 2))
            assertNull(storage.read(server.copy(mac = "00:11:22:33:44:FF"), 1))

            now++
            val cache = restart()
            assertEquals(lineup, cache.get(server, 1) { loads++; error("Portal offline") })
            val changed = lineup.drop(1)
            assertEquals(changed, cache.get(server, 1) { loads++; changed })
            assertEquals(changed, restart().get(server, 1) { loads++; emptyList() })
            assertEquals(3, loads)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
