package com.nuvio.app.features.lisy

import com.nuvio.app.isDesktop
import kotlin.time.TimeSource
import com.nuvio.app.features.servers.ServerStreamSource
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal object LisyPlaybackSource {
    private const val SOURCE_ID = "lisy:iptv"
    private val mutex = Mutex()
    private var activeServer: LisyServer? = null
    private var portal: LisyPortal? = null
    private data class CachedMatches(val rows: List<LisyEntry>, val time: TimeSource.Monotonic.ValueTimeMark)
    private val matchCache = linkedMapOf<String, CachedMatches>()

    private fun portalFor(server: LisyServer): LisyPortal {
        if (activeServer != server) matchCache.clear()
        if (activeServer != server || portal == null) {
            portal = LisyPortal(server)
            activeServer = server
        }
        return checkNotNull(portal)
    }

    private fun configuredServer(): LisyServer? {
        if (!isDesktop) return null
        LisyServerRepository.ensureLoaded()
        return LisyServerRepository.uiState.value
    }

    fun canServe(type: String, id: String): Boolean =
        type in setOf("movie", "series") &&
            (id.startsWith("tmdb:") || id.startsWith("tt")) && configuredServer() != null

    fun sources(type: String, videoId: String, season: Int?, episode: Int?): List<ServerStreamSource> {
        if (!canServe(type, videoId)) return emptyList()
        val server = configuredServer() ?: return emptyList()
        return listOf(ServerStreamSource(SOURCE_ID, server.name, preferred = true) {
            withTimeout(35_000) {
                mutex.withLock {
                    if (configuredServer() != server) return@withLock emptyList()
                    try {
                        portalFor(server).resolve(server.name, type, videoId, season, episode)
                    } catch (error: Exception) {
                        portal = null
                        throw error
                    }
                }
            }
        })
    }

    private suspend fun LisyPortal.resolve(
        providerName: String, type: String, videoId: String, season: Int?, episode: Int?,
    ): List<StreamItem> {
        val catalog = if (type == "movie") LisyCatalog.Movies else LisyCatalog.Series
        val identity = videoId.removePrefix("tmdb:").split(':')
        val requestedSeason = season ?: identity.getOrNull(1)?.toIntOrNull()
        val episodeNumber = episode ?: identity.getOrNull(2)?.toIntOrNull()
        if (catalog == LisyCatalog.Series && (requestedSeason == null || episodeNumber == null)) return emptyList()

        val tmdbId = TmdbService.ensureTmdbId(videoId, type) ?: return emptyList()
        val matches = findMatches(catalog, tmdbId, null, null)

        var failed = false
        val streams = mutableListOf<StreamItem>()
        for (match in matches.take(6)) {
            try {
                val entry = if (catalog == LisyCatalog.Series) {
                    var matchingSeason: LisyEntry? = null
                    for (page in 1..30) {
                        val seasons = list(catalog, page, "", seriesId = match.id)
                        matchingSeason = seasons.entries.firstOrNull {
                            seasonNumber(it) == requestedSeason &&
                                episodeNumber in it.episodes && !it.command.isNullOrBlank()
                        }
                        if (matchingSeason != null || !seasons.hasMore) break
                    }
                    matchingSeason ?: continue
                } else match
                val url = stream(catalog, entry, episodeNumber)
                streams.add(StreamItem(
                    name = providerName,
                    description = match.title,
                    url = url,
                    sourceName = providerName,
                    addonName = providerName,
                    addonId = SOURCE_ID,
                    behaviorHints = StreamBehaviorHints(
                        notWebReady = true,
                        proxyHeaders = StreamProxyHeaders(request = mapOf("User-Agent" to "VLC/3.0.21 LibVLC/3.0.21")),
                    ),
                ))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed = true
            }
        }
        if (failed && streams.isEmpty()) error("IPTV playback link unavailable")
        return streams
    }

    private suspend fun LisyPortal.findMatches(
        catalog: LisyCatalog, tmdbId: String, previewTitle: String?, previewYear: String?,
    ): List<LisyEntry> {
        val settings = TmdbSettingsRepository.snapshot()
        val type = catalog.mediaType
        val key = "$type:$tmdbId:${settings.language}"
        matchCache[key]?.takeIf { it.time.elapsedNow().inWholeSeconds < 300 }?.let { return it.rows }
        val details = TmdbMetadataService.fetchStandaloneMeta(type, "tmdb:$tmdbId", settings)
        val original = TmdbMetadataService.cachedOriginalTitle(tmdbId, type)
        val titles = listOfNotNull(details?.name, original, previewTitle).filter { it.isNotBlank() }.distinct()
        val year = details?.releaseInfo?.take(4) ?: previewYear
        val matches = linkedMapOf<String, LisyEntry>()
        for (title in titles) {
            for (page in 1..3) {
                val result = list(catalog, page, title)
                for (entry in result.entries) {
                    if (matchesLisyTitle(entry, tmdbId, titles, year)) matches[entry.id] = entry
                }
                if (!result.hasMore) break
            }
        }
        if (matchCache.size >= 400) matchCache.remove(matchCache.keys.first())
        val rows = matches.values.toList()
        matchCache[key] = CachedMatches(rows, TimeSource.Monotonic.markNow())
        return rows
    }

    private fun seasonNumber(row: LisyEntry): Int =
        row.season ?: seasonInTitle.find(row.title)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private val seasonInTitle = Regex("(?:season|saison)\\s*(\\d+)", RegexOption.IGNORE_CASE)
}

private val yearInTitle = Regex("\\b(?:19|20)\\d{2}\\b")
private val decorations = Regex("\\b(?:2160p|1080p|720p|4k|uhd|fhd|multi|vostfr|vf|bluray|web-dl)\\b", RegexOption.IGNORE_CASE)

internal fun matchesLisyTitle(entry: LisyEntry, id: String, titles: List<String>, year: String?): Boolean {
    entry.tmdbId?.toLongOrNull()?.takeIf { it > 0 }?.let { return it.toString() == id }
    val entryYear = entry.year?.take(4) ?: yearInTitle.find(entry.title)?.value
    if (year == null || entryYear != year) return false
    fun normalized(title: String): String = title
        .replace(Regex("^[A-Z]{2}\\s*[:|–-]\\s*"), "")
        .replace(Regex("\\[.*?]"), "")
        .replace(yearInTitle, "")
        .replace(decorations, "")
        .lowercase()
        .foldLisyAccents()
        .filter(Char::isLetterOrDigit)
    val candidate = normalized(entry.title)
    return candidate.isNotBlank() && titles.any { normalized(it) == candidate }
}

internal fun String.foldLisyAccents(): String = map { c ->
    when (c) {
        in "àáâãäå" -> 'a'
        in "èéêë" -> 'e'
        in "ìíîï" -> 'i'
        in "òóôõö" -> 'o'
        in "ùúûü" -> 'u'
        in "ýÿ" -> 'y'
        'ç' -> 'c'
        'ñ' -> 'n'
        else -> c
    }
}.joinToString("")
