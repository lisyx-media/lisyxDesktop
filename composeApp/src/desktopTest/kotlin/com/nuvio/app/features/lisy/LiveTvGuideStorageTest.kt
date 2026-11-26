package com.nuvio.app.features.lisy

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class LiveTvGuideStorageTest {
    @Test
    fun restoresMultidayGuideAcrossRestartsWithoutExposingServerCredentialsInFilenames() = runTest {
        val directory = Files.createTempDirectory("lisyx-guide-test")
        try {
            val storage = DesktopGuideStorage { directory }
            val server = LisyServer("Fixture", "https://iptv.example/c", "00:11:22:33:44:55")
            val programs = listOf(
                LiveTvProgram("Programme du 24", 1_000_000, 1_001_000),
                LiveTvProgram("Programme du 25", 1_086_400, 1_087_400),
            )
            val index = LiveTvGuideIndex(storage, backgroundScope) { 1_090_000 }
            index.merge(server, 1, mapOf("station" to programs), listOf("2026-11-24", "2026-11-25"))
            index.flush(server, 1)
            val restored = LiveTvGuideIndex(storage, backgroundScope) { 1_090_100 }
            restored.restore(server, 1)
            assertEquals(programs, restored.state(server, 1).value.programs["station"])
            assertEquals(listOf("2026-11-24", "2026-11-25"), restored.state(server, 1).value.days)
            assertNull(storage.read(server, 2))
            assertNull(storage.read(server.copy(mac = "00:11:22:33:44:FF"), 1))
            Files.list(directory).use { paths ->
                paths.forEach { file ->
                    assertFalse(file.fileName.toString().contains("iptv.example"))
                    assertFalse(file.fileName.toString().contains("00:11"))
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
