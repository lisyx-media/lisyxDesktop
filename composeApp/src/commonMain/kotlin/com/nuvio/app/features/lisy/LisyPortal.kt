package com.nuvio.app.features.lisy

import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.core.network.API_RESPONSE_MAX_BYTES
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

@Serializable
private data class PortalEnvelope<T>(val js: T)

@Serializable
private data class PortalRows<T>(
    val data: List<T>,
    @kotlinx.serialization.SerialName("total_items") val totalItems: JsonPrimitive? = null,
    @kotlinx.serialization.SerialName("max_page_items") val maxPageItems: JsonPrimitive? = null,
)

private val lineupJson = Json { ignoreUnknownKeys = true }

internal class LisyPortal(private val server: LisyServer, private val http: HttpClient = client) {
    private var token: String? = null
    private var guideChannelIds = emptySet<String>()
    private val archiveMutex = Mutex()
    private val connectionMutex = Mutex()
    private val replayWeekMutex = Mutex()
    private var replayWeek: Pair<TimeMark, List<String>>? = null
    private val guideRequests = Semaphore(3)
    private val guidePending = mutableMapOf<Pair<String, String>, CompletableDeferred<GuideDay>>()
    private val archivePages = mutableMapOf<Pair<String, String>, Pair<TimeMark, GuideDay>>()

    private suspend fun request(type: String, action: String, parameters: Map<String, String> = emptyMap()): JsonElement {
        val body = requestBody(type, action, parameters)
        return Json.parseToJsonElement(body).jsonObject["js"]?.takeUnless { it is JsonNull }
            ?: error("Invalid portal response")
    }

