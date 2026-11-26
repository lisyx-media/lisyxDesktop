package com.nuvio.app.features.lisy

import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal enum class LisyCatalog(val portalType: String, val mediaType: String) {
    Live("itv", "tv"), Movies("vod", "movie"), Series("series", "series"),
}

@Serializable
internal data class LisyEntry(val id: String, val title: String, val command: String?, val episodes: List<Int>,
    val tmdbId: String? = null, val year: String? = null, val season: Int? = null,
)
internal data class LisyPage(val entries: List<LisyEntry>, val hasMore: Boolean)

internal class LisyPortal(private val server: LisyServer, private val http: HttpClient = client) {
    private var token: String? = null
    private var guideChannelIds = emptySet<String>()
    private val archiveMutex = Mutex()
    private val connectionMutex = Mutex()
    private val guideRequests = Semaphore(3)
    private val guidePending = mutableMapOf<Pair<String, String>, CompletableDeferred<GuideDay>>()
    private val archivePages = mutableMapOf<Pair<String, String>, Pair<TimeMark, GuideDay>>()

    private suspend fun request(type: String, action: String, parameters: Map<String, String> = emptyMap()): JsonElement {
        val address = URLBuilder(server.url).apply {
            val path = encodedPath.trimEnd('/')
            encodedPath = when {
                path.endsWith("/c", ignoreCase = true) -> path.dropLast(2) + "/server/load.php"
                path.isEmpty() -> "/server/load.php"
                else -> path
            }
            fragment = ""
            (parameters + mapOf("type" to type, "action" to action, "JsHttpRequest" to "1-xml"))
                .forEach { (key, value) -> this.parameters.remove(key); this.parameters.append(key, value) }
        }.build()
        return http.prepareGet(address) {
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG200 stbapp ver: 2 rev: 250 Safari/533.3")
            header("X-User-Agent", "Model: MAG250; Link: WiFi")
            // Some portals calculate archive availability incorrectly with the French STB locale.
            // Keep their other requests localized and use the archive table's working protocol locale.
            val protocolLanguage = if (type == "epg" && action == "get_simple_data_table") "en" else "fr"
            header(HttpHeaders.Cookie, "mac=${server.mac.replace(":", "%3A")}; stb_lang=$protocolLanguage; timezone=Europe%2FParis")
            token?.let { bearerAuth(it) }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                token = null
                error("Portal request failed")
            }
            val body = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength())
            Json.parseToJsonElement(body).jsonObject["js"]?.takeUnless { it is JsonNull }
                ?: error("Invalid portal response")
        }
    }

    suspend fun connect(): Unit = connectionMutex.withLock {
        if (token != null) return@withLock
        token = request("stb", "handshake").jsonObject.text("token")?.takeIf { it.isNotBlank() }
            ?: error("Portal authentication failed")
        try {
            request("stb", "get_profile", mapOf("hd" to "1", "stb_type" to "MAG250"))
        } catch (failure: Exception) {
            token = null
            throw failure
        }
    }

    suspend fun list(catalog: LisyCatalog, page: Int, search: String, seriesId: String? = null): LisyPage {
        connect()
        val data = if (catalog == LisyCatalog.Live) request("itv", "get_all_channels") else {
            val parameters = if (seriesId != null) mapOf("movie_id" to seriesId) else
                mapOf("sortby" to "added", "search" to search, "category" to "0", "genre" to "0")
            request(catalog.portalType, "get_ordered_list", parameters + ("p" to page.toString()))
        }
        val container = data as? JsonObject
        val rows = (data as? JsonArray) ?: container?.get("data") as? JsonArray ?: error("Missing catalog")
        val entries = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val command = row.text("cmd")
            val id = row.text("id") ?: return@mapNotNull null
            val title = row.text("name") ?: row.text("title") ?: id
            val episodes = (row["series"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
            LisyEntry(
                id, title, command, episodes.distinct(),
                tmdbId = row.text("tmdb_id") ?: row.text("tmdb"),
                year = row.text("year"),
                season = (row.text("season_number") ?: row.text("season"))?.toIntOrNull(),
            )
        }.filter { catalog != LisyCatalog.Live || it.title.contains(search, ignoreCase = true) }
        val total = container?.text("total_items")?.toLongOrNull()
        val pageSize = container?.text("max_page_items")?.toLongOrNull()?.takeIf { it > 0 } ?: rows.size.toLong()
        return LisyPage(entries, catalog != LisyCatalog.Live && rows.isNotEmpty() && total != null && page * pageSize < total)
    }

    suspend fun liveChannels(): List<LiveTvChannel> = withContext(Dispatchers.Default) {
        connect()
        val channels = request("itv", "get_all_channels").catalogRows()
        val genres = try {
            request("itv", "get_genres").catalogRows().mapNotNull { genre ->
                val id = genre.text("id") ?: return@mapNotNull null
                val title = genre.text("title") ?: genre.text("name") ?: return@mapNotNull null
                id to title
            }.toMap()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            emptyMap()
        }
        val base = Url(server.url)
        val variants = channels.mapNotNull { row ->
            val id = row.text("id") ?: return@mapNotNull null
            val title = row.text("name")?.takeIf { it.isNotBlank() } ?: id
            val category = row.text("tv_genre_id")?.let(genres::get)
                ?: row.text("genre_title")?.takeIf { it.isNotBlank() }
            LiveTvVariant(
                entry = LisyEntry(id, title, row.text("cmd"), emptyList()),
                logo = row.text("logo")?.takeIf { it.isNotBlank() }?.let { logo ->
                    runCatching { URLBuilder(base).apply {
                        parameters.clear()
                        fragment = ""
                    }.build().resolveUrl(logo) }.getOrNull()
                },
                category = liveTvCategory(category),
                rawCategory = category,
                language = liveTvLanguage(row.text("language") ?: row.text("lang"), title, category),
                guideStationId = row.text("xmltv_id")?.trim()?.takeUnless { it.isEmpty() || it == "0" },
                replayAvailable = listOf("enable_tv_archive", "has_archive", "tv_archive_duration", "tv_archive")
                    .any { row.positiveField(it) },
                country = liveTvCountry(row.text("country_code") ?: row.text("country"), title, category),
            )
        }
        groupLiveTvChannels(variants)
    }

    suspend fun liveGuide(): Map<String, List<LiveTvProgram>> = withContext(Dispatchers.Default) {
        connect()
        val response = request("itv", "get_epg_info", mapOf("period" to "3")) as? JsonObject
            ?: error("Missing programme guide")
        val byChannel = response["data"] as? JsonObject ?: response
        byChannel.mapNotNull { (channelId, value) ->
            val programmes = (value as? JsonArray)?.mapNotNull programme@{ item ->
                val row = item as? JsonObject ?: return@programme null
                val name = row.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@programme null
                val start = row.text("start_timestamp")?.toLongOrNull() ?: return@programme null
                val end = row.text("stop_timestamp")?.toLongOrNull() ?: return@programme null
                LiveTvProgram(
                    name, start, end,
                    description = row.text("descr").orEmpty(),
                    category = row.text("category"),
                    imageUrl = programImage(row),
                    portalStartTime = row.text("time")?.takeIf { it.isNotBlank() },
                ).takeIf { end > start }
            }
            programmes?.takeIf { it.isNotEmpty() }?.let { channelId to it }
        }.toMap().also { guideChannelIds = it.keys }
    }

    suspend fun guideDays(): List<String> {
        connect()
        return request("epg", "get_week").catalogRows()
            .mapNotNull { row -> row.text("f_mysql")?.takeIf(isoDay::matches) }
            .distinct().sorted()
    }

    suspend fun guideForDay(
        channel: LiveTvChannel,
        date: String,
        forceRefresh: Boolean = false,
        windowStart: Long? = null,
        windowEnd: Long? = null,
    ): List<LiveTvProgram> {
        require(isoDay.matches(date))
        var failure: Exception? = null
        var hadResponse = false
        val programs = linkedMapOf<Long, LiveTvProgram>()
        val variants = channel.variants.sortedWith(
            compareByDescending<LiveTvVariant> { it.entry.id in guideChannelIds }
                .thenByDescending { it.guideStationId != null }
                .thenByDescending { it.replayAvailable },
        )
        val midnight = CurrentDateProvider.localStartOfDayEpochMs(date)?.div(1000)
        val wantedStart = if (midnight != null && windowStart != null) maxOf(midnight, windowStart) else windowStart
        val wantedEnd = if (midnight != null && windowEnd != null) minOf(midnight + 86_400, windowEnd) else windowEnd
        for (variant in variants) {
            try {
                val entries = guideDay(variant.entry.id, date, forceRefresh).programmes
                hadResponse = true
                for (entry in entries) {
                    val existing = programs[entry.start]
                    if (existing == null || entry.end > existing.end) programs[entry.start] = entry
                }
                if (entries.isNotEmpty() && (wantedStart == null || wantedEnd == null ||
                        wantedEnd <= wantedStart || coversGuideWindow(programs.values.toList(), wantedStart, wantedEnd))) {
                    return programs.values.sortedBy { it.start }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
            }
        }
        if (!hadResponse) failure?.let { throw it }
        return programs.values.sortedBy { it.start }
    }

    suspend fun replayDays(): List<String> {
        connect()
        val rows = request("epg", "get_week").catalogRows()
        val today = rows.firstOrNull { it.positiveField("today") }?.text("f_mysql")
            ?: CurrentDateProvider.todayIsoDate()
        return rows.mapNotNull { row -> row.text("f_mysql")?.takeIf { it.isNotBlank() && it <= today } }
            .distinct().sortedDescending()
    }

    suspend fun replayPrograms(channel: LiveTvChannel, date: String): List<LiveTvReplayProgram> {
        for (variant in channel.variants.filter { it.replayAvailable }) {
            val programs = archiveForDate(variant.entry.id, date)
            if (programs.isNotEmpty()) return programs
        }
        return emptyList()
    }

    suspend fun guideReplay(channel: LiveTvChannel, guide: LiveTvProgram): LiveTvReplayProgram? {
        val date = guide.portalStartTime?.take(10)?.takeIf { isoDay.matches(it) }
            ?: EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(guide.start * 1000) ?: return null
        var failure: Exception? = null
        for (variant in channel.variants.filter { it.replayAvailable }) {
            val entries = try {
                archiveForDate(variant.entry.id, date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                continue
            }
            entries.exactBroadcast(guide)?.let { return it }
        }
        failure?.let { throw it }
        return null
    }

    private data class GuideDay(
        val programmes: List<LiveTvProgram>,
        val archives: List<LiveTvReplayProgram>,
    )

    private suspend fun archiveForDate(channelId: String, date: String): List<LiveTvReplayProgram> =
        guideDay(channelId, date).archives

    private suspend fun guideDay(channelId: String, date: String, forceRefresh: Boolean = false): GuideDay {
        val key = channelId to date
        val lifetime = if (date == CurrentDateProvider.todayIsoDate()) 3.minutes else 6.hours
        val (pending, owner) = archiveMutex.withLock {
            if (!forceRefresh) archivePages[key]?.takeIf { it.first.elapsedNow() < lifetime }?.let { return it.second }
            guidePending[key]?.let { it to false }
                ?: (CompletableDeferred<GuideDay>().also { guidePending[key] = it } to true)
        }
        if (!owner) {
            try {
                return pending.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                // A scrolled-away viewport may cancel the shared owner while this one remains active.
                return guideDay(channelId, date, forceRefresh)
            }
        }
        try {
            val entries = guideRequests.withPermit {
                connect()
                fetchGuideDay(channelId, date)
            }
            archiveMutex.withLock {
                if (key !in archivePages && archivePages.size >= 96) archivePages.remove(archivePages.keys.first())
                archivePages[key] = TimeSource.Monotonic.markNow() to entries
            }
            pending.complete(entries)
            return entries
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                archiveMutex.withLock { if (guidePending[key] === pending) guidePending.remove(key) }
            }
            pending.completeExceptionally(failure)
            throw failure
        } finally {
            withContext(NonCancellable) {
                archiveMutex.withLock { if (guidePending[key] === pending) guidePending.remove(key) }
            }
        }
    }

    private suspend fun fetchGuideDay(channelId: String, date: String): GuideDay {
        val seenIds = mutableSetOf<String>()
        val programs = mutableListOf<LiveTvProgram>()
        val archives = mutableListOf<LiveTvReplayProgram>()
        var page = 1
        while (page <= 150) {
            val response = request("epg", "get_simple_data_table", mapOf(
                "ch_id" to channelId, "date" to date, "p" to page.toString(),
            ))
            val rows = response.catalogRows()
            val previousCount = seenIds.size
            rows.forEach { row ->
                val id = row.text("id") ?: return@forEach
                if (!seenIds.add(id)) return@forEach
                val name = row.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                val start = row.text("start_timestamp")?.toLongOrNull()
                val end = row.text("stop_timestamp")?.toLongOrNull()
                val description = row.text("descr").orEmpty()
                val category = row.text("category")
                val image = programImage(row)
                val portalTime = row.text("time")?.takeIf { it.isNotBlank() }
                if (start != null && end != null && end > start) {
                    programs.add(LiveTvProgram(name, start, end, description, category, image, portalTime))
                }
                if (row.positiveField("mark_archive")) archives.add(LiveTvReplayProgram(
                    id = id,
                    title = name,
                    description = description,
                    startLabel = row.text("t_time").orEmpty(),
                    endLabel = row.text("t_time_to").orEmpty(),
                    startEpochSeconds = start,
                    portalStartTime = portalTime,
                    category = category,
                    imageUrl = image,
                ))
            }
            val container = response as? JsonObject ?: break
            val total = container.text("total_items")?.toLongOrNull() ?: break
            val size = container.text("max_page_items")?.toLongOrNull()?.takeIf { it > 0 } ?: rows.size.toLong()
            if (seenIds.size == previousCount || page * size >= total) break
            page++
        }
        check(page <= 150) { "Programme guide exceeds page limit" }
        return GuideDay(programs.distinctBy { it.start }.sortedBy { it.start }, archives)
    }

    suspend fun replayStream(program: LiveTvReplayProgram): String {
        connect()
        val result = request("tv_archive", "create_link", mapOf("cmd" to "auto /media/${program.id}.ts"))
        val command = result.jsonObject.text("cmd") ?: error("Missing archive URL")
        val url = command.trim().split(Regex("\\s+")).firstOrNull {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        } ?: error("Invalid archive URL")
        require(Url(url).host.isNotBlank())
        return url
    }

    private fun JsonObject.positiveField(key: String): Boolean {
        val value = text(key) ?: return false
        return value.equals("true", true) || (value.toLongOrNull() ?: 0) > 0
    }

    private fun JsonElement.catalogRows(): List<JsonObject> {
        val rows = this as? JsonArray ?: (this as? JsonObject)?.get("data") as? JsonArray
            ?: error("Missing catalog")
        return rows.mapNotNull { it as? JsonObject }
    }

    private fun programImage(row: JsonObject): String? {
        val value = listOf("image", "image_url", "poster", "poster_url", "thumbnail", "picture", "cover")
            .firstNotNullOfOrNull { key -> row.text(key)?.trim()?.takeIf(String::isNotEmpty) }
            ?: return null
        return runCatching {
            URLBuilder(server.url).apply { parameters.clear(); fragment = "" }.build().resolveUrl(value)
        }.getOrNull()
    }

    private fun Url.resolveUrl(path: String): String? {
        val url = URLBuilder(this).takeFrom(path).build()
        return url.toString().takeIf { url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS }
    }

    suspend fun stream(catalog: LisyCatalog, entry: LisyEntry, episode: Int? = null): String {
        connect()
        val command = entry.command ?: error("Missing stream")
        val result = request(
            if (catalog == LisyCatalog.Live) "itv" else "vod", "create_link",
            mapOf("cmd" to command, "series" to (episode ?: 0).toString(),
                "forced_storage" to "false", "disable_ad" to "false", "download" to "false"),
        ).jsonObject.text("cmd") ?: error("Missing stream URL")
        val rawUrl = result.trim().replace(Regex("^(ffmpeg|ffrt)\\s+"), "").substringBefore(' ')
        val url = URLBuilder(rawUrl)
        check(rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true))
        check(url.host.isNotBlank())
        val streamId = Regex("[?&]stream=(\\d+)").find(command)?.groupValues?.get(1)
        if (streamId != null && url.parameters["stream"] == "") {
            url.parameters.remove("stream")
            url.parameters.append("stream", streamId)
        }
        return url.buildString()
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    companion object {
        private val isoDay = Regex("""\d{4}-\d{2}-\d{2}""")
        private val client by lazy { createApiHttpClient() }
    }
}
