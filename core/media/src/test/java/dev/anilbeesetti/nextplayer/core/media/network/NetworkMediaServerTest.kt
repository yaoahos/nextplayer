package dev.anilbeesetti.nextplayer.core.media.network

import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMediaServerTest {

    private val connection = NetworkConnection(
        id = 1,
        name = "Test",
        protocol = NetworkProtocol.SMB,
        host = "127.0.0.1",
    )

    private val payload = ByteArray(size = 1_024) { index -> (index % 251).toByte() }

    private fun server(): NetworkMediaServer {
        val client = object : NetworkClient {
            override val rootPath: String = ""
            override suspend fun connect(): Result<Unit> = Result.success(Unit)
            override suspend fun disconnect() = Unit
            override fun isConnected(): Boolean = true
            override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
                Result.success(emptyList())

            override suspend fun fileSize(path: String): Long = payload.size.toLong()
            override suspend fun openStream(path: String, offset: Long): InputStream =
                ByteArrayInputStream(
                    payload,
                    offset.toInt(),
                    payload.size - offset.toInt(),
                )
        }
        val pool = NetworkClientPool(
            clientFactory = NetworkClientFactory { client },
        )
        return NetworkMediaServer(clientPool = pool)
    }

    private fun open(
        server: NetworkMediaServer,
        range: String? = null,
        head: Boolean = false,
    ): HttpURLConnection {
        val url = URL(
            server.open(
                connection = connection,
                path = "video.mkv",
                length = payload.size.toLong(),
            ),
        )
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = if (head) "HEAD" else "GET"
            if (range != null) {
                setRequestProperty(
                    "Range",
                    range,
                )
            }
            connectTimeout = 5_000
            readTimeout = 5_000
        }
    }

    @Test
    fun servesTheWholeFile() {
        val requested = open(server = server())

        assertEquals(
            200,
            requested.responseCode,
        )
        assertEquals(
            payload.size.toString(),
            requested.getHeaderField("Content-Length"),
        )
        assertArrayEquals(
            payload,
            requested.inputStream.readBytes(),
        )
    }

    @Test
    fun servesARange() {
        val requested = open(
            server = server(),
            range = "bytes=100-199",
        )

        assertEquals(
            206,
            requested.responseCode,
        )
        assertEquals(
            "bytes 100-199/${payload.size}",
            requested.getHeaderField("Content-Range"),
        )
        assertArrayEquals(
            payload.copyOfRange(
                100,
                200,
            ),
            requested.inputStream.readBytes(),
        )
    }

    @Test
    fun servesAnOpenEndedRange() {
        val requested = open(
            server = server(),
            range = "bytes=1000-",
        )

        assertEquals(
            206,
            requested.responseCode,
        )
        assertArrayEquals(
            payload.copyOfRange(
                1_000,
                payload.size,
            ),
            requested.inputStream.readBytes(),
        )
    }

    @Test
    fun answersHeadWithTheLengthOnly() {
        val requested = open(
            server = server(),
            head = true,
        )

        assertEquals(
            200,
            requested.responseCode,
        )
        assertEquals(
            payload.size.toString(),
            requested.getHeaderField("Content-Length"),
        )
        assertTrue(requested.inputStream.readBytes().isEmpty())
    }

    @Test
    fun refusesAnUnknownToken() {
        val requested = open(server = server())
        val unknown = (
            URL(
                "${requested.url.toString().substringBeforeLast('/')}/missing",
            ).openConnection() as HttpURLConnection
            )

        assertEquals(
            404,
            unknown.responseCode,
        )
    }
}
