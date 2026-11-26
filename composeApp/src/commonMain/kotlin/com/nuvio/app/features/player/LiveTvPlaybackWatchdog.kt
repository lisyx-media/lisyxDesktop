package com.nuvio.app.features.player

/** One instance per attempted live feed. The native count advances only for fresh video frames. */
internal class LiveTvPlaybackWatchdog {
    private var lastProgressAt: Long? = null
    private var lastFrame = 0L
    private var started = false

    fun failed(snapshot: PlayerPlaybackSnapshot, wantsPlayback: Boolean, nowMs: Long): Boolean {
        val frames = snapshot.liveVideoFrames
        if (!wantsPlayback || frames == -2L) {
            lastProgressAt = null
            return false
        }
        if (frames == -1L || snapshot.isEnded) return true
        // Desktop engines without the macOS native counter report progress via position and size.
        val progress = frames ?: if (snapshot.videoWidth > 0 && snapshot.videoHeight > 0) snapshot.positionMs else 0L
        if (progress > 0 && progress != lastFrame) {
            started = true
            lastFrame = progress
            lastProgressAt = nowMs
        }
        val since = lastProgressAt ?: nowMs.also { lastProgressAt = it }
        return nowMs - since >= if (started) 10_000 else 15_000
    }
}
