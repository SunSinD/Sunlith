package io.github.sunsind.sunlith

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sunsind.sunlith.device.Args
import io.github.sunsind.sunlith.device.DeviceLinkError
import io.github.sunsind.sunlith.device.DeviceLinkHttp
import io.github.sunsind.sunlith.device.DeviceLinkJson
import io.github.sunsind.sunlith.device.DeviceLinkNetwork
import io.github.sunsind.sunlith.device.DeviceLinkRateLimiter
import io.github.sunsind.sunlith.device.EventTime
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceLinkProtocolTest {
    @Test
    fun jsonAcceptsOneStrictObject() {
        val call = DeviceLinkJson.parseCall("""{"tool":"get_event","arguments":{"calendarId":"1","eventId":"2"}}""".toByteArray())
        assertEquals("get_event", call.tool)
        assertEquals("2", call.arguments["eventId"])
    }

    @Test
    fun jsonRejectsDuplicatesTrailingDataDepthAndBadUtf8() {
        listOf(
            """{"tool":"a","tool":"b","arguments":{}}""",
            """{"tool":"a","arguments":{"x":1,"x":2}}""",
            """{"tool":"a","arguments":{}} {}""",
            """{"tool":"a","arguments":{}}x""",
            """{"tool":"a","arguments":{},"extra":true}""",
            """{"tool":"a","arguments":[]}""",
            """{"tool":"a","arguments":{"a":${"[".repeat(12)}${"]".repeat(12)}}}""",
            """{tool:"a","arguments":{}}""",
            """{"tool":"a","arguments":{"n":NaN}}""",
        ).forEach { body -> expect("bad_request") { DeviceLinkJson.parseCall(body.toByteArray()) } }
        expect("bad_request") { DeviceLinkJson.parseCall(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte())) }
        val nested = """{"tool":"a","arguments":{"a":${"[".repeat(9)}${"]".repeat(9)}}}"""
        DeviceLinkJson.parseCall(nested.toByteArray())
    }

    @Test
    fun httpHeadIsStrict() {
        val head = DeviceLinkHttp.readHead(ByteArrayInputStream(
            "POST /v1/tools HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${"a".repeat(43)}\r\nContent-Length: 12\r\n\r\n{}".toByteArray()))
        assertEquals("POST", head.method)
        assertEquals("/v1/tools", head.target)
        assertEquals(12, head.contentLength())
        assertEquals("a".repeat(43), head.bearerToken())

        expect("bad_request") { DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.1\r\nContent-Length: 1\r\ncontent-length: 1") }
        expect("bad_request") { DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.1\r\nX-A: 1\r\n folded") }
        expect("bad_request") { DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.1\nHost: a") }
        expect("bad_request") { DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.0") }
        expect("bad_request") { DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 2").contentLength() }
        expect("bad_request") { DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.1\r\nContent-Length: +2").contentLength() }
        expect("bad_request") { DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.1\r\nHost: a").contentLength() }
        expect("too_large") { DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.1\r\nContent-Length: ${512 * 1024 + 1}").contentLength() }
        expect("too_large") { DeviceLinkHttp.readHead(ByteArrayInputStream(("POST /v1/tools HTTP/1.1\r\nX: " + "a".repeat(9000)).toByteArray())) }
        assertNull(DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.1\r\nAuthorization: Basic abc").bearerToken())
        assertNull(DeviceLinkHttp.parseHead("POST /v1/tools HTTP/1.1\r\nAuthorization: Bearer short").bearerToken())
    }

    @Test
    fun failedPeersAreBlockedWithoutLockingOutOthers() {
        var now = 0L
        val limiter = DeviceLinkRateLimiter { now }
        repeat(DeviceLinkRateLimiter.MAX_FAILURES) { limiter.failure("10.0.0.9") }
        assertFalse(limiter.admitted("10.0.0.9"))
        assertTrue(limiter.admitted("100.64.0.2"))
        assertTrue(limiter.request("100.64.0.2"))
        now += DeviceLinkRateLimiter.BLOCK_MS
        assertTrue(limiter.admitted("10.0.0.9"))
        repeat(DeviceLinkRateLimiter.MAX_REQUESTS) { assertTrue(limiter.request("100.64.0.2")) }
        assertFalse(limiter.request("100.64.0.2"))
        now += DeviceLinkRateLimiter.REQUEST_WINDOW_MS
        assertTrue(limiter.request("100.64.0.2"))
    }

    @Test
    fun privateAddressRulesMatchTheNodeClient() {
        listOf("10.1.2.3", "127.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.5", "100.64.0.1", "100.127.255.254").forEach {
            assertTrue(it, DeviceLinkNetwork.isPrivate(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "100.63.255.255", "169.254.1.1", "192.169.0.1").forEach {
            assertFalse(it, DeviceLinkNetwork.isPrivate(InetAddress.getByName(it)))
        }
        assertTrue(DeviceLinkNetwork.isTailscale(InetAddress.getByName("100.101.102.103")))
    }

    @Test
    fun timedValuesMustMatchTheZoneOffset() {
        fun time(value: String, zone: String = "America/Toronto") =
            Args(mapOf("start" to mapOf("dateTime" to value, "timeZone" to zone)), setOf("start")).eventTime("start")
        expect("bad_request") { time("2026-03-08T02:30:00-05:00") }
        expect("bad_request") { time("2026-03-08T02:30:00-04:00") }
        expect("bad_request") { time("2026-07-01T10:00:00-05:00") }
        expect("bad_request") { time("2026-07-01T10:00:00Z") }
        expect("bad_request") { time("2026-02-30T10:00:00-05:00") }
        expect("bad_request") { time("2026-07-01T10:00-04:00") }
        expect("bad_request") { time("2026-07-01T10:00:00-04:00", "Mars/Base") }
        expect("bad_request") { time("2026-07-01T10:00:00+04:00", "+04:00") }
        val first = time("2026-11-01T01:30:00-04:00") as EventTime.Timed
        val second = time("2026-11-01T01:30:00-05:00") as EventTime.Timed
        assertEquals(3_600_000L, second.instant.toEpochMilli() - first.instant.toEpochMilli())
        assertEquals(Instant.parse("2026-07-01T10:00:00Z"), (time("2026-07-01T10:00:00Z", "UTC") as EventTime.Timed).instant)
        assertEquals("America/Toronto", (time("2026-07-01T10:00:00.250-04:00", "america/toronto") as EventTime.Timed).zone.id)
        expect("bad_request") { Args(mapOf("start" to mapOf("date" to "2026-02-29")), setOf("start")).eventTime("start") }
        expect("bad_request") { Args(mapOf("start" to mapOf("date" to "2026-02-28", "timeZone" to "UTC")), setOf("start")).eventTime("start") }
    }

    @Test
    fun argumentsAreStrict() {
        expect("bad_request") { Args(mapOf("calendarId" to "1", "extra" to 1), setOf("calendarId")) }
        expect("bad_request") { Args(mapOf("title" to null), setOf("title")) }
        listOf("0", "01", "-1", "1.0", "abc", "9223372036854775808", "").forEach { value ->
            expect("bad_request") { Args(mapOf("calendarId" to value), setOf("calendarId")).id("calendarId") }
        }
        expect("bad_request") { Args(mapOf("calendarId" to 5L), setOf("calendarId")).id("calendarId") }
        assertEquals(Long.MAX_VALUE, Args(mapOf("calendarId" to Long.MAX_VALUE.toString()), setOf("calendarId")).id("calendarId"))
        expect("bad_request") { Args(mapOf("recurrence" to listOf("RRULE:FREQ=HOURLY")), setOf("recurrence")).recurrence() }
        expect("bad_request") { Args(mapOf("recurrence" to listOf("RRULE:FREQ=DAILY", "RRULE:FREQ=WEEKLY")), setOf("recurrence")).recurrence() }
        expect("bad_request") { Args(mapOf("recurrence" to listOf("FREQ=DAILY")), setOf("recurrence")).recurrence() }
        assertEquals("FREQ=WEEKLY;COUNT=4", Args(mapOf("recurrence" to listOf("RRULE:FREQ=WEEKLY;COUNT=4")), setOf("recurrence")).recurrence())
        assertNull(Args(mapOf("recurrence" to emptyList<String>()), setOf("recurrence")).recurrence())
        expect("bad_request") { Args(mapOf("reminderMinutes" to listOf(40_321)), setOf("reminderMinutes")).reminders() }
        expect("bad_request") { Args(mapOf("reminderMinutes" to List(6) { it }), setOf("reminderMinutes")).reminders() }
        expect("bad_request") { Args(mapOf("reminderMinutes" to listOf(1.5)), setOf("reminderMinutes")).reminders() }
        expect("bad_request") { Args(mapOf("title" to "   "), setOf("title")).title() }
        assertEquals("Lab", Args(mapOf("title" to "  Lab "), setOf("title")).title())
        expect("bad_request") { Args(mapOf("pageToken" to "01"), setOf("pageToken")).pageToken() }
        expect("bad_request") { Args(mapOf("seriesScope" to "event"), setOf("seriesScope")).seriesScope() }
        expect("bad_request") { Args(mapOf("confirm" to "true"), setOf("confirm")).confirm() }
    }

    private fun expect(code: String, block: () -> Unit) {
        val error = assertThrows(DeviceLinkError::class.java) { block() }
        assertEquals(code, error.code)
    }
}
