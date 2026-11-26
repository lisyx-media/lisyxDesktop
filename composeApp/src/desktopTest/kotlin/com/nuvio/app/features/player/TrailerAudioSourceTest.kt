package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TrailerAudioSourceTest {
    @Test
    fun nativeSourceRetainsBothTracksAndTheirSignedParameters() {
        val video = "https://video.example/v?s=a;b"
        val audio = "https://audio.example/é?s=%2B"
        assertEquals("edl://%29%$video;!new_stream;%30%$audio", mpvPlaybackUrl(video, audio))
        assertEquals(video, mpvPlaybackUrl(video, null))
    }
}
