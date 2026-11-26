package com.nuvio.app.features.lisy

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class TvReplaySearchStorageTest {
    @Test
    fun archiveCacheRestoresAtomicallyAndNamesContainNoProfileOrServerIdentity() = runTest {
        val directory = Files.createTempDirectory("replay-index")
        try {
            val storage = DesktopReplaySearchStorage { directory }
            val server = LisyServer("Fixture", "https://portal.example/c", "00:11:22:33:44:55")
            val snapshot = TvReplaySearchSnapshot(7, listOf(
                TvReplaySearchDay("station", "2026-11-25", 1_000,
                    listOf(LiveTvProgram("C’est Canteloup", 100, 200))),
                TvReplaySearchDay("station", "2026-11-24", 1_000, emptyList()),
            ))
            storage.write(server, 42, snapshot)
            assertEquals(snapshot, DesktopReplaySearchStorage { directory }.read(server, 42))
            assertNull(storage.read(server, 43))
            assertNull(storage.read(server.copy(mac = "00:11:22:33:44:FF"), 42))
            storage.write(server, 42, snapshot.copy(revision = 8))
            assertEquals(8L, storage.read(server, 42)?.revision)
            Files.list(directory).use { files ->
                val names = files.map { it.fileName.toString() }.toList()
                assertEquals(1, names.size)
                assertTrue(names.single().matches(Regex("[a-f0-9]{64}\\.json\\.gz")))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
