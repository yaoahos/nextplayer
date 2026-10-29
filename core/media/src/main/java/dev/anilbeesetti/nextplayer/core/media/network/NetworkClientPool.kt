package dev.anilbeesetti.nextplayer.core.media.network

import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/**
 * Reuses connected [NetworkClient]s between thumbnail and subtitle lookups.
 *
 * Opening an SMB, FTP, SFTP, or WebDAV session takes several round trips, which on a busier server
 * costs more than the few megabytes a cover actually reads. A client is therefore kept connected
 * for a short while after it was used and handed to the next caller instead of being re-created.
 *
 * Network clients are not safe for concurrent reads, so a client is lent to exactly one caller at a
 * time. The pool hands out several clients per connection when they are requested at the same
 * moment, and those clients are reused afterwards.
 *
 * Nothing in here touches the network while [mutex] is held: connecting and disconnecting a client
 * can block for a long time on a dead socket, and holding the lock across that would stall every
 * later lookup behind it.
 */
@Single
class NetworkClientPool(private val clientFactory: NetworkClientFactory) {

    private val mutex = Mutex()
    private val idleClients = mutableMapOf<String, MutableList<IdleClient>>()

    /**
     * Returns a connected client for [connection], reusing an idle one when possible. Returns
     * `null` when the connection could not be established.
     */
    suspend fun acquire(connection: NetworkConnection): ClientLease? {
        val key = keyOf(connection = connection)
        val stale = mutableListOf<NetworkClient>()
        val idle = mutex.withLock {
            takeIdle(
                key = key,
                stale = stale,
            )
        }
        stale.forEach { client -> runCatching { client.disconnect() } }
        if (idle != null) {
            return ClientLease(
                key = key,
                client = idle,
            )
        }

        val client = clientFactory.create(connection = connection)
        val failure = runCatching { client.connect().getOrThrow() }.exceptionOrNull()
        if (failure != null) {
            runCatching { client.disconnect() }
            return null
        }
        return ClientLease(
            key = key,
            client = client,
        )
    }

    /**
     * Gives [lease] back. The client stays connected for reuse unless [reusable] is `false` or it
     * lost its connection, in which case it is disconnected.
     */
    suspend fun release(lease: ClientLease, reusable: Boolean = true) {
        if (lease.released) return
        lease.released = true

        if (!reusable || !lease.client.isConnected()) {
            runCatching { lease.client.disconnect() }
            return
        }

        val stale = mutableListOf<NetworkClient>()
        var surplus: NetworkClient? = null
        mutex.withLock {
            val now = now()
            stale += pruneExpired(now = now)
            val clients = idleClients.getOrPut(lease.key) { mutableListOf() }
            if (clients.size >= MAX_IDLE_CLIENTS) {
                surplus = lease.client
            } else {
                clients += IdleClient(
                    client = lease.client,
                    idleSince = now,
                )
            }
        }

        stale.forEach { client -> runCatching { client.disconnect() } }
        surplus?.let { client -> runCatching { client.disconnect() } }
    }

    /** Disconnects every client that is currently idle; the next [acquire] has to connect again. */
    suspend fun disconnectIdle() {
        val clients = mutex.withLock {
            val all = idleClients.values.flatten().map { idle -> idle.client }
            idleClients.clear()
            all
        }
        clients.forEach { client -> runCatching { client.disconnect() } }
    }

    private fun takeIdle(key: String, stale: MutableList<NetworkClient>): NetworkClient? {
        stale += pruneExpired(now = now())
        val clients = idleClients[key] ?: return null
        while (clients.isNotEmpty()) {
            val idle = clients.removeAt(index = clients.lastIndex)
            if (idle.client.isConnected()) return idle.client
            stale += idle.client
        }
        idleClients.remove(key)
        return null
    }

    private fun pruneExpired(now: Long): List<NetworkClient> {
        val stale = mutableListOf<NetworkClient>()
        idleClients.keys.toList().forEach { key ->
            val clients = idleClients[key] ?: return@forEach
            val expired = clients.filter { idle ->
                now - idle.idleSince > IDLE_TIMEOUT_NANOS || !idle.client.isConnected()
            }
            if (expired.isEmpty()) return@forEach

            expired.forEach { idle ->
                clients.remove(idle)
                stale += idle.client
            }
            if (clients.isEmpty()) idleClients.remove(key)
        }
        return stale
    }

    private fun keyOf(connection: NetworkConnection): String = buildString {
        append(connection.protocol)
        append(':')
        append(connection.host)
        append(':')
        append(connection.effectivePort)
        append(':')
        append(connection.username)
        append(':')
        append(connection.password.hashCode())
        append(':')
        append(connection.path)
        append(':')
        append(connection.id)
    }

    private fun now(): Long = System.nanoTime()

    private class IdleClient(val client: NetworkClient, val idleSince: Long)

    private companion object {
        /** How many clients a single connection may keep open for reuse. */
        const val MAX_IDLE_CLIENTS = 2

        /** How long a client may sit unused before it is disconnected. */
        const val IDLE_TIMEOUT_MS = 20_000L
        const val IDLE_TIMEOUT_NANOS = IDLE_TIMEOUT_MS * 1_000_000L
    }
}

/** A client lent by [NetworkClientPool]; give it back with [NetworkClientPool.release]. */
class ClientLease internal constructor(val key: String, val client: NetworkClient) {

    internal var released = false
}
