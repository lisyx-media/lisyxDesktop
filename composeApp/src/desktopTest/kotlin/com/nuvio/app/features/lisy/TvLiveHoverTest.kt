package com.nuvio.app.features.lisy

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class TvLiveHoverTest {
    @get:Rule val ui = createComposeRule()

    @Test fun briefHoverDoesNotOpenStreamAndSelectedEditionIsUsedByPreview() {
        val station = LiveTvChannel("station", "LCI", null, setOf("Actualités"), "France",
            listOf("FHD", "HD").map {
                LiveTvVariant(LisyEntry(it, "LCI $it", null, emptyList()), null, null, "France")
            })
        var selected by mutableStateOf<String?>(null)
        val attempts = mutableListOf<String?>()
        var playerLaunches = 0
        ui.setContent {
            NuvioTheme {
                TvBrowser(listOf(station), emptyMap(), 100L, emptySet(), null, null, false, false,
                    {}, {}, { playerLaunches++ }, {}, sourceRevision = if (selected == null) 0 else 1,
                    selectedSource = { selected }, onSelectSource = { _, id -> selected = id },
                    previewStream = { _, id ->
                        attempts.add(id)
                        kotlinx.coroutines.awaitCancellation()
                    })
            }
        }
        val play = runBlocking { getString(Res.string.lisy_tv_play) }
        val quality = runBlocking { getString(Res.string.tv_source_quality) }
        val card = ui.onNodeWithContentDescription("LCI, $play")
        ui.onNodeWithContentDescription(quality).performClick()
        ui.onNodeWithText("HD").performClick()
        ui.runOnIdle { assertEquals("HD", selected) }
        ui.mainClock.autoAdvance = false
        card.performMouseInput { enter(center) }
        ui.mainClock.advanceTimeBy(500)
        card.performMouseInput { exit() }
        ui.mainClock.advanceTimeBy(1200)
        ui.runOnIdle { assertEquals(emptyList<String?>(), attempts.toList()) }
        card.performMouseInput { enter(center) }
        ui.mainClock.advanceTimeBy(1250)
        ui.onNodeWithText(play).assertExists()
        ui.mainClock.autoAdvance = true
        val mute = runBlocking { getString(Res.string.tv_preview_mute) }
        val unmute = runBlocking { getString(Res.string.tv_preview_unmute) }
        ui.onNodeWithContentDescription(mute).performClick()
        ui.onNodeWithContentDescription(unmute).performClick()
        ui.onNodeWithContentDescription(mute).assertExists()
        ui.runOnIdle {
            assertEquals(listOf<String?>("HD"), attempts.toList())
            assertEquals(0, playerLaunches)
        }
    }
}
