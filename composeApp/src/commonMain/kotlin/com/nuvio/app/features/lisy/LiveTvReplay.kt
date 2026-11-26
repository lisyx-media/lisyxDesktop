package com.nuvio.app.features.lisy

internal data class LiveTvReplayProgram(
    val id: String,
    val title: String,
    val description: String,
    val startLabel: String,
    val endLabel: String,
    val category: String? = null,
    val imageUrl: String? = null,
    val startEpochSeconds: Long? = null,
    val portalStartTime: String? = null,
)

internal fun List<LiveTvReplayProgram>.exactBroadcast(guide: LiveTvProgram): LiveTvReplayProgram? {
    return singleOrNull { archive ->
        archive.title.trim().equals(guide.name.trim(), ignoreCase = true) &&
            if (archive.startEpochSeconds != null) archive.startEpochSeconds == guide.start
            else guide.portalStartTime != null && archive.portalStartTime == guide.portalStartTime
    }
}

// MAG portals express tv_archive_duration in hours. An unspecified duration leaves the
// provider's replay calendar authoritative for that variant.
private fun LiveTvChannel.archiveCutoff(now: Long): Long? {
    val sources = variants.filter { it.replayAvailable }
    if (sources.isEmpty() || sources.any { it.archiveHours == null }) return null
    return now - sources.maxOf { checkNotNull(it.archiveHours) } * 3600L
}

internal fun LiveTvChannel.earliestReplayDate(now: Long): String? = archiveCutoff(now)?.let {
    com.nuvio.app.core.time.EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(it * 1000)
}

internal fun LiveTvChannel.replayCanCoverDate(date: String, now: Long): Boolean =
    earliestReplayDate(now)?.let { date >= it } ?: true
