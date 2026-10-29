package dev.anilbeesetti.nextplayer.core.media.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.graphics.drawable.toDrawable
import coil3.ImageLoader
import coil3.Uri as CoilUri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * remote stream is unreliable for containers that keep their index at the end of the file. The
 * copy grows through [HEAD_STAGES] and ends with [TAIL_BYTES] at the end of the file, so a cover
 * costs a bounded amount of data instead of the whole video.
 *
 * The cover is not simply the frame at a fixed second: the first seconds of a video are often a
 * black fade or a dark title card, so several frames are decoded and the first one with actual
 * picture content wins. The result is cached on disk, and Coil keeps it in memory, so a folder can
 * be browsed repeatedly without touching the server again.
 */
@Single
class NetworkVideoThumbnailFetcher(
    private val connectionResolver: NetworkConnectionResolver,
    private val clientPool: NetworkClientPool,
) : Fetcher.Factory<CoilUri> {

    override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val uri = data.toAndroidUri()
        return if (NetworkUri.isNetworkUri(uri = uri)) {
            ThumbnailFetcher(
                uri = uri,
                options = options,
            )
        } else {
            null
        }
    }

    private class SparseResult(val frame: Bitmap?, val bytesCopied: Long)

    private inner class ThumbnailFetcher(private val uri: Uri, private val options: Options) :
        Fetcher {

        override suspend fun fetch(): FetchResult {
            if (isRecentlyFailed()) throw IOException("Retrying $uri later")
            val frame = readCachedFrame()
                ?: withTimeoutOrNull(timeMillis = FETCH_TIMEOUT_MS) { extractFrame() }
                    ?.also { bitmap -> writeCachedFrame(bitmap = bitmap) }
            if (frame == null) {
                rememberFailure()
                throw IOException("Failed to read a thumbnail from $uri")
            }

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
                val first = attemptRead(
                    connection = target.connection,
                    path = target.filePath,
                    fresh = false,
                )
                logAttempt(
                    path = target.filePath,
                    result = first,
                )
                if (first.frame != null) return@withPermit first.frame

                // Bytes were read but nothing could be decoded: that is about the file, not the
                // session, so a retry would only download the same range again.
                if (first.bytesCopied > 0L) return@withPermit null

                // Nothing could be read at all, which usually means the kept session died. One
                // retry on a fresh connection keeps a dropped session from hiding a cover.
                val retry = attemptRead(
                    connection = target.connection,
                    path = target.filePath,
                    fresh = true,
                )
                logAttempt(
                    path = target.filePath,
                    result = retry,
                )
                retry.frame
            }
        }

        private suspend fun attemptRead(
            connection: NetworkConnection,
            path: String,
            fresh: Boolean,
        ): SparseResult = clientPool.withClient(
            connection = connection,
            fresh = fresh,
        ) { client ->
            val size = client.fileSize(path = path)
            if (size <= 0L) {
                SparseResult(
                    frame = null,
                    bytesCopied = 0L,
                )
            } else {
                decodeSparseCopy(
                    client = client,
                    path = path,
                    size = size,
                )
            }
        } ?: SparseResult(
            frame = null,
            bytesCopied = 0L,
        )

        private fun logAttempt(path: String, result: SparseResult) {
            Log.d(
                LOG_TAG,
                "cover $path: ${result.bytesCopied} bytes read, frame=${result.frame != null}",
            )
        }

        /**
         * Copies the start of the remote file into a sparse local file and decodes a frame from it.
         * The file keeps its real length, so offsets stay valid and the middle of the file is never
         * downloaded.
         *
         * The head grows in stages instead of being copied in one fixed size: a file with a low
         * bitrate yields a frame from the first few megabytes, while a high bitrate file only holds
         * a keyframe once more of its opening seconds were read. The tail is fetched once the first
         * stage did not produce a frame, because containers such as MKV and non-faststart MP4 keep
         * their index at the end and cannot be opened without it.
         */
        private suspend fun decodeSparseCopy(
            client: NetworkClient,
            path: String,
            size: Long,
        ): SparseResult {
            if (size > MAX_SPARSE_BYTES) {
                return SparseResult(
                    frame = null,
                    bytesCopied = 0L,
                )
            }
            val file = File.createTempFile(
                "network-thumb-",
                ".tmp",
                options.context.cacheDir,
            )
            var copied = 0L
            return try {
                RandomAccessFile(
                    file,
                    "rw",
                ).use { output ->
                    output.setLength(size)
                    var hasTail = false
                    for (stage in HEAD_STAGES) {
                        val target = minOf(
                            size,
                            stage,
                        )
                        if (target > copied) {
                            copyRange(
                                client = client,
                                path = path,
                                output = output,
                                offset = copied,
                                length = target - copied,
                            )
                            copied = target
                        }

                        decodeFile(
                            path = file.path,
                            hasTail = hasTail,
                        )?.let { frame ->
                            return SparseResult(
                                frame = frame,
                                bytesCopied = copied,
                            )
                        }

                        if (!hasTail) {
                            val tailStart = maxOf(
                                copied,
                                size - TAIL_BYTES,
                            )
                            if (tailStart < size) {
                                copyRange(
                                    client = client,
                                    path = path,
                                    output = output,
                                    offset = tailStart,
                                    length = size - tailStart,
                                )
                                copied += size - tailStart
                                hasTail = true
                                decodeFile(
                                    path = file.path,
                                    hasTail = true,
                                )?.let { frame ->
                                    return SparseResult(
                                        frame = frame,
                                        bytesCopied = copied,
                                    )
                                }
                            }
                        }
                    }
                    SparseResult(
                        frame = null,
                        bytesCopied = copied,
                    )
                }
            } catch (_: Exception) {
                SparseResult(
                    frame = null,
                    bytesCopied = copied,
                )
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

        private fun decodeFile(path: String, hasTail: Boolean): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(path)
                retriever.pickCover(hasTail = hasTail)
            } catch (_: RuntimeException) {
                null
            } finally {
                retriever.closeSafely()
            }
        }

        /**
         * Decodes a few frames and returns the first one that holds actual picture content, or the
         * brightest one when every candidate is dark: covers taken at one fixed second are often a
         * black fade or a title card.
         *
         * The candidate list stays short on purpose. Every decoded frame costs a seek in the
         * retriever, and a folder is full of videos, so a cover that needs five attempts is worse
         * than a cover that is a second later than ideal.
         */
        private fun MediaMetadataRetriever.pickCover(hasTail: Boolean): Bitmap? {
            val durationUs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { seconds -> seconds * 1_000L }
            val candidates = buildList {
                if (hasTail && durationUs != null && durationUs > TAIL_MARGIN_US) {
                    // The tail holds the end of the video, where titles are usually over.
                    add(durationUs - 3_000_000L)
                }
                add(3_000_000L)
                add(1_000_000L)
                add(5_000_000L)
            }

            var best: Pair<Bitmap, Float>? = null
            for (timeUs in candidates) {
                if (timeUs < 0L) continue
                val frame = runCatching {
                    getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }
                    .getOrNull() ?: continue
                val quality = frame.pictureQuality()
                if (quality.hasContent) {
                    return frame.scaledToFit(maxDimension = MAX_FRAME_DIMENSION)
                }
                val current = best
                if (current == null || quality.score > current.second) {
                    best = frame to quality.score
                }
            }
            return best?.first?.scaledToFit(maxDimension = MAX_FRAME_DIMENSION)
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

        private fun isRecentlyFailed(): Boolean {
            val key = uri.toString()
            val failedAt = failedCovers[key] ?: return false
            if (System.currentTimeMillis() - failedAt < NEGATIVE_CACHE_MS) return true
            failedCovers.remove(key)
            return false
        }

        private fun rememberFailure() {
            if (failedCovers.size >= NEGATIVE_CACHE_LIMIT) failedCovers.clear()
            failedCovers[uri.toString()] = System.currentTimeMillis()
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
        const val FETCH_TIMEOUT_MS = 15_000L

        /** Length of the longest side of the decoded cover. */
        const val MAX_FRAME_DIMENSION = 512

        /**
         * How much of the start of a video is copied, stage by stage. A later stage is only reached
         * when the frames of the earlier one could not be decoded.
         */
        val HEAD_STAGES = listOf(
            4L * 1024L * 1024L,
            8L * 1024L * 1024L,
            16L * 1024L * 1024L,
            32L * 1024L * 1024L,
        )

        /** Number of bytes copied from the end of the video, only when the head is not enough. */
        const val TAIL_BYTES = 4L * 1024L * 1024L

        /** The local copy is sparse, so even a large remux only costs the bytes that are read. */
        const val MAX_SPARSE_BYTES = 64L * 1024L * 1024L * 1024L

        /** A video shorter than this has no meaningful "near the end" candidate. */
        const val TAIL_MARGIN_US = 10_000_000L

        const val COPY_BUFFER_BYTES = 256 * 1024

        /** Old covers were black; a new directory makes sure they are not reused. */
        const val CACHE_DIRECTORY = "network_thumbnails_v2"

        /** A cover that just failed is not retried on every scroll past it. */
        const val NEGATIVE_CACHE_MS = 120_000L

        const val NEGATIVE_CACHE_LIMIT = 500

        val failedCovers = ConcurrentHashMap<String, Long>()

        /** Logcat tag of the cover pipeline; useful when a server keeps refusing reads. */
        const val LOG_TAG = "NetworkCover"

        const val CACHE_QUALITY = 85

        const val CACHE_LIMIT = 200

        /** Reading several covers at once would starve the connection they share. */
        val thumbnailSlots = Semaphore(permits = 2)
    }
}

