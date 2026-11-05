package io.github.sunsind.sunlith.device

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.Locale

internal class HttpHead(val method: String, val target: String, val headers: Map<String, String>) {
    fun contentLength(): Int {
        if ("transfer-encoding" in headers) throw DeviceLinkError.badRequest("Transfer-Encoding is not supported. Send Content-Length.")
        if ("expect" in headers) throw DeviceLinkError.badRequest("Expect is not supported.")
        val raw = headers["content-length"] ?: throw DeviceLinkError.badRequest("Content-Length is required.")
        if (!Regex("[0-9]{1,9}").matches(raw)) throw DeviceLinkError.badRequest("Invalid Content-Length.")
        val length = raw.toLong()
        if (length > DeviceLinkHttp.MAX_BODY) throw DeviceLinkError.tooLarge("The request is larger than 512 KiB.")
        return length.toInt()
    }

    fun bearerToken(): String? = headers["authorization"]?.let { BEARER.matchEntire(it)?.groupValues?.get(1) }

    private companion object {
        val BEARER = Regex("Bearer ([A-Za-z0-9_-]{43})")
    }
}

internal object DeviceLinkHttp {
    const val MAX_HEADER = 8 * 1024
    const val MAX_BODY = 512 * 1024
    const val MAX_RESPONSE = 2 * 1024 * 1024
    private val REQUEST_LINE = Regex("([A-Z]{1,16}) ([!-~]{1,256}) HTTP/1\\.1")
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")

    fun readHead(input: InputStream): HttpHead {
        val buffer = ByteArrayOutputStream(512)
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            if (value < 0) throw EOFException()
            if (buffer.size() >= MAX_HEADER) throw DeviceLinkError.tooLarge("The request headers are larger than 8 KiB.")
            buffer.write(value)
            matched = when {
                value == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                value == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                value == '\r'.code -> 1
                else -> 0
            }
        }
        val bytes = buffer.toByteArray()
        return parseHead(String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1))
    }

    fun parseHead(text: String): HttpHead {
        if (text.any { it.code > 126 || (it.code < 32 && it != '\r' && it != '\n' && it != '\t') }) throw malformed()
        val lines = text.split("\r\n")
        if (lines.any { '\r' in it || '\n' in it }) throw malformed()
        val request = REQUEST_LINE.matchEntire(lines.first()) ?: throw malformed()
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty() || line[0] == ' ' || line[0] == '\t') throw malformed()
            val separator = line.indexOf(':')
            if (separator <= 0) throw malformed()
            val name = line.substring(0, separator)
            if (!TOKEN.matches(name)) throw malformed()
            val key = name.lowercase(Locale.ROOT)
            if (key in headers) throw DeviceLinkError.badRequest("Duplicate HTTP headers are not allowed.")
            if (headers.size >= 32) throw malformed()
            headers[key] = line.substring(separator + 1).trim(' ', '\t')
        }
        return HttpHead(request.groupValues[1], request.groupValues[2], headers)
    }

    fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        return body
    }

    fun drain(input: InputStream, length: Int) {
        val scratch = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(scratch, 0, minOf(scratch.size, remaining))
            if (read < 0) return
            remaining -= read
        }
    }

    fun response(status: Int, body: ByteArray): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(REASONS[status] ?: "Error").append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            if (status == 401) append("WWW-Authenticate: Bearer\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return head + body
    }

    fun errorBody(error: DeviceLinkError): ByteArray =
        DeviceLinkJson.write(mapOf("error" to linkedMapOf<String, Any?>("code" to error.code, "message" to error.message)
            .apply { error.reason?.let { put("reason", it) } }))

    private fun malformed() = DeviceLinkError.badRequest("Malformed HTTP request.")

    private val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
        409 to "Conflict", 413 to "Payload Too Large", 422 to "Unprocessable Content", 429 to "Too Many Requests",
        500 to "Internal Server Error", 503 to "Service Unavailable")
}
