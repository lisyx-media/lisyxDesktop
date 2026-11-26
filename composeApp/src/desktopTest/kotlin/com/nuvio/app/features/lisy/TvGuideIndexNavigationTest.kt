package com.nuvio.app.features.lisy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.lisy_tv_search
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test

class TvGuideIndexNavigationTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun searchFindsBothVisitedDaysImmediatelyAfterReturningFromThePlayer() {
        val server = LisyServer("Fixture", "https://iptv.example/c", "00:11:22:33:44:55")
        val index = LiveTvGuideIndex(null)
        val midnight = checkNotNull(CurrentDateProvider.localStartOfDayEpochMs("2026-11-26")) / 1000
        val now = midnight + 12 * 3600
        val programmes = listOf(2, 1).map { daysAgo ->
            val start = midnight - daysAgo * 86_400 + 21 * 3600
            LiveTvProgram("C'est Canteloup", start, start + 300)
        }
        val variant = LiveTvVariant(LisyEntry("station", "TF1", null, emptyList()), null, null, "France", true)
        val channel = LiveTvChannel("tf1", "TF1", null, emptySet(), "France", listOf(variant))
        runBlocking { index.merge(server, 1, mapOf("station" to programmes)) }
        var showTv by mutableStateOf(true)
        ui.setContent {
            NuvioTheme {
                val saved = rememberSaveableStateHolder()
                Box(Modifier.size(1100.dp, 740.dp)) {
                    if (showTv) {
                        saved.SaveableStateProvider("tv") {
                            val snapshot by remember { index.state(server, 1) }.collectAsState()
                            TvBrowser(
                                channels = listOf(channel), guide = snapshot.programs, now = now,
                                favorites = emptySet(), playingId = null, topPadding = 0.dp,
                                loadFailed = false, playbackFailed = false,
                                onRetry = {}, onFavorite = {}, onLive = { showTv = false }, onArchive = {},
                            )
                        }
                    } else {
                        Text("Player route")
                    }
                }
            }
        }
        val search = runBlocking { getString(Res.string.lisy_tv_search) }
        ui.onNodeWithContentDescription(search).performTextInput("Canteloup")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("C'est Canteloup").fetchSemanticsNodes().size == 2 }
        ui.onNodeWithContentDescription("TF1,", substring = true).performClick()
        ui.onNodeWithText("Player route").assertIsDisplayed()

        // The current snapshot arrives while the TV composition is disposed.
        runBlocking {
            index.merge(server, 1, mapOf("station" to listOf(LiveTvProgram("Current news", now, now + 1800))))
        }
        ui.runOnIdle { showTv = true }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("C'est Canteloup").fetchSemanticsNodes().size == 2 }
        ui.onNodeWithContentDescription(search).assertTextContains("Canteloup")
        ui.onNodeWithContentDescription("TF1,", substring = true).assertExists()
    }
}
