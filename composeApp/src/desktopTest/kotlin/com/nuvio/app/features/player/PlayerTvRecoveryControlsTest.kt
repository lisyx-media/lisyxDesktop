package com.nuvio.app.features.player

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.features.lisy.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class PlayerTvRecoveryControlsTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun autoTriesTheNextFeedOnlyOnceAndRetryRestartsFromTheFirst() {
        val requests = CopyOnWriteArrayList<String>()
        val opened = CopyOnWriteArrayList<String>()
        val server = LisyServer("Test", "https://lisyx-qa.invalid/live-recovery", "00:00:00:00:00:01")
        val station = LiveTvChannel("tv", "Station", null, emptySet(), null, listOf("a", "b").map {
            LiveTvVariant(LisyEntry(it, it, it, emptyList()), null, null, null)
        })
        val http = HttpClient(MockEngine { request ->
            val payload = when (request.url.parameters["action"]) {
                "handshake" -> """{"token":"mock"}"""
                "get_profile" -> "{}"
                "create_link" -> {
                    val cmd = checkNotNull(request.url.parameters["cmd"])
                    requests.add(cmd)
                    """{"cmd":"https://lisyx-qa.invalid/$cmd"}"""
                }
                else -> error("Unexpected request")
            }
            respond("""{"js":$payload}""")
        })
        try {
            val portal = LisyPortal(server, http)
            lateinit var controls: PlayerTvSources
            ui.setContent {
                val state = stationSourceControls(server, station, "https://lisyx-qa.invalid/a", setOf("a"), portal) { opened.add(it) }
                SideEffect { controls = state }
            }
            ui.runOnIdle { controls.fail("https://lisyx-qa.invalid/a"); controls.fail("https://lisyx-qa.invalid/a") }
            ui.waitUntil(5000) { opened.size == 1 }
            assertEquals(listOf("b"), requests.toList())
            // A native EOF from the outgoing player must not skip the new stream.
            ui.runOnIdle { controls.observe("https://lisyx-qa.invalid/a", PlayerPlaybackSnapshot(liveVideoFrames = -1), true) }
            ui.runOnIdle { assertNull(controls.terminalError) }
            ui.runOnIdle { controls.observe(opened.last(), PlayerPlaybackSnapshot(liveVideoFrames = -1), true) }
            ui.waitUntil(5000) { controls.terminalError != null }
            ui.runOnIdle { controls.fail("https://lisyx-qa.invalid/a"); controls.fail("https://lisyx-qa.invalid/a"); controls.retry() }
            ui.waitUntil(5000) { opened.size == 2 }
            ui.runOnIdle { assertNull(controls.terminalError) }
            assertEquals(listOf("b", "a"), requests.toList())
        } finally {
            http.close()
        }
    }
}
