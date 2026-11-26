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
