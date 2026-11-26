package com.nuvio.app.features.lisy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

internal object TvSourceSeparations {
    private const val preferenceKey = "tv_separated_sources"
    private const val audioLanguageKey = "tv_source_audio_languages"
    private val revision = MutableStateFlow(0)
    val changes = revision.asStateFlow()

    fun ids(server: LisyServer): Set<String> = load()[connectionKey(server)].orEmpty()

    fun separate(server: LisyServer, variantId: String) {
        if (variantId.isBlank()) return
        val preferences = load()
        val key = connectionKey(server)
        if (variantId in preferences[key].orEmpty()) return
        LisyServerStorage.saveTvPreference(
            preferenceKey,
            Json.encodeToString(preferences + (key to (preferences[key].orEmpty() + variantId))),
        )
        revision.value++
    }

    fun restore(server: LisyServer, variantId: String) {
        val preferences = load()
        val key = connectionKey(server)
        if (variantId !in preferences[key].orEmpty()) return
        val remaining = preferences.getValue(key) - variantId
        val updated = if (remaining.isEmpty()) preferences - key else preferences + (key to remaining)
        LisyServerStorage.saveTvPreference(preferenceKey, Json.encodeToString(updated))
        revision.value++
    }

    fun recordAudioLanguage(server: LisyServer, variantId: String, language: String) {
        val normalized = liveTvLanguage(language, "", null) ?: return
        if (variantId.isBlank()) return
        val preferences = loadAudioLanguages()
        val key = connectionKey(server)
        val sources = preferences[key].orEmpty()
        if (sources[variantId] == normalized) return
        LisyServerStorage.saveTvPreference(
            audioLanguageKey,
            Json.encodeToString(preferences + (key to (sources + (variantId to normalized)))),
        )
        revision.value++
    }

    fun apply(server: LisyServer, channels: List<LiveTvChannel>): List<LiveTvChannel> {
        val languages = loadAudioLanguages()[connectionKey(server)].orEmpty()
        val annotated = if (languages.isEmpty()) channels else channels.flatMap { channel ->
            if (channel.variants.none { it.entry.id in languages }) return@flatMap listOf(channel)
            groupLiveTvChannels(channel.variants.map { variant ->
                languages[variant.entry.id]?.let { variant.copy(language = it) } ?: variant
            })
        }
        return separateLiveTvSources(annotated, ids(server))
    }

    private fun loadAudioLanguages(): Map<String, Map<String, String>> =
        LisyServerStorage.loadTvPreference(audioLanguageKey)?.let {
            runCatching { Json.decodeFromString<Map<String, Map<String, String>>>(it) }.getOrNull()
        }.orEmpty()

    private fun load(): Map<String, Set<String>> =
        LisyServerStorage.loadTvPreference(preferenceKey)?.let {
            runCatching { Json.decodeFromString<Map<String, Set<String>>>(it) }.getOrNull()
        }.orEmpty()

    private fun connectionKey(server: LisyServer): String =
        server.url.trimEnd('/') + "|" + server.mac.replace(":", "").replace("-", "").uppercase()
}

internal fun separateLiveTvSources(channels: List<LiveTvChannel>, variantIds: Set<String>): List<LiveTvChannel> {
    if (variantIds.isEmpty()) return channels
    return channels.flatMap { channel ->
        val (isolated, remaining) = channel.variants.partition { it.entry.id in variantIds }
        if (isolated.isEmpty()) return@flatMap listOf(channel)
        val labels = channel.sourceLabels()
        val main = if (remaining.isEmpty()) emptyList() else listOf(
            channel.copy(
                id = channel.id.takeIf { id -> remaining.any { it.entry.id == id } } ?: remaining.first().entry.id,
                variants = remaining,
            ),
        )
        main + isolated.map { variant ->
            channel.copy(
                id = variant.entry.id,
                name = "${channel.name} · ${labels.getValue(variant.entry.id)}",
                logo = variant.logo ?: channel.logo,
                country = null,
                variants = listOf(variant.copy(country = null)),
            )
        }
    }
}
