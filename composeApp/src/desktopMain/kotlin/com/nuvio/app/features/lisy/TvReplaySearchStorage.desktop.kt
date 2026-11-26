package com.nuvio.app.features.lisy

import com.nuvio.app.core.storage.DesktopCache
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal actual fun platformReplaySearchStorage(): TvReplaySearchStorage? =
    DesktopReplaySearchStorage { DesktopStorage.cacheDir.resolve("live-tv-replay-search-v1") }

@OptIn(ExperimentalSerializationApi::class)
internal class DesktopReplaySearchStorage(private val directory: () -> Path) : TvReplaySearchStorage {
    private fun path(server: LisyServer, profile: Int): Path {
        val identity = profile.toString() + "\u0000" + server.url.trimEnd('/') + "\u0000" + server.mac.uppercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).toHexString()
        return directory().resolve("$digest.json.gz")
    }

    override suspend fun read(server: LisyServer, profile: Int): TvReplaySearchSnapshot? = withContext(Dispatchers.IO) {
        try {
            Files.newInputStream(path(server, profile)).use { source ->
                GZIPInputStream(source).use { Json.decodeFromStream<TvReplaySearchSnapshot>(it) }
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }

    override suspend fun write(server: LisyServer, profile: Int, snapshot: TvReplaySearchSnapshot) {
        withContext(Dispatchers.IO) {
            try {
                DesktopCache.writeAtomically(path(server, profile)) { pending ->
                    Files.newOutputStream(pending).use { target ->
                        GZIPOutputStream(target).use { Json.encodeToStream(snapshot, it) }
                    }
                }
                // Keep disk usage bounded across server changes and profiles.
                Files.list(directory()).use { files ->
                    files.filter { it.fileName.toString().endsWith(".json.gz") }
                        .sorted { first, second ->
                            Files.getLastModifiedTime(second).compareTo(Files.getLastModifiedTime(first))
                        }.skip(8).forEach { Files.deleteIfExists(it) }
                }
            } catch (_: IOException) {
                // Browsing remains available when the cache directory is not writable.
            }
        }
    }
}
