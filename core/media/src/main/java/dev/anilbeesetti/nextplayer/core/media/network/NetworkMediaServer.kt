package dev.anilbeesetti.nextplayer.core.media.network

import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

/**
 * Serves a file from a network connection over a loopback HTTP server.
 *
 * Media components only understand files and URLs: `MediaMetadataRetriever`, for example, can read
 * and seek a local file or an http URL, but has no idea what an `smb://` URI is. This server bridges
 * the two, the same way desktop players hand their demuxer a stream instead of a raw protocol: the
 * component asks for a byte range, and the range is read from the connection.
 *
 * Range requests make the file seekable, so the component can jump to whatever position it needs
 * instead of downloading the file from the start. Only the bytes that are actually asked for are
 * read, and everything goes through [NetworkClientPool], so a cover never opens a session of its
 * own and never competes with playback.
 */
@Single
class NetworkMediaServer(private val clientPool: NetworkClientPool) {

    private val targets = ConcurrentHashMap<String, Target>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serverSocket: ServerSocket? = null

    /**
     * Makes [path] on [connection] readable as an http URL of [length] bytes and returns that URL.
     * The URL stays valid for [TARGET_LIFETIME_MS].
     */
    fun open(connection: NetworkConnection, path: String, length: Long): String {
        val server = ensureStarted()
        pruneTargets()
        val token = UUID.randomUUID().toString().replace(
            oldValue = "-",
            newValue = "",
        )
        targets[token] = Target(
            connection = connection,
            path = path,
            length = length,
            createdAt = System.currentTimeMillis(),
        )
        return "http://127.0.0.1:${server.localPort}/media/$token"
    }

    private fun ensureStarted(): ServerSocket {
        serverSocket?.takeIf { socket -> !socket.isClosed }?.let { return it }
        synchronized(lock = this) {
            serverSocket?.takeIf { socket -> !socket.isClosed }?.let { return it }
            // Port 0 lets the system pick a free port; the socket is bound to the loopback address
            // so nothing outside the device can reach it.
            val socket = ServerSocket(
                0,
                BACKLOG,
                InetAddress.getLoopbackAddress(),
            )
            serverSocket = socket
            scope.launch { acceptRequests(server = socket) }
            return socket
        }
    }

    private suspend fun acceptRequests(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            scope.launch { handle(socket = socket) }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val request = readRequest(input = input) ?: return
            val target = targets[request.token]
            if (target == null) {
                writeHead(
                    output = output,
                    code = 404,
                    reason = "Not Found",
                    headers = emptyList(),
                )
                return
            }

            val start = request.rangeStart ?: 0L
            val end = minOf(
                request.rangeEnd ?: (target.length - 1L),
                target.length - 1L,
            )
            val length = end - start + 1L
            if (start > end || start >= target.length) {
                writeHead(
                    output = output,
                    code = 416,
                    reason = "Range Not Satisfiable",
                    headers = listOf("Content-Range: bytes */${target.length}"),
                )
                return
            }

            val headers = buildList {
                add("Content-Type: ${mimeTypeOf(name = target.path)}")
                add("Accept-Ranges: bytes")
                add("Content-Length: $length")
                add("Connection: close")
                if (request.rangeStart != null || request.rangeEnd != null) {
                    add("Content-Range: bytes $start-$end/${target.length}")
                }
            }
            writeHead(
                output = output,
                code = if (headers.any { header -> header.startsWith(prefix = "Content-Range") }) {
                    206
                } else {
                    200
                },
                reason = if (headers.any { header ->
                        header.startsWith(prefix = "Content-Range")
                    }
                ) {
                    "Partial Content"
                } else {
                    "OK"
                },
                headers = headers,
            )
            if (!request.head) {
                copyRange(
                    target = target,
                    start = start,
                    length = length,
                    output = output,
                )
            }
        }
    }

    private suspend fun copyRange(
        target: Target,
        start: Long,
        length: Long,
        output: OutputStream,
    ) {
        withTimeoutOrNull(timeMillis = COPY_TIMEOUT_MS) {
            clientPool.withClient(connection = target.connection) { client ->
                client.openStream(
                    path = target.path,
                    offset = start,
                ).use { input ->
                    copy(
                        input = input,
                        output = output,
                        length = length,
                    )
                }
            }
        }
    }

    private fun copy(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(size = COPY_BUFFER_BYTES)
        var remaining = length
        while (remaining > 0L) {
            val requested = minOf(
                remaining,
                buffer.size.toLong(),
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
        output.flush()
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input = input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts[1]
        var rangeStart: Long? = null
        var rangeEnd: Long? = null
        while (true) {
            val header = readLine(input = input) ?: break
            if (header.isEmpty()) break
            if (!header.startsWith(
                    prefix = "Range:",
                    ignoreCase = true,
                )
            ) {
                continue
            }
            val value = header.substringAfter(delimiter = "Range:").trim()
            val spec = value.substringAfter(
                delimiter = "bytes=",
                missingDelimiterValue = "",
            ).trim()
            if (spec.isEmpty()) continue
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter(
                '-',
                missingDelimiterValue = "",
            ).trim()
            rangeStart = from.toLongOrNull() ?: 0L
            rangeEnd = to.toLongOrNull()
        }
        val token = path.trim('/')
            .substringAfter(
                delimiter = "media/",
                missingDelimiterValue = "",
            )
            .substringBefore(delimiter = '/')
        return Request(
            token = token,
            head = method == "HEAD",
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
        )
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return builder.toString().takeIf { it.isNotEmpty() }
            if (next ==
                '\n'.code
            ) {
                return builder.toString().trimEnd(Char::isWhitespace).takeIf { true }
            }
            if (next != '\r'.code) builder.append(next.toChar())
        }
    }

    private fun writeHead(output: OutputStream, code: Int, reason: String, headers: List<String>) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        headers.forEach { header -> builder.append(header).append("\r\n") }
        builder.append("\r\n")
        output.write(builder.toString().toByteArray())
        output.flush()
    }

    private fun pruneTargets() {
        val threshold = System.currentTimeMillis() - TARGET_LIFETIME_MS
        targets.entries.removeIf { entry -> entry.value.createdAt < threshold }
    }

    private fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "3gp" -> "video/3gpp"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        else -> "application/octet-stream"
    }

    private data class Target(
        val connection: NetworkConnection,
        val path: String,
        val length: Long,
        val createdAt: Long,
    )

    private data class Request(
        val token: String,
        val head: Boolean,
        val rangeStart: Long?,
        val rangeEnd: Long?,
    )

    private companion object {
        const val BACKLOG = 8

        /** A URL is only handed out for one cover lookup. */
        const val TARGET_LIFETIME_MS = 300_000L

        /** A stalled read must not keep a request open forever. */
        const val COPY_TIMEOUT_MS = 60_000L

        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
