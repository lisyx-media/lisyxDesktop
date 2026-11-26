package com.nuvio.app.features.lisy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class TvGuideDayNavigationTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun previousAndNextDayRequestVisibleStationsEvenWhenProgramsAreMissing() {
        val date = "2026-11-26"
        val midnight = checkNotNull(CurrentDateProvider.localStartOfDayEpochMs(date)) / 1000
        val now = midnight + 12 * 3600
        val variant = LiveTvVariant(LisyEntry("station", "Test Station", null, emptyList()), null, null, "France")
        val channel = LiveTvChannel("station", "Test Station", null, emptySet(), "France", listOf(variant))
        val requests = mutableListOf<Pair<Long, List<String>>>()
        var guide by mutableStateOf(mapOf("station" to emptyList<LiveTvProgram>()))
        ui.setContent {
            NuvioTheme {
                Box(Modifier.size(1000.dp, 600.dp)) {
                    TvGuide(
                        channels = listOf(channel), guide = guide, now = now, pad = 16.dp,
                        playingId = null, onLive = {}, lookupReplay = { _, _ -> null }, onReplay = { _, _ -> },
                        guideDays = listOf("2026-11-25", date, "2026-11-27"),
                        onWindowChanged = { start, _, channels -> requests.add(start to channels.map { it.id }) },
                    )
                }
            }
        }
        fun label(resource: StringResource) = runBlocking { getString(resource) }
        ui.waitForIdle()
        assertEquals(midnight + 12 * 3600, requests.last().first)
        ui.onNodeWithContentDescription(label(Res.string.tv_guide_previous_day)).performClick()
        ui.waitForIdle()
        val yesterday = checkNotNull(CurrentDateProvider.localStartOfDayEpochMs("2026-11-25")) / 1000 + 12 * 3600
        assertEquals(yesterday, requests.last().first)
        assertEquals(listOf("station"), requests.last().second)
        ui.onNodeWithContentDescription(label(Res.string.tv_guide_previous_day)).assertIsNotEnabled()
        ui.runOnIdle {
            guide = mapOf("station" to listOf(LiveTvProgram("Loaded previous-day programme", yesterday, yesterday + 3600)))
        }
        ui.onNodeWithText("Loaded previous-day programme").assertExists()
        ui.onNodeWithContentDescription(label(Res.string.tv_guide_next_day)).performClick()
        ui.onNodeWithContentDescription(label(Res.string.tv_guide_next_day)).performClick()
        ui.waitForIdle()
        val tomorrow = checkNotNull(CurrentDateProvider.localStartOfDayEpochMs("2026-11-27")) / 1000 + 12 * 3600
        assertEquals(tomorrow, requests.last().first)
        ui.onNodeWithContentDescription(label(Res.string.tv_guide_next_day)).assertIsNotEnabled()
        ui.onNodeWithText(label(Res.string.lisy_tv_now)).performClick()
        ui.waitForIdle()
        assertEquals(now, requests.last().first)
    }
}
