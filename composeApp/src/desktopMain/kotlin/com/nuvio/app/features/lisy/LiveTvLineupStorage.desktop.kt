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

internal actual fun platformLineupStorage(): LiveTvLineupStorage? =
    DesktopLineupStorage { DesktopStorage.cacheDir.resolve("live-tv-v1") }

@OptIn(ExperimentalSerializationApi::class)
internal class DesktopLineupStorage(private val directory: () -> Path) : LiveTvLineupStorage {
    private fun path(server: LisyServer, profile: Int): Path {
        val connection = server.url.trimEnd('/') + "\u0000" + server.mac.uppercase()
        val hash = MessageDigest.getInstance("SHA-256").digest(connection.toByteArray()).toHexString()
        return directory().resolve("${profile}-${hash}.json.gz")
    }

    override suspend fun read(server: LisyServer, profile: Int): LiveTvLineupSnapshot? = withContext(Dispatchers.IO) {
        try {
            Files.newInputStream(path(server, profile)).use { source ->
                GZIPInputStream(source).use { Json.decodeFromStream<LiveTvLineupSnapshot>(it) }
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }

    override suspend fun write(server: LisyServer, profile: Int, snapshot: LiveTvLineupSnapshot) {
        withContext(Dispatchers.IO) {
            try {
                DesktopCache.writeAtomically(path(server, profile)) { pending ->
                    Files.newOutputStream(pending).use { target ->
                        GZIPOutputStream(target).use { Json.encodeToStream(snapshot, it) }
                    }
                }
            } catch (_: IOException) {
                // A full or read-only cache must not prevent browsing a fresh lineup.
            }
        }
    }
}
