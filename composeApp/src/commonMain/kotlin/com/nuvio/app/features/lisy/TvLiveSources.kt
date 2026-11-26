package com.nuvio.app.features.lisy

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

internal object TvLiveSources {
    private val changed = MutableStateFlow(0)
    val choices = changed.asStateFlow()
    private var loadedKey: String? = null
    private var preferred = emptySet<String>()

    private fun load(server: LisyServer): String {
        val connection = server.url.trimEnd('/') + "|" + server.mac.uppercase()
        val key = "tv_streams_${ProfileRepository.activeProfileId}_${connection.hashCode()}"
        if (loadedKey != key) {
            preferred = LisyServerStorage.loadTvPreference(key)?.let {
                runCatching { Json.decodeFromString<Set<String>>(it) }.getOrNull()
            }.orEmpty()
            loadedKey = key
        }
        return key
    }

    fun selected(server: LisyServer, channel: LiveTvChannel): String? {
        load(server)
        return channel.variants.firstOrNull { it.entry.id in preferred }?.entry?.id
    }

    fun select(server: LisyServer, channel: LiveTvChannel, variantId: String?) {
        val key = load(server)
        val ids = channel.variants.map { it.entry.id }.toSet()
        if (variantId != null && variantId !in ids) return
        val remaining = preferred - ids
        preferred = if (variantId == null) remaining else remaining + variantId
        LisyServerStorage.saveTvPreference(key, Json.encodeToString(preferred))
        changed.value++
    }

    suspend fun resolve(portal: LisyPortal, channel: LiveTvChannel, variantId: String?): String? =
        resolveFrom(portal, channel, variantId)?.url

    suspend fun resolveFrom(
        portal: LisyPortal, channel: LiveTvChannel, variantId: String?, attempted: Set<String> = emptySet(),
    ): ResolvedTvStream? {
        if (!portal.allowsPlayback(channel)) return null
        return nextTvStream(channel, variantId, attempted) { entry ->
            if (portal.allowsPlayback(channel)) {
                kotlinx.coroutines.withTimeoutOrNull(12_000L) { portal.stream(LisyCatalog.Live, entry) }
            } else null
        }?.takeIf { portal.allowsPlayback(channel) }
    }
}

internal data class ResolvedTvStream(val url: String, val attemptedIds: Set<String>)

internal suspend fun nextTvStream(
    channel: LiveTvChannel, preferred: String?, attempted: Set<String>,
    resolve: suspend (LisyEntry) -> String?,
): ResolvedTvStream? {
    val tried = attempted.toMutableSet()
    for (variant in channel.variants) {
        val entry = variant.entry
        if (preferred != null && entry.id != preferred || !tried.add(entry.id)) continue
        val url = try {
            resolve(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null }
        if (!url.isNullOrBlank()) return ResolvedTvStream(url, tried)
    }
    return null
}

private val qualityTokens = Regex("""(?<![A-Z0-9])(FULL[ -]?HD|FHD|UHD|[48]K|HD|SD|HDR10\+?|HDR|HEVC?|[HX]\.?26[45]|(?:2160|1080|720)[PI]|\d{2}\s?FPS|RAW)(?![A-Z0-9])""")

internal fun LiveTvChannel.sourceLabels(): Map<String, String> {
    val showLanguages = country == null || variants.mapNotNull { it.language?.uppercase() }.distinct().size > 1
    val names = variants.map { variant ->
        val name = variant.entry.title.uppercase().replace("ᴴᴰ", " HD").replace("ᶠᴴᴰ", " FHD").replace("ᵁᴴᴰ", " UHD")
        val quality = qualityTokens.findAll(name).map { if (it.value == "HEV") "HEVC" else it.value }
            .distinct().joinToString(" · ").ifBlank { "Source" }
        val language = variant.language?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && showLanguages }
        listOfNotNull(quality, language).joinToString(" · ")
    }
    val counts = names.groupingBy { it }.eachCount()
    val occurrences = mutableMapOf<String, Int>()
    return variants.mapIndexed { index, variant ->
        val name = names[index]
        val number = (occurrences[name] ?: 0) + 1
        occurrences[name] = number
        variant.entry.id to if (name == "Source" || counts[name]!! > 1) "$name · $number" else name
    }.toMap()
}
