package io.github.sunsind.sunlith.device

import android.content.Context
import android.os.PowerManager
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * One HTTPS request per connection: POST /v1/tools with Content-Length, then Connection: close.
 * Binds 0.0.0.0 but admits only connections that arrived on Wi-Fi, Ethernet, VPN or loopback addresses from private IPv4 peers.
 */
internal class DeviceLinkServer(
    context: Context,
    private val port: Int = DeviceLink.PORT,
    private val tools: DeviceLinkTools = DeviceLinkTools(context),
    private val limiter: DeviceLinkRateLimiter = DeviceLinkRateLimiter(),
) {
    private val app = context.applicationContext
    private val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8), threads("worker"))
        .apply { allowCoreThreadTimeOut(true) }
    private val watchdog = ScheduledThreadPoolExecutor(1, threads("watchdog")).apply { removeOnCancelPolicy = true }
    @Volatile private var serverSocket: SSLServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<SSLSocket>()

    val localPort: Int get() = serverSocket?.localPort ?: -1

    fun start() {
        val socket = DeviceLinkTls.sslContext().serverSocketFactory
            .createServerSocket(port, 16, Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0))) as SSLServerSocket
        try {
            socket.enabledProtocols = DeviceLinkTls.protocols(socket.supportedProtocols)
            socket.needClientAuth = false
            socket.wantClientAuth = false
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
        serverSocket = socket
        Thread({ acceptLoop(socket) }, "sunlith-link-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.toList().forEach(::close)
        workers.shutdownNow()
        watchdog.shutdownNow()
    }

    private fun acceptLoop(server: SSLServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept() as SSLSocket
            } catch (_: IOException) {
                if (server.isClosed) return
                Thread.sleep(100)
                continue
            }
            clients.add(client)
            if (server.isClosed) {
                close(client)
                return
            }
            val remote = client.inetAddress
            if (!DeviceLinkNetwork.admits(app, client.localAddress, remote) || !limiter.admitted(remote.hostAddress.orEmpty())) {
                close(client)
                continue
            }
            try {
                workers.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                close(client)
            }
        }
    }

    private fun handle(socket: SSLSocket) {
        val remote = socket.inetAddress.hostAddress.orEmpty()
        val wakeLock = runCatching {
            app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sunlith:device-link")
                .apply { setReferenceCounted(false); acquire(180_000) }
        }.getOrNull()
        var guard: ScheduledFuture<*>? = null
        try {
            socket.soTimeout = TIMEOUT_MS
            guard = closeAfter(socket, TIMEOUT_MS)
            try {
                socket.startHandshake()
            } catch (_: IOException) {
                limiter.failure(remote)
                return
            }
            guard.cancel(false)
            guard = closeAfter(socket, TIMEOUT_MS)
            val input = BufferedInputStream(socket.inputStream, 8192)
            val peer = Peer(remote)
            val (status, body) = try {
                respond(input, peer, guard)
            } catch (error: DeviceLinkError) {
                if (!peer.authorized) limiter.failure(remote)
                error.status to DeviceLinkHttp.errorBody(error)
            } catch (_: IOException) {
                if (!peer.authorized) limiter.failure(remote)
                return
            } catch (_: Exception) {
                DeviceLinkError.internal().let { it.status to DeviceLinkHttp.errorBody(it) }
            }
            guard.cancel(false)
            guard = closeAfter(socket, WRITE_TIMEOUT_MS)
            socket.outputStream.apply {
                write(DeviceLinkHttp.response(status, body))
                flush()
            }
            if (status == 200) peer.pairing?.let(::noteSuccess)
        } catch (_: Exception) {
        } finally {
            guard?.cancel(false)
            close(socket)
            runCatching { wakeLock?.release() }
        }
    }

    private class Peer(val address: String, var authorized: Boolean = false, var pairing: DeviceLinkPairing? = null)

    private fun noteSuccess(pairing: DeviceLinkPairing) {
        try {
            if (DeviceLinkStore.recordSuccess(app, pairing)) DeviceLink.activityChanged()
        } catch (_: Exception) {
        }
    }

    private fun respond(input: BufferedInputStream, peer: Peer, readGuard: ScheduledFuture<*>): Pair<Int, ByteArray> {
        val head = DeviceLinkHttp.readHead(input)
        if (head.target != "/v1/tools") throw DeviceLinkError.notFound("Unknown path.")
        if (head.method != "POST") throw DeviceLinkError.badRequest("Use POST.")
        val length = head.contentLength()
        val pairing = DeviceLinkStore.authorize(app, head.bearerToken())
        if (pairing == null) {
            DeviceLinkHttp.drain(input, length)
            throw DeviceLinkError.unauthorized()
        }
        peer.authorized = true
        peer.pairing = pairing
        if (!limiter.request(peer.address)) {
            DeviceLinkHttp.drain(input, length)
            throw DeviceLinkError.rateLimited()
        }
        val body = DeviceLinkHttp.readBody(input, length)
        readGuard.cancel(false)
        if (head.headers["content-type"]?.lowercase(Locale.ROOT)?.startsWith("application/json") != true) {
            throw DeviceLinkError.badRequest("Content-Type must be application/json.")
        }
        val call = DeviceLinkJson.parseCall(body)
        return success(tools.call(call.tool, call.arguments, pairing.calendarIds))
    }

    private fun success(result: Map<String, Any?>): Pair<Int, ByteArray> {
        val body = DeviceLinkJson.write(mapOf("result" to result))
        if (body.size > DeviceLinkHttp.MAX_RESPONSE) {
            val error = DeviceLinkError.tooLarge("The result is larger than 2 MiB. Use a smaller range or limit.")
            return error.status to DeviceLinkHttp.errorBody(error)
        }
        return 200 to body
    }

    private fun closeAfter(socket: SSLSocket, millis: Int): ScheduledFuture<*> =
        watchdog.schedule({ close(socket) }, millis.toLong(), TimeUnit.MILLISECONDS)

    private fun close(socket: SSLSocket) {
        clients.remove(socket)
        runCatching { socket.close() }
    }

    private fun threads(name: String): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { runnable -> Thread(runnable, "sunlith-link-$name-${count.incrementAndGet()}").apply { isDaemon = true } }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val WRITE_TIMEOUT_MS = 20_000
    }
}