    private suspend fun requestBody(type: String, action: String, parameters: Map<String, String> = emptyMap(),
        wholeLineup: Boolean = false): String {
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
            if (wholeLineup) timeout { requestTimeoutMillis = 120_000 }
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
            readBoundedResponseBody(response.bodyAsChannel(), response.contentLength(),
                if (wholeLineup) 64 * 1024 * 1024 else API_RESPONSE_MAX_BYTES)
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

    internal fun allowsPlayback(channel: LiveTvChannel): Boolean = !channel.requiresParentalPin ||
        TvParentalControls.isUnlocked(com.nuvio.app.features.profiles.ProfileRepository.activeProfileId, server)

    suspend fun list(catalog: LisyCatalog, page: Int, search: String, seriesId: String? = null): LisyPage {
        connect()
        if (catalog == LisyCatalog.Live) {
            val entries = liveChannels().filter(::allowsPlayback).flatMap { channel ->
                channel.variants.map { it.entry }.filter { it.title.contains(search, ignoreCase = true) }
            }
            return LisyPage(entries, false)
        }
        val data = run {
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
        }
        val total = container?.text("total_items")?.toLongOrNull()
        val pageSize = container?.text("max_page_items")?.toLongOrNull()?.takeIf { it > 0 } ?: rows.size.toLong()
        return LisyPage(entries, rows.isNotEmpty() && total != null && page * pageSize < total)
    }

    suspend fun liveChannels(): List<LiveTvChannel> = withContext(Dispatchers.Default) {
        connect()
        val genres = request("itv", "get_genres").catalogRows().mapNotNull { genre ->
            genre.text("id")?.let { it to genre }
        }.toMap()
        val base = URLBuilder(server.url).apply { parameters.clear(); fragment = "" }.build()
        fun rows(fallbackGenre: JsonObject? = null) = object : KSerializer<LiveTvVariant?> {
            override val descriptor = JsonElement.serializer().descriptor
            override fun serialize(encoder: Encoder, value: LiveTvVariant?): Unit = error("Read-only portal catalogue")
            override fun deserialize(decoder: Decoder): LiveTvVariant? {
                // Keep only playback metadata as each row is decoded, even for very large lineups.
                val row = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: return null
                val id = row.text("id") ?: return null
                val title = row.text("name")?.takeIf { it.isNotBlank() } ?: id
                val rowGenreId = row.text("tv_genre_id")?.takeUnless { it in setOf("", "0", "*") }
                if (fallbackGenre != null && rowGenreId != null &&
                    rowGenreId != fallbackGenre.text("id") && !row.isParentalLocked()) {
                    // A portal ignoring its genre filter must not reclassify public stations as adult.
                    return null
                }
                val genre = fallbackGenre ?: row.text("tv_genre_id")?.let(genres::get)
                val category = genre?.text("title") ?: genre?.text("name")
                    ?: row.text("genre_title")?.takeIf { it.isNotBlank() }
                val parentalLock = row.isParentalLocked() || genre?.isParentalLocked() == true ||
                    liveTvCategory(category) == "Adulte"
                return LiveTvVariant(
                    entry = LisyEntry(id, title, row.text("cmd"), emptyList()),
                    logo = row.text("logo")?.takeIf { it.isNotBlank() }?.let { logo ->
                        runCatching { base.resolveUrl(logo) }.getOrNull()
                    },
                    parentalLock = parentalLock,
                    category = if (parentalLock) "Adulte" else liveTvCategory(category),
                    rawCategory = category,
                    language = liveTvLanguage(row.text("language") ?: row.text("lang"), title, category),
                    guideStationId = row.text("xmltv_id")?.trim()?.takeUnless { it.isEmpty() || it == "0" },
                    replayAvailable = listOf("enable_tv_archive", "has_archive", "tv_archive_duration", "tv_archive")
                        .any { row.positiveField(it) },
                    archiveHours = row.text("tv_archive_duration")?.toIntOrNull()?.takeIf { it > 0 },
                    country = liveTvCountry(row.text("country_code") ?: row.text("country"), title, category),
                )
            }
        }
        fun decode(body: String, serializer: KSerializer<LiveTvVariant?>): PortalRows<LiveTvVariant?> = try {
            lineupJson.decodeFromString(PortalEnvelope.serializer(PortalRows.serializer(serializer)), body).js
        } catch (_: SerializationException) {
            // Some portals put the array directly in js, without pagination metadata.
            PortalRows(lineupJson.decodeFromString(PortalEnvelope.serializer(ListSerializer(serializer)), body).js)
        }
        val body = requestBody("itv", "get_all_channels", wholeLineup = true)
        val variants = decode(body, rows()).data.filterNotNull().associateByTo(linkedMapOf()) { it.entry.id }
        // Some portals omit protected bouquets entirely from get_all_channels.
        // Ask every explicitly protected bouquet for its complete paginated list.
        val restrictedGenres = genres.filterValues { genre ->
            genre.isParentalLocked() || liveTvCategory(genre.text("title") ?: genre.text("name")) == "Adulte"
        }
        for ((genreId, genre) in restrictedGenres) {
            try {
                connect()
                val seenIds = mutableSetOf<String>()
                var received = 0L
                val serializer = rows(genre)
                for (page in 1..500) {
                    currentCoroutineContext().ensureActive()
                    val response = decode(requestBody("itv", "get_ordered_list", mapOf(
                        "genre" to genreId, "category" to genreId, "p" to page.toString(),
                        "sortby" to "number", "search" to "",
                    )), serializer)
                    var added = false
                    for (variant in response.data.filterNotNull()) {
                        if (seenIds.add(variant.entry.id)) added = true
                        val previous = variants[variant.entry.id]
                        variants[variant.entry.id] = if (previous == null) variant else variant.copy(
                            entry = variant.entry.copy(command = variant.entry.command ?: previous.entry.command),
                            logo = variant.logo ?: previous.logo,
                            country = variant.country ?: previous.country,
                            guideStationId = variant.guideStationId ?: previous.guideStationId,
                            replayAvailable = variant.replayAvailable || previous.replayAvailable,
                            archiveHours = variant.archiveHours ?: previous.archiveHours,
                        )
                    }
                    received += response.data.size
                    val total = response.totalItems?.content?.toLongOrNull()
                    if (!added || response.data.isEmpty() || total != null && received >= total) break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preserve the public lineup when a protected bouquet is temporarily unavailable.
            }
        }
        groupLiveTvChannels(variants.values.toList())
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
        knownWindow: List<LiveTvProgram> = emptyList(),
    ): List<LiveTvProgram> = withContext(Dispatchers.Default) {
        if (!allowsPlayback(channel)) return@withContext emptyList()
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
        // A local day may last 23 or 25 hours. Midnight of the next local date is its true end.
        val followingDate = midnight?.let { EpisodeReleaseDatePlatform.localIsoDateAtEpochMs((it + 36 * 3600) * 1000) }
        val nextMidnight = followingDate?.let(CurrentDateProvider::localStartOfDayEpochMs)?.div(1000)
        val wantedStart = if (midnight != null && windowStart != null) maxOf(midnight, windowStart) else windowStart
        val wantedEnd = if (nextMidnight != null && windowEnd != null && nextMidnight > (windowStart ?: 0L))
            minOf(nextMidnight, windowEnd) else windowEnd
        fun add(entries: List<LiveTvProgram>) {
            for (entry in entries) {
                val existing = programs[entry.start]
                if (existing == null || entry.end > existing.end) programs[entry.start] = entry
            }
        }
        fun enough() = if (wantedStart == null || wantedEnd == null) programs.isNotEmpty() else
            coversGuideWindow(knownWindow + programs.values, wantedStart, wantedEnd)
        if (enough()) return@withContext emptyList()
        val cachedIds = mutableSetOf<String>()
        if (!forceRefresh) {
            val lifetime = if (date == CurrentDateProvider.todayIsoDate()) 3.minutes else 6.hours
            archiveMutex.withLock {
                for (variant in variants) {
                    val cached = archivePages[variant.entry.id to date]?.takeIf { it.first.elapsedNow() < lifetime } ?: continue
                    cachedIds += variant.entry.id
                    add(cached.second.programmes)
                    hadResponse = true
                    if (enough()) return@withContext programs.values.sortedBy { it.start }
                }
            }
        }
        for (variant in variants) {
            if (variant.entry.id in cachedIds) continue
            try {
                val entries = guideDay(variant.entry.id, date, forceRefresh).programmes
                hadResponse = true
                add(entries)
                if (enough()) return@withContext programs.values.sortedBy { it.start }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
            }
        }
        if (!hadResponse) failure?.let { throw it }
        programs.values.sortedBy { it.start }
    }

    suspend fun replayDays(): List<String> = replayWeekMutex.withLock {
        replayWeek?.takeIf { it.first.elapsedNow() < 3.minutes }?.let { return@withLock it.second }
        connect()
        val rows = request("epg", "get_week").catalogRows()
        val today = rows.firstOrNull { it.positiveField("today") }?.text("f_mysql")?.takeIf(isoDay::matches)
            ?: CurrentDateProvider.todayIsoDate()
        rows.mapNotNull { row -> row.text("f_mysql")?.takeIf { isoDay.matches(it) && it <= today } }
            .distinct().sortedDescending().also { replayWeek = TimeSource.Monotonic.markNow() to it }
    }

    /** Reports checked days newest first so that the first selection is deterministic. */
    suspend fun discoverReplayDays(
        channel: LiveTvChannel,
        onChecked: (String, List<LiveTvReplayProgram>) -> Unit,
    ) = coroutineScope {
        if (!allowsPlayback(channel) || channel.variants.none { it.replayAvailable }) return@coroutineScope
        var failure: Throwable? = null
        val now = kotlin.time.Clock.System.now().epochSeconds
        for (dates in replayDays().filter { channel.replayCanCoverDate(it, now) }.chunked(3)) {
            val requests = dates.map { day ->
                async {
                    try {
                        Result.success(replayPrograms(channel, day))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }
            for ((index, request) in requests.withIndex()) {
                request.await().fold(
                    onSuccess = { onChecked(dates[index], it) },
                    onFailure = { failure = it },
                )
            }
        }
        failure?.let { throw it }
        Unit
    }

    suspend fun replayPrograms(channel: LiveTvChannel, date: String): List<LiveTvReplayProgram> {
        if (!allowsPlayback(channel)) return emptyList()
        require(isoDay.matches(date))
        var failure: Exception? = null
        for (variant in channel.variants.filter { it.replayAvailable }) {
            val programs = try {
                archiveForDate(variant.entry.id, date)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                continue
            }
            if (programs.isNotEmpty()) return programs
        }
        failure?.let { throw it }
        return emptyList()
    }

    /** Only rows marked as archived by this station's provider are eligible for replay search. */
    suspend fun searchableReplayDay(channel: LiveTvChannel, date: String): List<LiveTvProgram> {
        require(isoDay.matches(date))
        if (!allowsPlayback(channel)) return emptyList()
        val confirmed = linkedMapOf<Long, LiveTvProgram>()
        for (variant in channel.variants.filter { it.replayAvailable }) {
            val day = guideDay(variant.entry.id, date)
            val starts = day.archives.mapNotNullTo(mutableSetOf()) { it.startEpochSeconds }
            day.programmes.filter { it.start in starts }.forEach { confirmed.getOrPut(it.start) { it } }
        }
        return confirmed.values.sortedBy { it.start }
    }

    suspend fun guideReplay(channel: LiveTvChannel, guide: LiveTvProgram): LiveTvReplayProgram? {
        if (!allowsPlayback(channel)) return null
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

    private fun JsonObject.isParentalLocked() =
        listOf("censored", "lock", "locked", "is_adult", "adult", "parental_lock").any { positiveField(it) }

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
