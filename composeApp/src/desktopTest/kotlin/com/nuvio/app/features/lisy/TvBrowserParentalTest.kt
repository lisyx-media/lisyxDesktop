package com.nuvio.app.features.lisy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class TvBrowserParentalTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun lockedAdultCategoryRequestsAccessWithoutStartingPlayback() {
        // The screen supplies only accessible channels while the parental gate is closed.
        val ordinary = LiveTvChannel("safe", "Adult Swim", null, setOf("Jeunesse"), null,
            listOf(LiveTvVariant(LisyEntry("safe", "Adult Swim", null, emptyList()), null, "Jeunesse", null)))
        var accessRequests = 0
        var playbackRequests = 0
        ui.setContent {
            NuvioTheme {
                TvBrowser(channels = listOf(ordinary), guide = emptyMap(), now = 1_000L,
                    favorites = emptySet(), playingId = null, topPadding = null,
                    loadFailed = false, playbackFailed = false, onRetry = {}, onFavorite = {},
                    onLive = { playbackRequests++ }, onArchive = { playbackRequests++ },
                    adultChannelsLocked = true, requestAdultAccess = { accessRequests++ })
            }
        }
        val category = runBlocking { getString(Res.string.tv_parental_channels) }
        val locked = runBlocking { getString(Res.string.tv_parental_locked) }
        val unlock = runBlocking { getString(Res.string.tv_parental_unlock) }
        ui.onNode(hasText(category) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        ui.onNodeWithText(locked).assertIsDisplayed()
        ui.onNodeWithContentDescription("Adult Swim,", substring = true).assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, accessRequests); assertEquals(0, playbackRequests) }
        ui.onNodeWithText(unlock).performClick()
        ui.runOnIdle { assertEquals(2, accessRequests); assertEquals(0, playbackRequests) }
    }

    @Test
    fun adultAccessClearsTheCountryFilterAndRelockingHidesTheUnknownCountryStation() {
        val ordinary = LiveTvChannel("safe", "Ordinary fixture", null, setOf("Généralistes"), "France",
            listOf(LiveTvVariant(LisyEntry("safe", "Ordinary fixture", null, emptyList()), null, "Généralistes", "France")))
        val restricted = LiveTvChannel("private", "Restricted fixture", null, setOf("Adulte"), null,
            listOf(LiveTvVariant(LisyEntry("private", "Restricted fixture", null, emptyList()), null, "Adulte", null,
                parentalLock = true)))
        var unlocked by mutableStateOf(false)
        var accessRequests = 0
        var playbackRequests = 0
        ui.setContent {
            NuvioTheme {
                TvBrowser(
                    channels = if (unlocked) listOf(ordinary, restricted) else listOf(ordinary),
                    guide = emptyMap(), now = 1_000L, favorites = emptySet(), playingId = null,
                    topPadding = null, loadFailed = false, playbackFailed = false,
                    onRetry = {}, onFavorite = {}, onLive = { playbackRequests++ }, onArchive = { playbackRequests++ },
                    adultChannelsLocked = !unlocked,
                    requestAdultAccess = { accessRequests++; unlocked = true },
                    relockAdultChannels = if (unlocked) ({ unlocked = false }) else null,
                )
            }
        }
        val allCountries = runBlocking { getString(Res.string.lisy_tv_all_countries) }
        val category = runBlocking { getString(Res.string.tv_parental_channels) }
        val countryNotice = runBlocking { getString(Res.string.tv_adult_catalog_all_countries) }
        val relock = runBlocking { getString(Res.string.tv_parental_relock) }
        val locked = runBlocking { getString(Res.string.tv_parental_locked) }
        ui.onNodeWithText(allCountries).performClick()
        ui.onNodeWithText("France").performClick()
        ui.onNode(hasText(category) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        ui.onNodeWithText(allCountries).assertIsDisplayed()
        ui.onNodeWithText(countryNotice).assertIsDisplayed()
        ui.onNodeWithContentDescription("Restricted fixture,", substring = true).assertExists()
        ui.runOnIdle { assertEquals(1, accessRequests); assertEquals(0, playbackRequests) }
        ui.onNodeWithText(relock).performClick()
        ui.onNodeWithText(locked).assertIsDisplayed()
        ui.onNodeWithContentDescription("Restricted fixture,", substring = true).assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, playbackRequests) }
    }
}
