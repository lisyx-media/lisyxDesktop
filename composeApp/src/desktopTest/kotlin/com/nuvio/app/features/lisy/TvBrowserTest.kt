package com.nuvio.app.features.lisy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.core.ui.NuvioTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class TvBrowserTest {
    @get:Rule val ui = createComposeRule()

    @get:Rule val images = TemporaryFolder()

    private fun logo(name: String, ink: Color): String {
        val bitmap = BufferedImage(240, 90, BufferedImage.TYPE_INT_ARGB)
        val draw = bitmap.createGraphics()
        draw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        draw.font = Font(Font.SANS_SERIF, Font.BOLD, 34)
        draw.color = ink
        draw.drawString(name, (240 - draw.fontMetrics.stringWidth(name)) / 2, 57)
        draw.dispose()
        val file = images.newFile("$name.png")
        ImageIO.write(bitmap, "png", file)
        return file.toURI().toString()
    }

    private fun channel(id: String, category: String, replay: Boolean) = LiveTvChannel(
        id, id, logo(id, if (replay) Color.BLACK else Color.WHITE), setOf(category), "France",
        listOf(LiveTvVariant(LisyEntry(id, id, null, emptyList()), null, category, "France", replay)),
    )

    @Test
    fun browseFavoritesGuideAndReplay() {
        val channels = listOf(
            channel("TF1", "Généralistes", true), channel("France 2", "Généralistes", false),
            channel("Eurosport", "Sport", true), channel("L’Équipe", "Sport", false),
            channel("Gulli", "Jeunesse", true), channel("ARTE", "Découverte", true),
        )
        val titles = listOf("Journal de 13 h", "Météo à la carte", "Cyclisme sur piste", "L’Équipe de choc",
            "Bienvenue chez les Loud", "Invitation au voyage")
        val next = listOf("Grands reportages", "Affaire conclue", "Le magazine du cyclisme", "Le grand débrief", "Totally Spies !", "Le dessous des cartes")
        val later = listOf("Sept à huit", "Tout le monde a son mot à dire", "Tennis en direct", "L’Équipe du soir", "Pokémon", "Voyage aux Galápagos")
        val now = 1763993400L
        val guide = channels.mapIndexed { i, c ->
            val transition = now + 1200 + i * 200
            c.id to listOf(LiveTvProgram("La matinale de ${c.name}", now - 8000, now - 1400),
                LiveTvProgram(titles[i], now - 1400, transition),
                LiveTvProgram(next[i], transition, transition + 2400),
                LiveTvProgram(later[i], transition + 2400, now + 14400))
        }.toMap()
        var played: String? = null
        var archive: String? = null
        ui.setContent {
            var favorites by remember { mutableStateOf(setOf("TF1", "Eurosport")) }
            NuvioTheme {
                TvBrowser(channels, guide, now, favorites, null, null, false, false, {},
                    { favorites = if (it.id in favorites) favorites - it.id else favorites + it.id },
                    { played = it.id }, { archive = it.id }, lookupReplay = { _, p ->
                        LiveTvReplayProgram("archive-71", p.name, "", "", "")
                    }, onReplay = { c, p -> archive = "${c.id}/${p.id}" })
            }
        }
        fun label(id: StringResource) = runBlocking { getString(id) }
        fun click(id: StringResource) = ui.onNodeWithText(label(id)).performClick()
        fun card(name: String) = ui.onNodeWithContentDescription("$name,", substring = true)
        fun snapshot(name: String) {
            val directory = File("build/reports/tv-browser").apply { mkdirs() }
            ImageIO.write(ui.onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
        }
        snapshot("direct")
        click(Res.string.lisy_tv_favorites)
        ui.onNodeWithText("Sport").performClick()
        card("Eurosport").assertExists().performClick()
        card("TF1").assertDoesNotExist()
        card("L’Équipe").assertDoesNotExist()
        assertEquals("Eurosport", played)
        click(Res.string.lisy_tv_favorites)
        card("L’Équipe").assertExists()
        click(Res.string.lisy_tv_all_channels)
        click(Res.string.lisy_tv_guide)
        ui.onNodeWithText("Journal de 13 h").assertExists()
        ui.onNodeWithText(channels.first().flaggedName).performClick()
        click(Res.string.lisy_tv_play)
        assertEquals("TF1", played)
        snapshot("guide")
        ui.onNodeWithContentDescription(label(Res.string.tv_later)).performClick()
        ui.onNodeWithText("Journal de 13 h").assertDoesNotExist()
        click(Res.string.lisy_tv_now)
        ui.onNodeWithText("Journal de 13 h").assertExists()
        ui.onNodeWithContentDescription(label(Res.string.tv_earlier)).performClick()
        ui.onNodeWithText("La matinale de TF1").performClick()
        click(Res.string.tv_guide_play_replay)
        ui.waitForIdle()
        assertEquals("TF1/archive-71", archive)
        click(Res.string.lisy_tv_replay)
        card("France 2").assertDoesNotExist()
        card("TF1").performClick()
        assertEquals("TF1", archive)
        snapshot("replay")
    }

    @Test
    fun programmeSearchOpensTheMatchingBroadcastAndReplayRemainsInTheTvTab() {
        val channel = channel("ARTE", "Découverte", true)
        val now = 1795550700L
        val past = LiveTvProgram("L’Égypte antique", now - 5400, now - 1800)
        val day = "2026-11-24"
        val archive = LiveTvReplayProgram("egypte-1", past.name, "", "19:35", "20:35")
        var played: String? = null
        ui.setContent {
            var inArchive by remember { mutableStateOf(false) }
            NuvioTheme {
                TvBrowser(listOf(channel), mapOf(channel.id to listOf(past)), now, emptySet(), null, null,
                    false, false, {}, {}, {}, { inArchive = true },
                    lookupReplay = { _, _ -> archive }, onReplay = { _, item -> played = item.id },
                    onExitArchive = { inArchive = false },
                    archiveContent = if (inArchive) ({
                        TvArchiveContent(channel, listOf(day), day, listOf(archive), null, false, false,
                            {}, { inArchive = false }, {}, { played = it.id })
                    }) else null)
            }
        }
        fun label(id: StringResource) = runBlocking { getString(id) }
        ui.onNodeWithContentDescription(label(Res.string.lisy_tv_search)).performTextInput("egypte")
        ui.waitUntil { ui.onAllNodesWithText(past.name).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(past.name).performClick()
        ui.onNodeWithText(label(Res.string.tv_guide_play_replay)).performClick()
        ui.runOnIdle { assertEquals(archive.id, played) }
        ui.onNodeWithContentDescription(label(Res.string.lisy_tv_reset_search)).performClick()
        ui.onNodeWithText(label(Res.string.lisy_tv_replay)).performClick()
        ui.onNodeWithContentDescription("ARTE,", substring = true).performClick()
        ui.onNodeWithContentDescription(label(Res.string.tv_archive_back)).assertExists()
        ui.onNodeWithText(label(Res.string.lisy_tv_direct)).assertIsDisplayed().performClick()
        ui.onNodeWithContentDescription(label(Res.string.tv_archive_back)).assertDoesNotExist()
        ui.onNodeWithContentDescription("ARTE,", substring = true).assertIsDisplayed()
    }

    @Test
    fun pastProgrammeIsDisabledUntilVerifiedAndCanRetryAPlaybackFailure() {
        val channel = channel("TF1", "Généralistes", true)
        val now = 1795550400L
        val past = LiveTvProgram("Le journal de 20 h", now - 7200, now - 3600,
            "Les titres de la journée et un reportage de la rédaction.", "Journal")
        val deferred = CompletableDeferred<LiveTvReplayProgram?>()
        var attempts = 0
        ui.setContent {
            NuvioTheme {
                Box(Modifier.size(365.dp, 345.dp)) {
                    GuideProgrammeDetail(channel, past, now, false, { _, _ -> deferred.await() }, {},
                        onReplay = { _, p ->
                            assertEquals("verified-345", p.id)
                            attempts++
                            if (attempts == 1) error("Temporary failure")
                        }, onDismiss = {})
                }
            }
        }
        fun label(resource: StringResource) = runBlocking { getString(resource) }
        ui.onNodeWithText(label(Res.string.tv_guide_checking_replay)).assertIsNotEnabled()
        deferred.complete(LiveTvReplayProgram("verified-345", past.name, "", "20:00", "21:00"))
        val play = ui.onNodeWithText(label(Res.string.tv_guide_play_replay))
        ui.waitUntil { ui.onAllNodesWithText(label(Res.string.tv_guide_play_replay)).fetchSemanticsNodes().isNotEmpty() }
        play.assertIsDisplayed().assertIsEnabled()
        val directory = File("build/reports/tv-browser").apply { mkdirs() }
        ImageIO.write(ui.onRoot().captureToImage().toAwtImage(), "png", File(directory, "programme-compact.png"))
        play.performClick()
        ui.onNodeWithText(label(Res.string.tv_guide_replay_play_error)).assertIsDisplayed()
        play.performClick()
        ui.runOnIdle { assertEquals(2, attempts) }
    }

    @Test
    fun missingReplayRemainsDisabledAndDialogSupportsDismissal() {
        val channel = channel("France 2", "Généralistes", true)
        val now = 1795550700L
        val past = LiveTvProgram("Un si grand soleil", now - 3500, now - 2400, "Les destins croisés des habitants de Montpellier.", "Série")
        val current = LiveTvProgram("Le journal", now - 2400, now + 1800)
        ui.setContent {
            NuvioTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    TvGuide(listOf(channel), mapOf(channel.id to listOf(past, current)), now, 20.dp, null, {},
                        lookupReplay = { _, _ -> null }, onReplay = { _, _ -> error("Unavailable replay launched") })
                }
            }
        }
        fun label(resource: StringResource) = runBlocking { getString(resource) }
        ui.onNodeWithContentDescription(label(Res.string.tv_earlier)).performClick()
        ui.onNodeWithText(past.name).performClick()
        ui.onNodeWithText(label(Res.string.tv_guide_unavailable_replay)).assertIsNotEnabled()
        val directory = File("build/reports/tv-browser").apply { mkdirs() }
        val dialog = ui.onAllNodes(isRoot()).onLast()
        ImageIO.write(dialog.captureToImage().toAwtImage(), "png", File(directory, "programme-dialog.png"))
        ui.onNodeWithContentDescription(label(Res.string.lisy_tv_close)).performKeyInput { pressKey(Key.Escape) }
        ui.onNodeWithText(label(Res.string.tv_guide_unavailable_replay)).assertDoesNotExist()
        ui.onNodeWithText(past.name).performClick()
        ui.onAllNodes(isRoot()).onFirst().performMouseInput { click(Offset(2f, 2f)) }
        ui.onNodeWithText(label(Res.string.tv_guide_unavailable_replay)).assertDoesNotExist()
    }

    @Test
    fun replayOnlyShowsConfirmedDaysAndKeepsTheSelectedDayWhileLoading() {
        val station = channel("TF1", "Généralistes", true)
        val checkingOldest = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val parameters = request.url.parameters
            respond(when (parameters["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_week" -> """{"js":[{"f_mysql":"2026-06-04","today":1},{"f_mysql":"2026-06-03"},
                    {"f_mysql":"2026-06-02"},{"f_mysql":"2026-06-01"}]}"""
                "get_simple_data_table" -> {
                    val day = parameters["date"].orEmpty()
                    if (day == "2026-06-01") checkingOldest.await()
                    val archived = if (day == "2026-06-03") 0 else 1
                    """{"js":[{"id":"archive-$day","name":"Journal du $day","t_time":"20:00","mark_archive":$archived}]}"""
                }
                "create_link" -> """{"js":{"cmd":"ffmpeg https://media.example/replay.ts"}}"""
                else -> error("Unexpected action")
            })
        }
        HttpClient(engine).use { client ->
            val portal = LisyPortal(LisyServer("Fixture", "https://portal.example/c", "00:11:22:33:44:55"), client)
            val played = CompletableDeferred<String>()
            ui.setContent { NuvioTheme {
                TvArchivePage(station, portal, onDismiss = {}) { programme, _ -> played.complete(programme.id) }
            } }
            try {
                val earlier = runBlocking { getString(Res.string.tv_archive_previous_day) }
                ui.waitUntil(6_000) { ui.onAllNodesWithContentDescription(earlier).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText("Journal du 2026-06-04").assertIsDisplayed()
                ui.onNodeWithContentDescription(earlier).performClick()
                ui.onNodeWithText("Journal du 2026-06-02").assertIsDisplayed()
                ui.onNodeWithContentDescription(earlier).assertIsNotEnabled()
                checkingOldest.complete(Unit)
                ui.waitUntil(6_000) {
                    ui.onAllNodes(hasContentDescription(earlier) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
                ui.onNodeWithText("Journal du 2026-06-02").performClick()
                ui.waitUntil(6_000) { played.isCompleted }
                assertEquals("archive-2026-06-02", runBlocking { played.await() })
            } finally {
                checkingOldest.complete(Unit)
            }
        }
    }

    @Test
    fun archiveDateChangesResetTheListingAndPlayTheSelectedProgramme() {
        val station = channel("TF1", "Généralistes", true)
        val days = listOf("2026-11-24", "2026-11-23")
        var played: String? = null
        val programs = listOf(
            LiveTvReplayProgram("news", "Le journal de 20 h", "L’actualité présentée par la rédaction.", "20:00", "20:40"),
            LiveTvReplayProgram("film", "Le sens de la fête", "Une comédie d’Éric Toledano et Olivier Nakache.", "21:10", "23:00"),
            LiveTvReplayProgram("mag", "Grands reportages", "Immersion auprès des secouristes des Alpes.", "14:05", "15:20"),
        )
        ui.setContent {
            var date by remember { mutableStateOf(days.first()) }
            NuvioTheme {
                TvArchiveContent(station, days, date, if (date == days.first()) programs else programs.take(1),
                    null, false, false, { date = it }, {}, {}, { played = it.id })
            }
        }
        val older = runBlocking { getString(Res.string.tv_archive_previous_day) }
        ui.onNodeWithText("Le sens de la fête").assertExists()
        val directory = File("build/reports/tv-browser").apply { mkdirs() }
        ImageIO.write(ui.onRoot().captureToImage().toAwtImage(), "png", File(directory, "archive.png"))
        val allDay = runBlocking { getString(Res.string.tv_archive_all_day) }
        val afternoon = runBlocking { getString(Res.string.tv_archive_afternoon) }
        ui.onNodeWithText(allDay).performClick()
        ui.onNodeWithText(afternoon).performClick()
        ui.onNodeWithText("Grands reportages").assertExists()
        ui.onNodeWithText("Le sens de la fête").assertDoesNotExist()
        ui.onNodeWithContentDescription(older).performClick()
        ui.onNodeWithText("Le sens de la fête").assertDoesNotExist()
        ui.onNodeWithText("Le journal de 20 h").performClick()
        assertEquals("news", played)
    }
}
