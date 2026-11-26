package com.nuvio.app.features.lisy

import com.nuvio.app.features.player.LiveTvPlaybackWatchdog
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LiveTvRecoveryTest {
    private val station = LiveTvChannel("station", "Station", null, emptySet(), null,
        listOf("uhd", "hd", "sd").map { id ->
            LiveTvVariant(LisyEntry(id, "Station ${id.uppercase()}", null, emptyList()), null, null, null,
                language = if (id == "uhd") "pl" else null)
        })

    @Test
    fun failedLinksAndUnplayableStreamsAreEachTriedOnceInAutomaticMode() = runTest {
        val requests = mutableListOf<String>()
        val resolve: suspend (LisyEntry) -> String? = {
            requests += it.id
            if (it.id == "uhd") error("missing link")
            "https://example.invalid/${it.id}"
        }
        val first = assertNotNull(nextTvStream(station, null, emptySet(), resolve))
        assertEquals(setOf("uhd", "hd"), first.attemptedIds)
        val second = assertNotNull(nextTvStream(station, null, first.attemptedIds, resolve))
        assertEquals("https://example.invalid/sd", second.url)
        assertNull(nextTvStream(station, null, second.attemptedIds, resolve))
        assertEquals(listOf("uhd", "hd", "sd"), requests)
    }

    @Test
    fun manualSelectionRemainsPinnedOnErrorAndCanBeRetried() = runTest {
        val requests = mutableListOf<String>()
        assertNull(nextTvStream(station, "hd", emptySet()) { requests += it.id; null })
        val retry = nextTvStream(station, "hd", emptySet()) { requests += it.id; "https://example.invalid/hd" }
        assertNotNull(retry)
        assertEquals(listOf("hd", "hd"), requests)
        assertFailsWith<CancellationException> { nextTvStream(station, null, emptySet()) { throw CancellationException() } }
        assertEquals("UHD · PL", station.sourceLabels()["uhd"])
    }

    @Test
    fun movingAudioClockCannotConcealMissingOrFrozenVideo() {
        val watch = LiveTvPlaybackWatchdog()
        fun poll(t: Long, count: Long, play: Boolean = true) =
            watch.failed(PlayerPlaybackSnapshot(positionMs = t, liveVideoFrames = count), play, t)
        assertFalse(poll(0, 0))
        assertTrue(poll(15_000, 0))
        assertFalse(poll(16_000, 1))
        assertFalse(poll(20_000, 11))
        assertFalse(poll(29_999, 11))
        assertTrue(poll(30_000, 11))
        assertFalse(poll(90_000, 11, false))
        assertFalse(poll(120_000, 11))
        assertFalse(poll(140_000, -2))
        assertFalse(poll(250_000, 11))
        assertTrue(poll(251_000, -1))
    }
}
