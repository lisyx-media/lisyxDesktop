package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.lisy.LisyPortal
import com.nuvio.app.features.lisy.LisyServerRepository
import com.nuvio.app.features.lisy.LiveTvProgram
import com.nuvio.app.features.lisy.LiveTvCache
import com.nuvio.app.features.lisy.scheduleFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock

@Composable
internal fun rememberPlayerTvSchedule(channelId: String): List<PlayerGuideProgramme> {
    val server by LisyServerRepository.uiState.collectAsStateWithLifecycle()
    val schedule by produceState(emptyList<PlayerGuideProgramme>(), server, channelId) {
        value = emptyList()
        val configured = server ?: return@produceState
        val portal = LisyPortal(configured)
        while (true) {
            try {
                val channel = LiveTvCache.channels(configured, portal).firstOrNull { it.id == channelId }
                if (channel != null) {
                    val now = Clock.System.now().epochSeconds
                    fun update(programmes: List<LiveTvProgram>) {
                        if (programmes.isEmpty()) return
                        value = (value + programmes.map { PlayerGuideProgramme(it.name, it.start, it.end) })
                            .filter { it.endSeconds >= now - 24 * 3600 && it.startSeconds <= now + 48 * 3600 }
                            .associateBy { it.startSeconds }.values.sortedBy { it.startSeconds }
                    }
                    if (value.isEmpty()) {
                        try {
                            update(channel.scheduleFrom(LiveTvCache.guide(configured, portal)))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The station's own schedule is also available on portals without a bulk guide.
                        }
                    }
                    LiveTvCache.guideWindow(portal, now, now + 2 * 3600, listOf(channel)) { _, programmes ->
                        update(programmes)
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Keep the last schedule through a temporary portal failure.
            }
            delay(3 * 60_000L)
        }
    }
    return schedule
}
