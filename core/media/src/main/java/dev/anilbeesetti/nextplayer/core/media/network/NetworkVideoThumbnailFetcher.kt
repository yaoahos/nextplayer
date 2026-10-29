package dev.anilbeesetti.nextplayer.core.media.network

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toDrawable
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

/**
 * Decodes the first frame of a video reached over a saved network connection, so the browse screen
 * can show a cover instead of a generic icon.
 *
 * Coil has no fetcher for the `smb`/`ftp`/`sftp`/`webdav` schemes, and the player's own
 * [NetworkSessions] client is busy with playback, so this fetcher opens a short-lived client of its
 * own for every thumbnail. Remote bytes are handed to [MediaMetadataRetriever] through
 * [NetworkMediaDataSource], which reopens the stream when the retriever seeks; [thumbnailSlots]
 * keeps a grid full of covers from hammering the server. Any failure surfaces as a normal Coil
 * error, so the caller falls back to its placeholder.
 */
@Single
class NetworkVideoThumbnailFetcher(
    private val connectionResolver: NetworkConnectionResolver,
    private val clientFactory: NetworkClientFactory,
) : Fetcher.Factory<Uri> {

    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
        if (NetworkUri.isNetworkUri(uri = data)) {
            ThumbnailFetcher(
                uri = data,
                options = options,
            )
        } else {
            null
        }

    private inner class ThumbnailFetcher(private val uri: Uri, private val options: Options) :
        Fetcher {

        override suspend fun fetch(): FetchResult {
            val frame = withTimeoutOrNull(timeMillis = FETCH_TIMEOUT_MS) {
                extractFrame(uri = uri)
            } ?: throw IOException("Failed to read a thumbnail from $uri")

            return ImageFetchResult(
                image = frame.toDrawable(options.context.resources).asImage(),
                isSampled = true,
                dataSource = DataSource.NETWORK,
            )
        }
    }

    private suspend fun extractFrame(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val target = NetworkUri.targetOf(
            uri = uri,
            connectionResolver = connectionResolver,
        )
            ?: return@withContext null

        thumbnailSlots.withPermit {
            val client = clientFactory.create(connection = target.connection)
            try {
                client.connect().getOrThrow()
                val size = client.fileSize(path = target.filePath)
                if (size <= 0L) return@withPermit null
                readFrame(
                    client = client,
                    path = target.filePath,
                    size = size,
                )
            } catch (_: Exception) {
                null
            } finally {
                runCatching { client.disconnect() }
            }
        }
    }

    private fun readFrame(client: NetworkClient, path: String, size: Long): Bitmap? {
        val source = NetworkMediaDataSource(
            client = client,
            path = path,
            size = size,
        )
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source)
            // A second into the video skips black lead-ins; the very first frame is the fallback.
            val frame = retriever.getFrameAtTime(FRAME_TIME_US) ?: retriever.getFrameAtTime(0L)
            frame?.scaledToFit(maxDimension = MAX_FRAME_DIMENSION)
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    retriever.close()
                } else {
                    retriever.release()
                }
            }
            runCatching { source.close() }
        }
    }

    /** A full-size frame is wasted on a thumbnail; keep the cover small but sharp. */
    private fun Bitmap.scaledToFit(maxDimension: Int): Bitmap {
        val largest = maxOf(
            width,
            height,
        )
        if (largest <= maxDimension) return this
        val scale = maxDimension.toFloat() / largest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt(),
            (height * scale).roundToInt(),
            true,
        )
    }

    private companion object {
        const val FETCH_TIMEOUT_MS = 15_000L
        const val FRAME_TIME_US = 1_000_000L
        const val MAX_FRAME_DIMENSION = 640

        /** Limits how many covers are extracted at once; a visible grid asks for a handful. */
        val thumbnailSlots = Semaphore(permits = 2)
    }
}

/**
 * Exposes a remote video to [MediaMetadataRetriever], which only understands a seekable byte source.
 * Reads are sequential in practice, so the stream is kept until the retriever jumps elsewhere.
 */
internal class NetworkMediaDataSource(
    private val client: NetworkClient,
    private val path: String,
    private val size: Long,
) : MediaDataSource(),
    Closeable {

    private var stream: InputStream? = null
    private var streamPosition = 0L

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= this.size) return -1
        val length = minOf(
            size.toLong(),
            this.size - position,
        ).toInt()
        if (length <= 0) return -1
        val input = streamAt(position = position) ?: return -1
        val read = runCatching {
            input.read(
                buffer,
                offset,
                length,
            )
        }.getOrDefault(-1)
        if (read > 0) streamPosition = position + read
        return read
    }

    @Synchronized
    override fun getSize(): Long = size

    @Synchronized
    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }

    private fun streamAt(position: Long): InputStream? {
        val current = stream
        if (current != null && streamPosition == position) return current
        runCatching { current?.close() }
        stream = null
        val reopened = runCatching {
            runBlocking {
                client.openStream(
                    path = path,
                    offset = position,
                )
            }
        }.getOrNull() ?: return null
        stream = reopened
        streamPosition = position
        return reopened
    }
}
