package dev.anilbeesetti.nextplayer.core.media.network

import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClientPoolTest {

    private val connection = NetworkConnection(
        id = 3,
        name = "Home NAS",
        protocol = NetworkProtocol.SMB,
        host = "192.168.1.10",
        path = "Media",
        username = "guest",
    )

    @Test
    fun `reuses the client that was handed back`() = runTest {
        val factory = FakeClientFactory()
        val pool = NetworkClientPool(clientFactory = factory)

        val first = pool.acquire(connection = connection)
        assertNotNull(first)
        val client = first!!.fake()
        pool.release(lease = first)

        val second = pool.acquire(connection = connection)

        assertSame(
            client,
            second?.client,
        )
        assertEquals(
            1,
            factory.created.size,
        )
        assertEquals(
            1,
            client.connectCount,
        )
        assertEquals(
            0,
            client.disconnectCount,
        )
    }

    @Test
    fun `connects a new client when the idle one lost its connection`() = runTest {
        val factory = FakeClientFactory()
        val pool = NetworkClientPool(clientFactory = factory)

        val first = pool.acquire(connection = connection)!!
        val client = first.fake()
        client.connected = false
        pool.release(lease = first)

        val second = pool.acquire(connection = connection)

        assertEquals(
            2,
            factory.created.size,
        )
        assertNotSame(
            client,
            second?.client,
        )
        assertTrue(second!!.client.isConnected())
    }

    @Test
    fun `disconnects a client that is not reusable`() = runTest {
        val factory = FakeClientFactory()
        val pool = NetworkClientPool(clientFactory = factory)

        val lease = pool.acquire(connection = connection)!!
        pool.release(
            lease = lease,
            reusable = false,
        )

        assertEquals(
            1,
            lease.fake().disconnectCount,
        )

        val next = pool.acquire(connection = connection)
        assertNotSame(
            lease.client,
            next?.client,
        )
        assertEquals(
            2,
            factory.created.size,
        )
    }

    @Test
    fun `keeps only one idle client per connection`() = runTest {
        val factory = FakeClientFactory()
        val pool = NetworkClientPool(clientFactory = factory)

        val leases = List(size = 3) { pool.acquire(connection = connection)!! }
        leases.forEach { lease -> pool.release(lease = lease) }

        assertEquals(
            2,
            leases.count { lease -> lease.fake().disconnectCount > 0 },
        )
        assertEquals(
            3,
            factory.created.size,
        )
    }

    @Test
    fun `drops the kept client when a fresh one is requested`() = runTest {
        val factory = FakeClientFactory()
        val pool = NetworkClientPool(clientFactory = factory)

        val first = pool.acquire(connection = connection)!!
        pool.release(lease = first)
        val fresh = pool.acquire(
            connection = connection,
            fresh = true,
        )

        assertNotSame(
            first.client,
            fresh?.client,
        )
        assertEquals(
            1,
            first.fake().disconnectCount,
        )
        assertEquals(
            2,
            factory.created.size,
        )
    }

    @Test
    fun `runs one caller at a time per connection`() = runTest {
        val factory = FakeClientFactory()
        val pool = NetworkClientPool(clientFactory = factory)
        var active = 0
        var peak = 0

        coroutineScope {
            List(size = 3) {
                async {
                    pool.withClient(connection = connection) { client ->
                        active++
                        peak = maxOf(
                            peak,
                            active,
                        )
                        delay(timeMillis = 10L)
                        active--
                        client
                    }
                }
            }.awaitAll()
        }

        assertEquals(
            1,
            peak,
        )
        assertEquals(
            1,
            factory.created.size,
        )
    }

    @Test
    fun `drops the client when the work throws`() = runTest {
        val factory = FakeClientFactory()
        val pool = NetworkClientPool(clientFactory = factory)

        val result = pool.withClient<String>(connection = connection) {
            error(message = "boom")
        }

        assertNull(result)
        assertEquals(
            1,
            factory.created.single().disconnectCount,
        )
    }

    @Test
    fun `ignores a lease that is handed back twice`() = runTest {
        val factory = FakeClientFactory()
        val pool = NetworkClientPool(clientFactory = factory)

        val lease = pool.acquire(connection = connection)!!
        pool.release(lease = lease)
        pool.release(lease = lease)

        assertEquals(
            0,
            lease.fake().disconnectCount,
        )
        assertSame(
            lease.client,
            pool.acquire(connection = connection)?.client,
        )
    }

    @Test
    fun `returns null when the connection cannot be established`() = runTest {
        val pool = NetworkClientPool(
            clientFactory = NetworkClientFactory { connection ->
                FailingClient(connection = connection)
            },
        )

        assertNull(pool.acquire(connection = connection))
    }

    private fun ClientLease.fake(): FakeClient = client as FakeClient

    private class FakeClient : NetworkClient {

        override val rootPath: String = "/"

        var connected = false
        var connectCount = 0
        var disconnectCount = 0

        override suspend fun connect(): Result<Unit> {
            connectCount++
            connected = true
            return Result.success(Unit)
        }

        override suspend fun disconnect() {
            disconnectCount++
            connected = false
        }

        override fun isConnected(): Boolean = connected

        override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
            Result.success(emptyList())

        override suspend fun fileSize(path: String): Long = 0L

        override suspend fun openStream(path: String, offset: Long): InputStream =
            ByteArrayInputStream(ByteArray(size = 0))
    }

    private class FailingClient(private val connection: NetworkConnection) : NetworkClient {

        override val rootPath: String = connection.path

        override suspend fun connect(): Result<Unit> =
            Result.failure(IllegalStateException("Connection refused"))

        override suspend fun disconnect() = Unit

        override fun isConnected(): Boolean = false

        override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
            Result.failure(IllegalStateException("Not connected"))

        override suspend fun fileSize(path: String): Long = -1L

        override suspend fun openStream(path: String, offset: Long): InputStream =
            throw IllegalStateException("Not connected")
    }

    private class FakeClientFactory : NetworkClientFactory {

        val created = mutableListOf<FakeClient>()

        override fun create(connection: NetworkConnection): NetworkClient =
            FakeClient().also { client -> created += client }
    }
}
