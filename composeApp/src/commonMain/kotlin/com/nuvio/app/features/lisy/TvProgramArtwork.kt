package com.nuvio.app.features.lisy

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Renders nothing while artwork is unknown, preserving the channel logo beneath it. */
@Composable
internal fun TvProgramArtwork(
    title: String,
    category: String? = null,
    description: String? = null,
    imageUrl: String? = null,
    modifier: Modifier = Modifier,
    station: LiveTvChannel? = null,
) {
    val settings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    var artwork by remember(title, category, imageUrl, settings.language) {
        mutableStateOf(imageUrl?.takeIf(::isTvArtworkUrl))
    }
    LaunchedEffect(title, category, description, imageUrl, settings.language) {
        artwork = imageUrl?.takeIf(::isTvArtworkUrl)
            ?: TvProgramArtworkRepository.find(title, category, settings.language)
    }
    val image = artwork
    if (image != null) {
        var loaded by remember(image) { mutableStateOf(false) }
        BoxWithConstraints(modifier) {
            NuvioAsyncImage(
                model = image,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
                onError = { artwork = null },
            )
            if (loaded && station?.logo != null) {
                val width = (maxWidth * .23f).coerceIn(30.dp, 56.dp)
                TvStationLogo(station, Modifier.align(Alignment.BottomStart)
                    .padding((maxWidth * .035f).coerceIn(4.dp, 10.dp)).size(width, width * .62f))
            }
        }
    }
}

internal fun isTvArtworkUrl(value: String): Boolean =
    (value.startsWith("https://", true) || value.startsWith("http://", true)) &&
        runCatching { io.ktor.http.Url(value).host.isNotBlank() }.getOrDefault(false)

internal object TvProgramArtworkRepository {
    private data class Entry(val image: String?, val time: TimeMark)
    private val lock = SynchronizedObject()
    private val cache = linkedMapOf<String, Entry>()
    private val pending = mutableMapOf<String, CompletableDeferred<String?>>()
    private val requests = Semaphore(2)

    suspend fun find(title: String, category: String?, language: String): String? {
        val query = artworkQuery(title) ?: return null
        val key = "$language:${category.orEmpty()}:$query:${programYear.find(title)?.value.orEmpty()}"
        val cached = synchronized(lock) { cache[key] }
        if (cached != null && cached.time.elapsedNow() < 6.hours) return cached.image

        val (work, owner) = synchronized(lock) {
            pending[key]?.let { it to false }
                ?: (CompletableDeferred<String?>().also { pending[key] = it } to true)
        }
        if (!owner) {
            return try {
                work.await()
            } catch (failure: CancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                null
            }
        }
        try {
            val image = requests.withPermit {
                withTimeoutOrNull(7_000) {
                    val mediaTypes = artworkMediaTypes(category)
                    val results = mediaTypes.flatMap { mediaType ->
                        TmdbMetadataService.fetchSearchItems(mediaType, query, language).orEmpty()
                            .map { mediaType to it }
                    }
                    strictProgramArtwork(title, results)
                }
            }
            synchronized(lock) {
                if (cache.size >= 400) cache.remove(cache.keys.first())
                cache[key] = Entry(image, TimeSource.Monotonic.markNow())
            }
            work.complete(image)
            return image
        } catch (failure: Exception) {
            if (failure is CancellationException) {
                work.cancel(failure)
                throw failure
            }
            work.complete(null)
            return null
        } finally {
            synchronized(lock) { if (pending[key] === work) pending.remove(key) }
        }
    }
}

private val programYear = Regex("""\b(?:19|20)\d{2}\b""")
private val episodeSuffix = Regex("""\s*[-–:]?\s*(?:s\d+\s*e\d+|saison\s+\d+|épisode\s+\d+|episode\s+\d+).*$""", RegexOption.IGNORE_CASE)
private val excludedPrograms = Regex("""^(?:programmes? (?:de nuit|a venir)|fin des programmes|météo|meteo|journal|téléachat|teleachat|direct|publicité|publicite|le jt|jt)(?:\b|$)""", RegexOption.IGNORE_CASE)

internal fun artworkQuery(title: String): String? {
    if (title.length !in 4..180 || excludedPrograms.containsMatchIn(title.trim())) return null
    return title.replace(episodeSuffix, "").replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "")
        .trim().takeIf { it.length >= 4 }
}

private fun normalizeArtworkTitle(value: String): String = value.lowercase()
    .replace('à', 'a').replace('â', 'a').replace('ä', 'a')
    .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('ë', 'e')
    .replace('î', 'i').replace('ï', 'i').replace('ô', 'o').replace('ö', 'o')
    .replace('ù', 'u').replace('û', 'u').replace('ü', 'u').replace('ç', 'c')
    .filter(Char::isLetterOrDigit)

private fun artworkMediaTypes(category: String?): List<TmdbEntityMediaType> {
    val genre = normalizeArtworkTitle(category.orEmpty())
    return when {
        genre in setOf("film", "films", "cinema", "movie", "movies") -> listOf(TmdbEntityMediaType.MOVIE)
        genre in setOf("serie", "series", "tvseries") -> listOf(TmdbEntityMediaType.TV)
        else -> listOf(TmdbEntityMediaType.TV, TmdbEntityMediaType.MOVIE)
    }
}

internal fun strictProgramArtwork(
    title: String,
    results: List<Pair<TmdbEntityMediaType, MetaPreview>>,
): String? {
    val query = artworkQuery(title) ?: return null
    val expected = normalizeArtworkTitle(query)
    val year = programYear.find(title)?.value
    val matches = results.filter { (_, item) ->
        normalizeArtworkTitle(item.name) == expected &&
            (year == null || item.releaseInfo?.take(4) == year)
    }.distinctBy { (mediaType, item) -> mediaType to item.id }
    // A namesake or remake is ambiguous unless the guide supplied its release year.
    return matches.singleOrNull()?.second?.banner
}
