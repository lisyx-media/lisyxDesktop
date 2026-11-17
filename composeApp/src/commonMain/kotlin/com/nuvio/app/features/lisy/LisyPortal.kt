package com.nuvio.app.features.lisy

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

internal enum class LisyCatalog(val portalType: String, val mediaType: String) {
    Live("itv", "tv"), Movies("vod", "movie"), Series("series", "series"),
}

internal data class LisyEntry(val id: String, val title: String, val command: String?, val episodes: List<Int>,
    val tmdbId: String? = null, val year: String? = null, val season: Int? = null,
)
internal data class LisyPage(val entries: List<LisyEntry>, val hasMore: Boolean)

internal class LisyPortal(private val server: LisyServer, private val http: HttpClient = client) {
    private var token: String? = null

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
            header(HttpHeaders.Cookie, "mac=${server.mac.replace(":", "%3A")}; stb_lang=fr; timezone=Europe%2FParis")
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

    suspend fun connect() {
        if (token != null) return
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
        private val client by lazy { createApiHttpClient() }
    }
}
