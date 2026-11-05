package io.github.sunsind.sunlith

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sunsind.sunlith.device.DeviceLink
import io.github.sunsind.sunlith.device.DeviceLinkDiagnostics
import io.github.sunsind.sunlith.device.DeviceLinkJson
import io.github.sunsind.sunlith.device.DeviceLinkServer
import io.github.sunsind.sunlith.device.DeviceLinkStore
import io.github.sunsind.sunlith.device.DeviceLinkStore.hex
import io.github.sunsind.sunlith.device.DeviceLinkTls
import java.io.File
import java.net.Socket
import java.net.SocketException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.ZoneId
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceLinkTlsTest {
    private lateinit var context: Context
    private lateinit var server: DeviceLinkServer
    private lateinit var token: String

    @Before
    fun setUp() {
        requireEmulator()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        token = DeviceLinkStore.pair(context, setOf(1L))
        server = DeviceLinkServer(context, port = 0).also { it.start() }
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        if (::context.isInitialized) DeviceLinkStore.clear(context)
    }

    @Test
    fun androidKeyStoreCertificateServesPinnedTls() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias("sunlith_device_link_tls"))
        val certificate = keyStore.getCertificate("sunlith_device_link_tls") as X509Certificate
        assertEquals("EC", certificate.publicKey.algorithm)
        certificate.verify(certificate.publicKey)
        val response = post("""{"tool":"get_current_time","arguments":{}}""") { socket ->
            assertTrue(socket.session.protocol in setOf("TLSv1.2", "TLSv1.3"))
            assertEquals(DeviceLinkTls.fingerprint(), DeviceLinkStore.sha256(socket.session.peerCertificates[0].encoded).hex())
        }
        assertEquals(200, response.first)
        val result = (DeviceLinkJson.parse(response.second.toByteArray()) as Map<*, *>)["result"] as Map<*, *>
        assertEquals(ZoneId.systemDefault().id, result["timeZone"])
    }

    @Test
    fun tokensAreCheckedAndRevocationIsImmediate() {
        assertEquals(401, post("""{"tool":"get_current_time","arguments":{}}""", token = "A".repeat(43)).first)
        assertEquals(401, post("""{"tool":"get_current_time","arguments":{}}""", token = null).first)
        assertEquals(200, post("""{"tool":"get_current_time","arguments":{}}""").first)
        val rotated = DeviceLinkStore.pair(context, setOf(1L))
        assertEquals(401, post("""{"tool":"get_current_time","arguments":{}}""").first)
        token = rotated
        assertEquals(200, post("""{"tool":"get_current_time","arguments":{}}""").first)
        DeviceLinkStore.clear(context)
        val revoked = post("""{"tool":"get_current_time","arguments":{}}""")
        assertEquals(401, revoked.first)
        assertTrue(revoked.second.contains("\"code\":\"unauthorized\""))
        assertFalse(revoked.second.contains(token))
    }

    @Test
    fun diagnosticsTrackOnlySuccessfulAnswersForTheCurrentPairing() {
        val pairedAt = DeviceLink.diagnostics(context).pairedAtMillis
        assertNotNull(pairedAt)
        assertNull(DeviceLink.diagnostics(context).lastSuccessMillis)
        assertEquals(401, post("""{"tool":"get_current_time","arguments":{}}""", token = "A".repeat(43)).first)
        assertEquals(400, post("""{"tool":"nope","arguments":{}}""").first)
        assertNull(DeviceLink.diagnostics(context).lastSuccessMillis)

        val before = DeviceLink.activity.value
        val start = System.currentTimeMillis()
        assertEquals(200, post("""{"tool":"get_current_time","arguments":{}}""").first)
        val seen = DeviceLink.diagnostics(context).lastSuccessMillis
        assertNotNull(seen)
        assertTrue(seen!! >= start)
        assertTrue(DeviceLink.activity.value > before)
        DeviceLinkStore.dropCache()
        assertEquals(DeviceLinkDiagnostics(pairedAt, seen), DeviceLink.diagnostics(context))

        val old = DeviceLinkStore.pairing(context)!!
        token = DeviceLinkStore.pair(context, setOf(1L))
        assertNull(DeviceLink.diagnostics(context).lastSuccessMillis)
        assertFalse(DeviceLinkStore.recordSuccess(context, old))
        assertNull(DeviceLink.diagnostics(context).lastSuccessMillis)

        assertEquals(200, post("""{"tool":"get_current_time","arguments":{}}""").first)
        assertNotNull(DeviceLink.diagnostics(context).lastSuccessMillis)
        DeviceLinkStore.clear(context)
        assertEquals(DeviceLinkDiagnostics(null, null), DeviceLink.diagnostics(context))
        DeviceLinkStore.dropCache()
        assertNull(DeviceLink.diagnostics(context).lastSuccessMillis)
    }

    @Test
    fun pairingFilesWithoutPairedAtStillLoad() {
        DeviceLinkStore.write(DeviceLinkStore.file(context, "pairing.json"), DeviceLinkJson.write(linkedMapOf(
            "version" to 1, "tokenSha256" to DeviceLinkStore.sha256(token.toByteArray()).hex(), "calendarIds" to listOf("1"))))
        DeviceLinkStore.dropCache()
        assertEquals(setOf(1L), DeviceLinkStore.authorize(context, token)?.calendarIds)
        assertNull(DeviceLink.diagnostics(context).pairedAtMillis)
        assertEquals(200, post("""{"tool":"get_current_time","arguments":{}}""").first)
    }

    @Test
    fun rawTokenIsNeverPersisted() {
        File(context.noBackupFilesDir, "device-link").walkTopDown().filter { it.isFile }.forEach {
            assertFalse(it.name, it.readText().contains(token))
        }
    }

    @Test
    fun stoppingClosesBothWorkingAndQueuedConnections() {
        val clients = List(8) { Socket("127.0.0.1", server.localPort).apply { soTimeout = 2_000 } }
        try {
            server.stop()
            clients.forEach { socket ->
                try { assertEquals(-1, socket.inputStream.read()) }
                catch (_: SocketException) { /* Closing a pending handshake may reset the TCP connection. */ }
            }
        } finally {
            clients.forEach { it.close() }
        }
    }

    @Test
    fun framingAndRoutingErrorsUseStableCodes() {
        assertEquals(400, post("""{"tool":"get_current_time","arguments":{}}""", extra = "Transfer-Encoding: chunked\r\n").first)
        assertEquals(400, post("""{"tool":"get_current_time","arguments":{}}""", extra = "Authorization: Bearer ${"b".repeat(43)}\r\n").first)
        assertEquals(404, post("""{"tool":"get_current_time","arguments":{}}""", path = "/v1/other").first)
        assertEquals(400, post("""{"tool":"get_current_time","arguments":{"timeZone":"UTC","x":1}}""").first)
        assertEquals(400, post("""{"tool":"nope","arguments":{}}""").first)
        assertEquals(400, post("""{"tool":"get_current_time","arguments":{}} trailing""").first)
    }

    private fun post(
        body: String,
        token: String? = this.token,
        path: String = "/v1/tools",
        extra: String = "",
        inspect: (SSLSocket) -> Unit = {},
    ): Pair<Int, String> {
        val trustPinnedLater = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustPinnedLater), null) }
        (ssl.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { socket ->
            socket.soTimeout = 15_000
            socket.startHandshake()
            assertEquals(DeviceLinkTls.fingerprint(), DeviceLinkStore.sha256(socket.session.peerCertificates[0].encoded).hex())
            inspect(socket)
            val bytes = body.toByteArray()
            val head = buildString {
                append("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                append("Content-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n")
                append(extra).append("\r\n")
            }
            socket.outputStream.write(head.toByteArray(Charsets.US_ASCII) + bytes)
            socket.outputStream.flush()
            val response = String(socket.inputStream.readBytes(), Charsets.UTF_8)
            val status = response.substringBefore("\r\n").split(' ')[1].toInt()
            assertTrue(response.contains("\r\nConnection: close\r\n"))
            return status to response.substringAfter("\r\n\r\n")
        }
    }
}
