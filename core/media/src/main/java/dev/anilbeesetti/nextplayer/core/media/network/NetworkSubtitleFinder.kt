package dev.anilbeesetti.nextplayer.core.media.network

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

/**
 * Finds the subtitles published next to a video on a saved network connection.
 *
 * The player must not probe for subtitles through [NetworkSessions]: that class keeps the single
 * client used for playback, so a probe competing for it can stall the stream that is starting.
 * This finder therefore borrows a client from the pool, asks for one directory listing and gives it
 * back. It never throws and gives up after [FIND_TIMEOUT_MS], so a slow or missing server can only
 * cost a subtitle, never playback.
 *
 * Playback waits for this lookup, so a listing is kept for [LISTING_TTL_MS]: opening several videos
 * from the same folder costs one listing instead of one per video.
 */
@UnstableApi
@Single
class NetworkSubtitleFinder(
    private val connectionResolver: NetworkConnectionResolver,
    private val clientPool: NetworkClientPool,
) {

    private val listings = mutableMapOf<String, CachedListing>()

    suspend fun findSameNameSubtitles(uri: Uri, excludeSubs: List<Uri> = emptyList()): List<Uri> {
        if (!NetworkUri.isNetworkUri(uri)) return emptyList()
        val target = NetworkUri.targetOf(
            uri = uri,
            connectionResolver = connectionResolver,
        ) ?: return emptyList()

        val fileName = target.filePath.substringAfterLast(delimiter = '/')
        val baseName = fileName.substringBeforeLast(
            delimiter = '.',
            missingDelimiterValue = fileName,
        )
        if (baseName.isEmpty()) return emptyList()
        val directory = target.filePath.substringBeforeLast(
            delimiter = '/',
            missingDelimiterValue = "",
        )
        val excluded = excludeSubs
            .mapNotNull { subtitle -> subtitle.toString().takeUnless(String::isBlank) }
            .toSet()

        val files = listingOf(
            connection = target.connection,
            directory = directory,
        ) ?: return emptyList()

        return files
            .filter { file -> file.isSubtitleFor(videoName = baseName) }
            .sortedBy { file -> subtitleExtensions.indexOf(file.extension()) }
            .mapNotNull { file ->
                val subtitleUri = NetworkUri.build(
                    connection = target.connection,
                    filePath = joinPath(
                        directory = directory,
                        name = file.name,
                    ),
                )
                subtitleUri.takeUnless { it.toString() in excluded }
            }
    }

    /**
     * Remembers a listing the caller already fetched (the browser lists a folder before anything is
     * played), so opening a video from that folder does not list the directory a second time.
     */
    fun rememberListing(
        connection: NetworkConnection,
        directory: String,
        files: List<NetworkFile>,
    ) {
        storeListing(
            key = keyOf(
                connection = connection,
                directory = directory,
            ),
            files = files,
        )
    }

    private fun keyOf(connection: NetworkConnection, directory: String): String =
        "${connection.id}|${connection.host}|${connection.path}|$directory"

    private fun storeListing(key: String, files: List<NetworkFile>) {
        synchronized(lock = listings) {
            if (listings.size >= LISTING_LIMIT) listings.clear()
            listings[key] = CachedListing(
                files = files,
                fetchedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun listingOf(
        connection: NetworkConnection,
        directory: String,
    ): List<NetworkFile>? {
        val key = keyOf(
            connection = connection,
            directory = directory,
        )
        val now = System.currentTimeMillis()
        synchronized(lock = listings) {
            val cached = listings[key]
            if (cached != null) {
                if (now - cached.fetchedAt < LISTING_TTL_MS) return cached.files
                listings.remove(key)
            }
        }

        val files = withTimeoutOrNull(timeMillis = FIND_TIMEOUT_MS) {
            fetchListing(
                connection = connection,
                directory = directory,
            )
        } ?: return null

        storeListing(
            key = key,
            files = files,
        )
        return files
    }

    private suspend fun fetchListing(
        connection: NetworkConnection,
        directory: String,
    ): List<NetworkFile>? = clientPool.withClient(
        connection = connection,
    ) { client ->
        client.listFiles(path = directory).getOrNull()
    }

    private fun joinPath(directory: String, name: String): String = when {
        directory.isEmpty() -> name
        directory.endsWith(char = '/') -> directory + name
        else -> "$directory/$name"
    }

    /**
     * The video itself shares its base name with the subtitles, so only files that carry a
     * subtitle extension are accepted; probing the video as a subtitle would also make the player
     * open the stream it is about to play.
     */
    private fun NetworkFile.isSubtitleFor(videoName: String): Boolean =
        !isDirectory && baseName() == videoName && extension() in subtitleExtensions

    private fun NetworkFile.extension(): String = name.substringAfterLast(
        delimiter = '.',
        missingDelimiterValue = "",
    ).lowercase()

    private fun NetworkFile.baseName(): String = name.substringBeforeLast(delimiter = '.')

    private class CachedListing(val files: List<NetworkFile>, val fetchedAt: Long)

    private companion object {
        const val FIND_TIMEOUT_MS = 1_500L

        /** How long a directory listing is reused before it is fetched again. */
        const val LISTING_TTL_MS = 30_000L

        const val LISTING_LIMIT = 32

        val subtitleExtensions = listOf(
            "srt",
            "ssa",
            "ass",
            "vtt",
            "ttml",
        )
    }
}
