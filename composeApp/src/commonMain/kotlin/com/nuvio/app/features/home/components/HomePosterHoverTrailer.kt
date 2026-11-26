package com.nuvio.app.features.home.components

import com.nuvio.app.features.details.MetaTrailer
import com.nuvio.app.features.details.youtubePlaybackUrl
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CancellationException

internal suspend fun resolveHomePosterHoverTrailerPlaybackSource(
    trailer: MetaTrailer,
): TrailerPlaybackSource? {
    return try {
        TrailerPlaybackResolver.resolveFromYouTubeUrl(trailer.youtubePlaybackUrl())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
}
