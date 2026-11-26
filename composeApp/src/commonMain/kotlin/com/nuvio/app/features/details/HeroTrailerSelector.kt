package com.nuvio.app.features.details

internal fun selectHeroTrailer(trailers: List<MetaTrailer>, preferredLanguage: String = "fr"): MetaTrailer? =
    trailers
        .asSequence()
        .filter { it.isPlayableYouTubeTrailerCandidate() }
        .distinctBy { it.key }
        .maxWithOrNull(
            compareBy<MetaTrailer>(
                { it.seasonNumber == null },
                { it.type.equals("Trailer", true) },
                { it.audioLanguagePriority(preferredLanguage) },
                { !it.isPromoCut() },
                { it.heroTrailerPriority() },
                { it.publishedAt.orEmpty() },
                { it.size ?: 0 },
                { it.name },
            ),
        )

internal fun MetaTrailer.youtubePlaybackUrl(): String =
    key.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: "https://www.youtube.com/watch?v=$key"

private fun MetaTrailer.isPlayableYouTubeTrailerCandidate(): Boolean =
    key.isNotBlank() && site.equals("YouTube", ignoreCase = true)

private fun MetaTrailer.heroTrailerPriority(): Int {
    val isSeriesTrailer = seasonNumber != null
    val isTrailerType = type.equals("Trailer", ignoreCase = true)
    return when {
        !isSeriesTrailer && isTrailerType && official -> 70
        !isSeriesTrailer && isTrailerType -> 60
        !isSeriesTrailer && official -> 50
        !isSeriesTrailer -> 40
        isTrailerType && official -> 30
        isTrailerType -> 20
        official -> 10
        else -> 0
    }
}

private val subtitled = Regex("""\b(?:vost(?:fr|f)?|vo|sous[ -]titr\p{L}*|subtitles?)\b""", RegexOption.IGNORE_CASE)
private val frenchDub = Regex("""\b(?:vf(?:f|q|r)?|doubl[ée]+e? en fran[cç]ais|version fran[cç]aise)\b""", RegexOption.IGNORE_CASE)

internal fun MetaTrailer.audioLanguagePriority(language: String): Int {
    val preferred = language.substringBefore('-')
    if (preferred.equals("fr", true)) {
        if (subtitled.containsMatchIn(name)) return 0
        if (frenchDub.containsMatchIn(name)) return 3
    }
    return if (iso6391.equals(preferred, true)) 2 else 1
}

private val promoCut = Regex("""\b(?:j\s*[-–]\s*\d+|spot(?: tv)?|tv spot|countdown|compte à rebours)\b""", RegexOption.IGNORE_CASE)

internal fun MetaTrailer.isPromoCut(): Boolean = promoCut.containsMatchIn(name)
