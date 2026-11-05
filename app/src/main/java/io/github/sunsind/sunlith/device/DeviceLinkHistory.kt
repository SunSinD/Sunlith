package io.github.sunsind.sunlith.device

import android.content.Context
import android.util.AtomicFile
import java.io.IOException

enum class DeviceLinkChangeAction { CREATE, UPDATE, DELETE, IMPORT }

enum class DeviceLinkChangeField { TITLE, DESCRIPTION, LOCATION, TIME, RECURRENCE, REMINDERS }

/**
 * One calendar change the paired computer made through the phone link. Only successful provider writes are recorded.
 * [eventId] and [title] are set for create, update and delete. [fields] is set for update. The counts are set for import.
 */
data class DeviceLinkChange(
    val action: DeviceLinkChangeAction,
    val atMillis: Long,
    val calendarId: Long,
    val eventId: Long? = null,
    val title: String? = null,
    val fields: List<DeviceLinkChangeField> = emptyList(),
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
)

/**
 * Local list of the latest [MAX_ENTRIES] changes, in noBackupFilesDir. It never stores tokens, request bodies,
 * descriptions, locations, addresses or reads. Stopping the link or unpairing keeps it; only [clear] removes it.
 */
internal object DeviceLinkHistory {
    const val MAX_ENTRIES = 100
    const val MAX_TITLE = 200
    private const val FILE = "history.json"
    private val lock = Any()
    private var entries: List<DeviceLinkChange>? = null

    /** Newest first. */
    fun entries(context: Context): List<DeviceLinkChange> = synchronized(lock) { load(context).asReversed().toList() }

    /** Keeps the change in memory even if the file write fails, and never throws. */
    fun record(context: Context, change: DeviceLinkChange) {
        try {
            synchronized(lock) {
                val updated = (load(context) + change.copy(title = cleanTitle(change.title))).takeLast(MAX_ENTRIES)
                entries = updated
                try { DeviceLinkStore.write(DeviceLinkStore.file(context, FILE), encode(updated)) } catch (_: IOException) { }
            }
        } catch (_: Exception) {
        }
        DeviceLink.activityChanged()
    }

    fun clear(context: Context) {
        synchronized(lock) {
            entries = emptyList()
            AtomicFile(DeviceLinkStore.file(context, FILE)).delete()
        }
        DeviceLink.activityChanged()
    }

    /** Tests use this to read the file again. */
    internal fun dropCache() = synchronized(lock) { entries = null }

    private fun load(context: Context): List<DeviceLinkChange> = entries ?: read(context).also { entries = it }

    private fun read(context: Context): List<DeviceLinkChange> = try {
        val root = DeviceLinkJson.parse(AtomicFile(DeviceLinkStore.file(context, FILE)).readFully()) as Map<*, *>
        if (root["version"] != 1L) emptyList()
        else (root["entries"] as List<*>).mapNotNull(::decode).takeLast(MAX_ENTRIES)
    } catch (_: Exception) {
        emptyList()
    }

    /** At most [MAX_TITLE] chars, without lone surrogates, which the JSON writer rejects and would block every later save. */
    internal fun cleanTitle(title: String?): String? {
        if (title == null) return null
        val out = StringBuilder()
        var i = 0
        while (i < title.length && out.length < MAX_TITLE) {
            val c = title[i]
            if (c.isHighSurrogate() && i + 1 < title.length && title[i + 1].isLowSurrogate()) {
                if (out.length + 2 > MAX_TITLE) break
                out.append(c).append(title[i + 1])
                i += 2
            } else {
                if (!c.isSurrogate()) out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun encode(list: List<DeviceLinkChange>): ByteArray = DeviceLinkJson.write(linkedMapOf(
        "version" to 1,
        "entries" to list.map { change ->
            linkedMapOf<String, Any?>("action" to change.action.name, "at" to change.atMillis, "calendarId" to change.calendarId).apply {
                change.eventId?.let { put("eventId", it) }
                change.title?.let { put("title", it) }
                if (change.fields.isNotEmpty()) put("fields", change.fields.map { it.name })
                if (change.action == DeviceLinkChangeAction.IMPORT) {
                    put("imported", change.imported)
                    put("duplicates", change.duplicates)
                    put("skipped", change.skipped)
                }
            }
        },
    ))

    private fun decode(value: Any?): DeviceLinkChange? = try {
        val map = value as Map<*, *>
        fun count(key: String) = ((map[key] as? Long) ?: 0L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        DeviceLinkChange(
            action = DeviceLinkChangeAction.valueOf(map["action"] as String),
            atMillis = map["at"] as Long,
            calendarId = map["calendarId"] as Long,
            eventId = map["eventId"] as? Long,
            title = cleanTitle(map["title"] as? String),
            fields = (map["fields"] as? List<*>).orEmpty().mapNotNull { name -> DeviceLinkChangeField.entries.firstOrNull { it.name == name } },
            imported = count("imported"),
            duplicates = count("duplicates"),
            skipped = count("skipped"),
        )
    } catch (_: Exception) {
        null
    }
}