/**
 * Rates how much of a picture a frame holds: a black fade has a low mean brightness, and a flat
 * card has a low deviation, so both score near zero.
 */
internal fun Bitmap.pictureQuality(): FrameQuality {
    val sample = Bitmap.createScaledBitmap(
        this,
        SCORE_WIDTH,
        SCORE_HEIGHT,
        true,
    )
    val pixels = IntArray(SCORE_WIDTH * SCORE_HEIGHT)
    try {
        sample.getPixels(
            pixels,
            0,
            SCORE_WIDTH,
            0,
            0,
            SCORE_WIDTH,
            SCORE_HEIGHT,
        )
    } finally {
        if (sample !== this) sample.recycle()
    }

    var sum = 0.0
    var sumOfSquares = 0.0
    for (pixel in pixels) {
        val red = pixel shr 16 and 0xFF
        val green = pixel shr 8 and 0xFF
        val blue = pixel and 0xFF
        val luma = 0.299 * red + 0.587 * green + 0.114 * blue
        sum += luma
        sumOfSquares += luma * luma
    }
    val count = pixels.size.toDouble()
    val mean = sum / count
    val variance = (sumOfSquares / count - mean * mean).coerceAtLeast(minimumValue = 0.0)
    val deviation = sqrt(variance).toFloat()
    val brightness = mean.toFloat()
    return FrameQuality(
        score = brightness + SCORE_DEVIATION_WEIGHT * deviation,
        hasContent = brightness >= MIN_BRIGHTNESS && deviation >= MIN_DEVIATION,
    )
}

internal class FrameQuality(val score: Float, val hasContent: Boolean)

/** Resolution the frames are sampled at to rate how much picture they hold. */
private const val SCORE_WIDTH = 32

private const val SCORE_HEIGHT = 18

/** A frame darker than this is treated as a black fade. */
private const val MIN_BRIGHTNESS = 32f

/** A frame flatter than this is treated as a blank card. */
private const val MIN_DEVIATION = 10f

private const val SCORE_DEVIATION_WEIGHT = 3f
