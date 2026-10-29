package dev.anilbeesetti.nextplayer.core.media.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

/**
 * Loads the cover frame of a video that lives on a network connection.
 *
 * Two sources are used, in this order, and both avoid the pitfalls of reading the video by hand:
 *
 * 1. An image that sits next to the video (`<name>.jpg`, `poster.jpg`, `folder.jpg`, …), which is
 *    what media centers such as Kodi and Nova look for first: if a library keeps its artwork in the
 *    folder, that picture is the right cover and costs a single small read.
 * 2. A frame decoded by [MediaMetadataRetriever] from [NetworkMediaServer]'s loopback URL. The
 *    retriever can seek an http URL, so it reads only the parts of the file it needs to find a
 *    keyframe, exactly like a player does, instead of downloading a fixed head and tail.
 */
@Single
class NetworkVideoThumbnailFetcher(
    private val connectionResolver: NetworkConnectionResolver,
    private val clientPool: NetworkClientPool,
    private val mediaServer: NetworkMediaServer,
) : Fetcher.Factory<CoilUri> {

    override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (!NetworkUri.isNetworkUri(uri = data.toAndroidUri())) return null
        return ThumbnailFetcher(
            uri = data.toAndroidUri(),
            options = options,
        )
    }

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

        private suspend fun extractFrame(): Bitmap? = thumbnailSlots.withPermit {
            val target = NetworkUri.targetOf(
                uri = uri,
                connectionResolver = connectionResolver,
            ) ?: return@withPermit null

            val image = readSiblingImage(target = target)
            if (image != null) {
                log(message = "cover ${target.filePath}: image next to the video")
                return@withPermit image
            }

            val frame = decodeWithRetriever(target = target)
            log(message = "cover ${target.filePath}: frame=$frame")
            frame
        }

        /**
         * Reads an artwork file published next to the video. A library that keeps `poster.jpg` or
         * `<name>.jpg` beside its videos gets its own artwork instead of a frame grabbed from the
         * middle of a fade.
         */
        private suspend fun readSiblingImage(target: NetworkUri.Target): Bitmap? {
            val directory = target.filePath.substringBeforeLast(
                delimiter = '/',
                missingDelimiterValue = "",
            )
            val videoName = target.filePath.substringAfterLast('/')
            val files = clientPool.withClient(connection = target.connection) { client ->
                client.listFiles(path = directory).getOrNull()
            } ?: return null

            val candidate = files.firstOrNull { file -> file.isArtworkFor(videoName = videoName) }
                ?: return null
            val bytes = clientPool.withClient(connection = target.connection) { client ->
                client.openStream(
                    path = candidate.path,
                    offset = 0L,
                ).use { input -> input.readAtMost(limit = MAX_IMAGE_BYTES) }
            } ?: return null
            if (bytes.isEmpty()) return null

            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
            ) ?: return null
            return bitmap.scaledToFit()
        }

        /**
         * Decodes a frame through the loopback server: the retriever asks for byte ranges and the
         * server reads exactly those from the connection, so seeking works like it does on a file.
         */
        private suspend fun decodeWithRetriever(target: NetworkUri.Target): Bitmap? {
            val length = clientPool.withClient(connection = target.connection) { client ->
                client.fileSize(path = target.filePath)
            } ?: return null
            if (length <= 0L) return null

            val url = mediaServer.open(
                connection = target.connection,
                path = target.filePath,
                length = length,
            )
            return withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(
                        url,
                        emptyMap(),
                    )
                    retriever.pickCover()
                } catch (error: Exception) {
                    log(message = "cover ${target.filePath}: retriever failed ($error)")
                    null
                } finally {
                    retriever.closeSafely()
                }
            }
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

        private fun log(message: String) {
            Log.d(
                LOG_TAG,
                message,
            )
        }
    }

    private companion object {
        /** How long a cover lookup may run before it gives up. */
        const val FETCH_TIMEOUT_MS = 15_000L

        /** Length of the longest side of the decoded cover. */
        const val MAX_FRAME_DIMENSION = 512

        /** Artwork is small; anything larger is not a poster. */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        /** Old covers were black; a new directory makes sure they are not reused. */
        const val CACHE_DIRECTORY = "network_thumbnails_v3"

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
 * Picks the frame of a video that shows something: frames are taken in the middle of the video
 * rather than at its very start, where a fade or a title card would give a black cover.
 */
private fun MediaMetadataRetriever.pickCover(): Bitmap? {
    val durationMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull()
    val durationUs = durationMs?.times(other = 1_000L)
    val candidates = buildList {
        if (durationUs != null && durationUs > TAIL_MARGIN_US) add(durationUs - 3_000_000L)
        add(3_000_000L)
        add(1_000_000L)
        add(5_000_000L)
        add(10_000_000L)
    }.filter { time -> durationUs == null || time < durationUs }

    var best: Bitmap? = null
    var bestScore = -1f
    for (time in candidates) {
        val frame = runCatching {
            getFrameAtTime(
                time,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        }.getOrNull() ?: continue
        val quality = frame.pictureQuality()
        if (quality.hasContent) {
            best?.recycle()
            return frame.scaledToFit()
        }
        if (quality.score > bestScore) {
            best?.recycle()
            best = frame
            bestScore = quality.score
        } else {
            frame.recycle()
        }
    }
    return best?.scaledToFit()
}

/** A video shorter than this has no meaningful "near the end" candidate. */
private const val TAIL_MARGIN_US = 10_000_000L

internal fun NetworkFile.isArtworkFor(videoName: String): Boolean {
    if (isDirectory) return false
    val extension = name.substringAfterLast('.').lowercase()
    if (extension !in artworkExtensions) return false
    val base = name.substringBeforeLast('.')
    return base == videoName.substringBeforeLast('.') ||
        artworkNames.any { artwork -> base.lowercase() == artwork }
}

private val artworkExtensions = setOf(
    "jpg",
    "jpeg",
    "png",
    "webp",
)

private val artworkNames = listOf(
    "poster",
    "folder",
    "cover",
    "thumb",
)

private fun Bitmap.scaledToFit(): Bitmap {
    val longest = maxOf(
        width,
        height,
    )
    if (longest <= MAX_FRAME_DIMENSION) return this
    val ratio = MAX_FRAME_DIMENSION.toFloat() / longest
    val scaled = Bitmap.createScaledBitmap(
        this,
        (width * ratio).roundToInt().coerceAtLeast(minimumValue = 1),
        (height * ratio).roundToInt().coerceAtLeast(minimumValue = 1),
        true,
    )
    if (scaled !== this) recycle()
    return scaled
}

private fun MediaMetadataRetriever.closeSafely() {
    runCatching { release() }
}

private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(size = 16 * 1024)
    val bytes = java.io.ByteArrayOutputStream()
    var remaining = limit
    while (remaining > 0) {
        val read = read(
            buffer,
            0,
            minOf(
                remaining,
                buffer.size,
            ),
        )
        if (read <= 0) break
        bytes.write(
            buffer,
            0,
            read,
        )
        remaining -= read
    }
    return bytes.toByteArray()
}

/** Length of the longest side of the decoded cover. */
private const val MAX_FRAME_DIMENSION = 512

/** Resolution the frames are sampled at to rate how much picture they hold. */
private const val SCORE_WIDTH = 32

private const val SCORE_HEIGHT = 18

/** A frame darker than this is treated as a black fade. */
private const val MIN_BRIGHTNESS = 32f

/** A frame flatter than this is treated as a blank card. */
private const val MIN_DEVIATION = 10f

private const val SCORE_DEVIATION_WEIGHT = 3f

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
