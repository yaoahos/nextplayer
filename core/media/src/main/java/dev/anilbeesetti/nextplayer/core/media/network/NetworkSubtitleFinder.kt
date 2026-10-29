package dev.anilbeesetti.nextplayer.core.media.network

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

/**
 * Finds the subtitles published next to a video on a saved network connection.
 *
 * The player must not probe for subtitles through [NetworkSessions]: that class keeps the single
 * client used for playback, so a probe competing for it can stall the stream that is starting.
 * This finder therefore opens a short-lived client of its own, asks for one directory listing and
 * disconnects again. It never throws and gives up after [FIND_TIMEOUT_MS], so a slow or missing
 * server can only cost a subtitle, never playback.
 */
@UnstableApi
@Single
class NetworkSubtitleFinder(
    private val connectionResolver: NetworkConnectionResolver,
    private val clientPool: NetworkClientPool,
) {

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

        return withTimeoutOrNull(timeMillis = FIND_TIMEOUT_MS) {
            val lease = clientPool.acquire(connection = target.connection)
                ?: return@withTimeoutOrNull emptyList()
            var reusable = true
            try {
                val listing = lease.client.listFiles(path = directory).getOrNull()
                listing
                    .orEmpty()
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
            } catch (cancellation: CancellationException) {
                reusable = false
                throw cancellation
            } catch (_: Exception) {
                reusable = false
                emptyList()
            } finally {
                withContext(NonCancellable) {
                    clientPool.release(
                        lease = lease,
                        reusable = reusable,
                    )
                }
            }
        } ?: emptyList()
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

    private companion object {
        const val FIND_TIMEOUT_MS = 2_500L
        val subtitleExtensions = listOf(
            "srt",
            "ssa",
            "ass",
            "vtt",
            "ttml",
        )
    }
}
