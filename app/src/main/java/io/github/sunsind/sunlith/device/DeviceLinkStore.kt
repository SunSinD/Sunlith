package io.github.sunsind.sunlith.device

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class DeviceLinkPairing(val tokenSha256: ByteArray, val calendarIds: Set<Long>)

internal data class OperationRecord(val key: String, val payloadHash: String, val eventId: Long)

/** Pairing scope and create_event journal. Only a SHA-256 of the token is stored, in noBackupFilesDir. */
internal object DeviceLinkStore {
    private const val MAX_OPERATIONS = 2000
    private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var cached: DeviceLinkPairing? = null

    fun pairing(context: Context): DeviceLinkPairing? {
        if (!loaded) synchronized(lock) {
            if (!loaded) {
                cached = readPairing(context)
                loaded = true
            }
        }
        return cached
    }

    /** Replaces any previous pairing and returns the new raw token. The raw token is never persisted. */
    fun pair(context: Context, calendarIds: Set<Long>): String {
        require(calendarIds.isNotEmpty() && calendarIds.all { it > 0 }) { "Choose at least one calendar." }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val pairing = DeviceLinkPairing(sha256(token.toByteArray(Charsets.US_ASCII)), calendarIds.toSet())
        synchronized(lock) {
            write(file(context, PAIRING), DeviceLinkJson.write(linkedMapOf(
                "version" to 1,
                "tokenSha256" to pairing.tokenSha256.hex(),
                "calendarIds" to pairing.calendarIds.sorted().map(Long::toString),
            )))
            cached = pairing
            loaded = true
        }
        return token
    }

    fun clear(context: Context) {
        synchronized(lock) {
            cached = null
            loaded = true
            AtomicFile(file(context, PAIRING)).delete()
        }
    }

    fun authorize(context: Context, token: String?): DeviceLinkPairing? {
        val pairing = pairing(context) ?: return null
        if (token == null || !TOKEN.matches(token)) return null
        return pairing.takeIf { MessageDigest.isEqual(sha256(token.toByteArray(Charsets.US_ASCII)), it.tokenSha256) }
    }

    fun operation(context: Context, key: String): OperationRecord? = synchronized(lock) { readOperations(context).firstOrNull { it.key == key } }

    fun recordOperation(context: Context, record: OperationRecord) {
        synchronized(lock) {
            val updated = (readOperations(context).filter { it.key != record.key } + record).takeLast(MAX_OPERATIONS)
            write(file(context, OPERATIONS), updated.joinToString("") { "${it.key} ${it.payloadHash} ${it.eventId}\n" }.toByteArray(Charsets.US_ASCII))
        }
    }

    private fun readPairing(context: Context): DeviceLinkPairing? = try {
        val root = DeviceLinkJson.parse(AtomicFile(file(context, PAIRING)).readFully()) as Map<*, *>
        require(root["version"] == 1L)
        val hash = (root["tokenSha256"] as String).also { require(Regex("[0-9a-f]{64}").matches(it)) }
        val ids = (root["calendarIds"] as List<*>).map { (it as String).toLong().also { id -> require(id > 0) } }.toSet()
        require(ids.isNotEmpty())
        DeviceLinkPairing(hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), ids)
    } catch (_: Exception) {
        null
    }

    private fun readOperations(context: Context): List<OperationRecord> = try {
        String(AtomicFile(file(context, OPERATIONS)).readFully(), Charsets.US_ASCII).lineSequence().mapNotNull { line ->
            val parts = line.split(' ')
            if (parts.size != 3) null else parts[2].toLongOrNull()?.let { OperationRecord(parts[0], parts[1], it) }
        }.toList()
    } catch (_: IOException) {
        emptyList()
    }

    private fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val file = AtomicFile(target)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: IOException) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun file(context: Context, name: String) = File(File(context.noBackupFilesDir, "device-link"), name)

    internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val PAIRING = "pairing.json"
    private const val OPERATIONS = "operations.txt"
}
