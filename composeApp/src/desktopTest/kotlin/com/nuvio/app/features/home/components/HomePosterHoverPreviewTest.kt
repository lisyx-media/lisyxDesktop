package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_view_details
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomePosterHoverPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun posterHoverOpensNuvioPreviewAndDetailsActionWorks() {
        var clicked = 0
        val detailsLabel = runBlocking { getString(Res.string.home_view_details) }
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(900.dp, 700.dp).padding(180.dp)) {
                    HomePosterCard(
                        item = MetaPreview(id = "hover-test", type = "movie", name = "Hover preview test", description = "Preview summary"),
                        modifier = Modifier.testTag("poster"),
                        forcePoster = true,
                        onClick = { clicked++ },
                    )
                }
            }
        }
        val bounds = compose.onNodeWithTag("poster").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height > bounds.width * 1.4f, "Card must use vertical poster proportions")
        compose.onNodeWithText(detailsLabel).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("poster").performMouseInput { enter(center) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText(detailsLabel).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText(detailsLabel).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }
}
