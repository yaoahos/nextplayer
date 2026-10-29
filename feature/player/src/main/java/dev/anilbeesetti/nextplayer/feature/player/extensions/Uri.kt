@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer.feature.player.extensions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import dev.anilbeesetti.nextplayer.core.common.extensions.convertToUTF8
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

fun Uri.getSubtitleMime(): String = when {
    path?.endsWith(suffix = ".ssa") == true || path?.endsWith(suffix = ".ass") == true -> {
        MimeTypes.TEXT_SSA
    }

    path?.endsWith(suffix = ".vtt") == true -> {
        MimeTypes.TEXT_VTT
    }

    path?.endsWith(suffix = ".ttml") == true || path?.endsWith(suffix = ".xml") == true ||
        path?.endsWith(suffix = ".dfxp") == true -> {
        MimeTypes.APPLICATION_TTML
    }

    else -> {
        MimeTypes.APPLICATION_SUBRIP
    }
}

val Uri.isSchemaContent: Boolean
    get() = ContentResolver.SCHEME_CONTENT.equals(
        other = scheme,
        ignoreCase = true,
    )

suspend fun Context.uriToSubtitleConfiguration(
    uri: Uri,
    subtitleEncoding: String = "",
    isSelected: Boolean = false,
): MediaItem.SubtitleConfiguration {
    val charset = if (subtitleEncoding.isNotEmpty() && Charset.isSupported(subtitleEncoding)) {
        Charset.forName(subtitleEncoding)
    } else {
        null
    }
    val label = getFilenameFromUri(uri = uri)
    val mimeType = uri.getSubtitleMime()
    val utf8ConvertedUri = convertToUTF8(
        uri = uri,
        charset = charset,
    )
    return MediaItem.SubtitleConfiguration.Builder(utf8ConvertedUri).apply {
        setId(uri.toString())
        setMimeType(mimeType)
        setLabel(label)
        if (isSelected) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    }.build()
}

private val remoteSubtitleExtensions = listOf(
    "srt",
    "ssa",
    "ass",
    "vtt",
    "ttml",
)

private val httpSubtitleSchemes = setOf(
    "http",
    "https",
)

private const val REMOTE_SUBTITLE_TIMEOUT_MS = 2_000

/**
 * Finds the subtitles published next to an HTTP video, mirroring [getSubtitles] for local files.
 * Candidates keep the query of the video, so credentials carried there still apply. Each candidate
 * is probed in parallel and the probe gives up after [REMOTE_SUBTITLE_TIMEOUT_MS], so a missing
 * subtitle never delays playback for long.
 */
suspend fun Uri.getRemoteSubtitles(
    excludeSubsList: List<Uri> = emptyList(),
    existingSubsList: List<Uri> = emptyList(),
): List<Uri> = withContext(Dispatchers.IO) {
    if (scheme?.lowercase() !in httpSubtitleSchemes) return@withContext emptyList()

    val baseUri = sameNameBase()
    val excludedUrls = (excludeSubsList + existingSubsList)
        .mapNotNull { it.toString().takeUnless(String::isBlank) }
        .toSet()

    coroutineScope {
        remoteSubtitleExtensions
            .map { extension -> baseUri.withExtension(extension = extension) }
            .filterNot { candidate -> candidate.toString() in excludedUrls }
            .map { candidate ->
                async { candidate.takeIf { isHttpFileAvailable(url = it.toString()) } }
            }
            .awaitAll()
            .filterNotNull()
    }
}

/**
 * The video's URI with its extension removed, so subtitle extensions can be swapped in while the
 * path, the authority and the query stay untouched.
 */
private fun Uri.sameNameBase(): Uri {
    val path = encodedPath ?: return this
    val extensionSeparatorIndex = path.lastIndexOf('.')
    val nameSeparatorIndex = path.lastIndexOf('/')
    if (extensionSeparatorIndex <= nameSeparatorIndex) return this
    return buildUpon().encodedPath(
        path.substring(
            0,
            extensionSeparatorIndex,
        ),
    ).build()
}

private fun Uri.withExtension(extension: String): Uri =
    buildUpon().encodedPath("$encodedPath.$extension").build()

private fun isHttpFileAvailable(url: String): Boolean {
    val connection = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = REMOTE_SUBTITLE_TIMEOUT_MS
            readTimeout = REMOTE_SUBTITLE_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty(
                "Range",
                "bytes=0-0",
            )
        }
    }.getOrNull() ?: return false

    return try {
        val contentType = connection.contentType.orEmpty()
        connection.responseCode in 200..299 &&
            !contentType.startsWith(prefix = "text/html") &&
            connection.inputStream.read() != -1
    } catch (e: IOException) {
        false
    } finally {
        connection.disconnect()
    }
}

@Suppress("DEPRECATION")
fun Bundle.getParcelableUriArray(key: String): ArrayList<out Parcelable>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(
            key,
            Uri::class.java,
        )
    } else {
        getParcelableArrayList(key)
    }
