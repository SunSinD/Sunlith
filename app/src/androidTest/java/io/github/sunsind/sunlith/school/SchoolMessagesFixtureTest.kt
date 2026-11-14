package io.github.sunsind.sunlith.school

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import java.io.File
import java.util.UUID
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Invented administration content only. No real account or message is used. */
@RunWith(AndroidJUnit4::class)
class SchoolMessagesFixtureTest {
    @Test fun oversizedDetailsKeepTitlesAndWholeFrenchBodiesWithinTheEncryptedBudget() {
        val date = LocalDate.of(2026, 11, 13)
        val body = "Été au Collège, conservez le message original. ".repeat(24_000)
        val messages = (0 until 10).map { index -> SchoolAdminNotice("id-$index", date.minusDays(index.toLong()),
            "Information $index", "Direction générale", body = body, detailAvailable = true) }
        val details = messages.associate { it.id to SchoolAdminDetail(it.date, it.subject, it.sender, "", body) }
        val original = SchoolMessagesCache("account", "connection", messages, details, 1L, SchoolAdminStatus.OK).remember()
        assertTrue(original.json().toString().toByteArray(Charsets.UTF_8).size > SchoolMessagesStore.MAX_BYTES)
        val bounded = original.bounded()
        assertEquals(10, bounded.admin.size)
        assertTrue(bounded.details.size in 1..9)
        assertEquals(body, bounded.admin.first().body)
        assertNull(bounded.admin.last().body)
        assertTrue(bounded.admin.all { it.body == null || it.body == body })
        assertEquals(original.seen, bounded.seen)
        assertTrue(bounded.json().toString().toByteArray(Charsets.UTF_8).size + 29 <= SchoolMessagesStore.MAX_BYTES)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.noBackupFilesDir, "messages-budget-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = directory }
        val store = SchoolMessagesStore(context)
        try {
            store.save(bounded)
            assertEquals(bounded, store.load())
            assertTrue(File(directory, "school-messages-v1.bin").length() <= SchoolMessagesStore.MAX_BYTES)
        } finally { store.clear(); check(directory.delete()) }
    }

    @Test fun administrationCacheKeepsOriginalFrenchWithoutSessionOrMailboxData() {
        val date = LocalDate.of(2026, 11, 13)
        val subject = "Levée des cours le 13 novembre"
        val body = "Bonjour, les cours sont levés jusqu’à 18 h. La formation continue est maintenue."
        val cache = SchoolMessagesCache("account", "connection",
            listOf(SchoolAdminNotice("id", date, subject, "Direction générale", "Annonce", body, true)),
            mapOf("id" to SchoolAdminDetail(date, subject, "Direction générale", "Annonce", body)),
            2L, SchoolAdminStatus.OK)
        val json = cache.json()
        assertEquals(cache, SchoolMessagesCache.read(JSONObject(json.toString())))
        assertFalse(json.has("mail"))
        assertFalse(json.has("folders"))
        assertFalse(json.toString().contains("affmessdir"))
        assertFalse(json.toString().contains("token"))
    }
}