/**
 * Per-peer limits. Repeated unauthenticated failures block only that peer address for a while, so an attacker
 * elsewhere on the network cannot lock out or revoke the paired computer.
 */
internal class DeviceLinkRateLimiter(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Entry(var failureWindow: Long, var failures: Int = 0, var blockedUntil: Long = 0, var requestWindow: Long = 0, var requests: Int = 0)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun admitted(peer: String): Boolean = entries[peer]?.let { clock() >= it.blockedUntil } ?: true

    @Synchronized
    fun failure(peer: String) {
        val now = clock()
        val entry = entry(peer, now)
        if (now - entry.failureWindow >= FAILURE_WINDOW_MS) {
            entry.failureWindow = now
            entry.failures = 0
        }
        entry.failures++
        if (entry.failures >= MAX_FAILURES) {
            entry.blockedUntil = now + BLOCK_MS
            entry.failures = 0
            entry.failureWindow = now
        }
    }

    @Synchronized
    fun request(peer: String): Boolean {
        val now = clock()
        val entry = entry(peer, now)
        entry.failures = 0
        if (now - entry.requestWindow >= REQUEST_WINDOW_MS) {
            entry.requestWindow = now
            entry.requests = 0
        }
        entry.requests++
        return entry.requests <= MAX_REQUESTS
    }

    private fun entry(peer: String, now: Long): Entry {
        entries[peer]?.let { return it }
        if (entries.size >= MAX_PEERS) {
            entries.entries.removeAll { now >= it.value.blockedUntil && now - maxOf(it.value.failureWindow, it.value.requestWindow) > BLOCK_MS }
            if (entries.size >= MAX_PEERS) entries.entries.iterator().let { it.next(); it.remove() }
        }
        return Entry(now, requestWindow = now).also { entries[peer] = it }
    }

    companion object {
        const val MAX_FAILURES = 20
        const val FAILURE_WINDOW_MS = 5 * 60_000L
        const val BLOCK_MS = 5 * 60_000L
        const val MAX_REQUESTS = 240
        const val REQUEST_WINDOW_MS = 60_000L
        const val MAX_PEERS = 512
    }
}
