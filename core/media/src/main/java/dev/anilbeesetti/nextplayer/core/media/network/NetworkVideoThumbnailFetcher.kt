package dev.anilbeesetti.nextplayer.core.media.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

/**
 * Loads the cover frame of a video that lives on a network connection.
 *
 * The frame is decoded with [MediaMetadataRetriever] from a local copy of the file, which is what
 * the app already does for videos on the device: the retriever works on files, and feeding it a
 * remote stream through [MediaDataSource] is unreliable for containers that keep their index at the
 * end of the file. Only the first [HEADER_BYTES] and the last [TAIL_BYTES] of the video are copied
 * into a sparse temporary file, so a cover costs a bounded amount of data instead of the whole
 * video. The result is cached on disk, and Coil keeps it in memory, so a folder can be browsed
 * repeatedly without touching the server again.
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
            val frame = readCachedFrame()
                ?: withTimeoutOrNull(timeMillis = FETCH_TIMEOUT_MS) { extractFrame() }
                    ?.also { bitmap -> writeCachedFrame(bitmap = bitmap) }
                ?: throw IOException("Failed to read a thumbnail from $uri")

            return ImageFetchResult(
                image = frame.toDrawable(options.context.resources).asImage(),
                isSampled = true,
                dataSource = DataSource.NETWORK,
            )
        }

        private suspend fun extractFrame(): Bitmap? = withContext(Dispatchers.IO) {
            val target = NetworkUri.targetOf(
                uri = uri,
                connectionResolver = connectionResolver,
            ) ?: return@withContext null

            thumbnailSlots.withPermit {
                val client = clientFactory.create(connection = target.connection)
                try {
                    client.connect().getOrThrow()
                    val size = client.fileSize(path = target.filePath)
                    if (size <= 0L) return@withPermit null
                    decodeSparseCopy(
                        client = client,
                        path = target.filePath,
                        size = size,
                    ) ?: decodeOverDataSource(
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

        /**
         * Copies the head and the tail of the remote file into a sparse local file and decodes a
         * frame from it. The file keeps its real length, so offsets of an index stored at the end
         * of the video stay valid, while the untouched middle of the file is never downloaded.
         */
        private suspend fun decodeSparseCopy(
            client: NetworkClient,
            path: String,
            size: Long,
        ): Bitmap? {
            if (size > MAX_SPARSE_BYTES) return null
            val file = File.createTempFile(
                "network-thumb-",
                ".tmp",
                options.context.cacheDir,
            )
            return try {
                RandomAccessFile(
                    file,
                    "rw",
                ).use { output ->
                    output.setLength(size)
                    copyRange(
                        client = client,
                        path = path,
                        output = output,
                        offset = 0L,
                        length = minOf(
                            size,
                            HEADER_BYTES,
                        ),
                    )
                    if (size > HEADER_BYTES) {
                        val tailStart = maxOf(
                            HEADER_BYTES,
                            size - TAIL_BYTES,
                        )
                        copyRange(
                            client = client,
                            path = path,
                            output = output,
                            offset = tailStart,
                            length = size - tailStart,
                        )
                    }
                }
                decodeFile(path = file.path)
            } catch (_: Exception) {
                null
            } finally {
                file.delete()
            }
        }

        private suspend fun copyRange(
            client: NetworkClient,
            path: String,
            output: RandomAccessFile,
            offset: Long,
            length: Long,
        ) {
            if (length <= 0L) return
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            client.openStream(
                path = path,
                offset = offset,
            ).use { input ->
                output.seek(offset)
                var remaining = length
                while (remaining > 0L) {
                    val requested = minOf(
                        buffer.size.toLong(),
                        remaining,
                    ).toInt()
                    val read = input.read(
                        buffer,
                        0,
                        requested,
                    )
                    if (read <= 0) break
                    output.write(
                        buffer,
                        0,
                        read,
                    )
                    remaining -= read
                }
            }
        }

        private fun decodeFile(path: String): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(path)
                retriever.coverFrame()
            } catch (_: RuntimeException) {
                null
            } finally {
                retriever.closeSafely()
            }
        }

        private fun decodeOverDataSource(
            client: NetworkClient,
            path: String,
            size: Long,
        ): Bitmap? {
            val retriever = MediaMetadataRetriever()
            val source = NetworkMediaDataSource(
                client = client,
                path = path,
                size = size,
            )
            return try {
                retriever.setDataSource(source)
                retriever.coverFrame()
            } catch (_: RuntimeException) {
                null
            } finally {
                retriever.closeSafely()
                source.close()
            }
        }

        private fun MediaMetadataRetriever.coverFrame(): Bitmap? {
            val frame = getFrameAtTime(FRAME_TIME_US) ?: getFrameAtTime(0L)
            return frame?.scaledToFit(maxDimension = MAX_FRAME_DIMENSION)
        }

        private fun MediaMetadataRetriever.closeSafely() {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) close() else release()
            }
        }

        private fun Bitmap.scaledToFit(maxDimension: Int): Bitmap {
            val largest = maxOf(
                width,
                height,
            )
            if (largest <= maxDimension) return this
            val ratio = maxDimension.toFloat() / largest
            val scaledWidth = (width * ratio).roundToInt().coerceAtLeast(1)
            val scaledHeight = (height * ratio).roundToInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(
                this,
                scaledWidth,
                scaledHeight,
                true,
            )
        }

        private fun readCachedFrame(): Bitmap? {
            val file = cacheFile()
            if (!file.isFile) return null
            return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
        }

        private fun writeCachedFrame(bitmap: Bitmap) {
            runCatching {
                val file = cacheFile()
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        CACHE_QUALITY,
                        output,
                    )
                }
            }
            pruneCache()
        }

        private fun cacheFile(): File {
            val name = Integer.toHexString(uri.toString().hashCode()) + ".jpg"
            return File(
                File(
                    options.context.cacheDir,
                    CACHE_DIRECTORY,
                ),
                name,
            )
        }

        private fun pruneCache() {
            runCatching {
                val files = File(
                    options.context.cacheDir,
                    CACHE_DIRECTORY,
                ).listFiles() ?: return
                if (files.size <= CACHE_LIMIT) return
                files.sortedBy { file -> file.lastModified() }
                    .take(files.size - CACHE_LIMIT)
                    .forEach { file -> file.delete() }
            }
        }
    }

    private companion object {
        /** How long a cover lookup may run before it gives up. */
        const val FETCH_TIMEOUT_MS = 20_000L

        /** Position of the frame that is shown as the cover. */
        const val FRAME_TIME_US = 1_000_000L

        /** Length of the longest side of the decoded cover. */
        const val MAX_FRAME_DIMENSION = 640

        /** Number of bytes copied from the start of the video. */
        const val HEADER_BYTES = 4L * 1024L * 1024L

        /** Number of bytes copied from the end of the video. */
        const val TAIL_BYTES = 6L * 1024L * 1024L

        /** Files larger than this are decoded through the data source instead. */
        const val MAX_SPARSE_BYTES = 8L * 1024L * 1024L * 1024L

        const val COPY_BUFFER_BYTES = 256 * 1024

        const val CACHE_DIRECTORY = "network_thumbnails"

        const val CACHE_QUALITY = 85

        const val CACHE_LIMIT = 200

        /** Reading several covers at once would starve the connection they share. */
        val thumbnailSlots = Semaphore(permits = 2)
    }
}

/**
 * Exposes a range of a file on a network connection as a [MediaDataSource], for videos whose index
 * is too large to be covered by the sparse copy above.
 */
internal class NetworkMediaDataSource(
    private val client: NetworkClient,
    private val path: String,
    private val size: Long,
) : MediaDataSource(),
    Closeable {

    private var stream: InputStream? = null

    private var streamPosition: Long = 0L

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= this.size) return -1
        val source = streamAt(position = position)
        val read = source.read(
            buffer,
            offset,
            size,
        )
        if (read < 0) {
            close()
        } else {
            streamPosition += read
        }
        return read
    }

    override fun getSize(): Long = size

    @Synchronized
    override fun close() {
        stream?.let { source -> runCatching { source.close() } }
        stream = null
    }

    private fun streamAt(position: Long): InputStream {
        val current = stream
        if (current != null && streamPosition == position) return current
        close()
        val opened = runBlocking {
            client.openStream(
                path = path,
                offset = position,
            )
        }
        stream = opened
        streamPosition = position
        return opened
    }
}
