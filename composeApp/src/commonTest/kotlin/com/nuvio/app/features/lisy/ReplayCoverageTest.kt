package com.nuvio.app.features.lisy

import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlin.test.*

class ReplayCoverageTest {
    private fun station(vararg durations: Int?) = LiveTvChannel("test", "Test", null, emptySet(), null,
        durations.mapIndexed { i, hours -> LiveTvVariant(LisyEntry(i.toString(), "Source", null, emptyList()),
            null, null, null, replayAvailable = true, archiveHours = hours) })

    @Test fun honorsLongestRetentionAndIncludesThePartialLocalDayAtTheBoundary() {
        val now = checkNotNull(CurrentDateProvider.localStartOfDayEpochMs("2026-11-26")) / 1000 + 12 * 3600
        val channel = station(24, 48)
        assertFalse(channel.replayCanCoverDate("2026-11-23", now))
        assertTrue(channel.replayCanCoverDate("2026-11-24", now))
        assertEquals("2026-11-24", channel.earliestReplayDate(now))
        assertTrue(station(24, null).replayCanCoverDate("2026-11-20", now))
    }
}
