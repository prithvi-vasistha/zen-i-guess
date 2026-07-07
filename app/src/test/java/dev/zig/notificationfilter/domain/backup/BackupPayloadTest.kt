package dev.zig.notificationfilter.domain.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the backup JSON shape. The exported file is a stable, user-facing
 * format, so the snake_case keys and version semantics must not drift silently.
 */
class BackupPayloadTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sample() = BackupPayload(
        exportDate = 1720272000,
        preferences = BackupPreferences(
            dailySummaryEnabled = true,
            sensitiveNotificationsEnabled = false,
        ),
        managedApps = listOf("com.bank.app", "com.chat.app"),
        keywordRules = listOf(listOf("OTP", "verification"), listOf("code")),
        categoryOverrides = listOf(
            CategoryOverrideEntry(packageName = "com.bank.app", defaultCategory = "FINANCE"),
        ),
        racMemory = listOf(
            RacMemoryEntry(
                messageText = "Your OTP is 123456",
                userOverrideStatus = "MANUALLY_ALLOWED",
                userAssignedCategory = "CATEGORY_FINANCE",
                packageName = "com.bank.app",
                timestamp = 1720272000,
            ),
        ),
    )

    @Test
    fun `round trips without loss`() {
        val original = sample()
        val restored = json.decodeFromString(
            BackupPayload.serializer(),
            json.encodeToString(BackupPayload.serializer(), original),
        )
        assertEquals(original, restored)
    }

    @Test
    fun `uses the documented snake_case keys`() {
        val text = json.encodeToString(BackupPayload.serializer(), sample())
        assertTrue(text.contains("\"export_date\""))
        assertTrue(text.contains("\"daily_summary_enabled\""))
        assertTrue(text.contains("\"sensitive_notifications_enabled\""))
        assertTrue(text.contains("\"managed_apps\""))
        assertTrue(text.contains("\"keyword_rules\""))
        assertTrue(text.contains("\"category_overrides\""))
        assertTrue(text.contains("\"rac_memory\""))
    }

    @Test
    fun `version defaults to the current schema version`() {
        assertEquals(BackupPayload.CURRENT_VERSION, sample().version)
    }

    @Test
    fun `a v1 file without the new sections still imports with empty defaults`() {
        val v1 = """
            {
              "version": 1,
              "export_date": 100,
              "preferences": { "daily_summary_enabled": true, "sensitive_notifications_enabled": true },
              "rac_memory": []
            }
        """.trimIndent()
        val payload = json.decodeFromString(BackupPayload.serializer(), v1)
        assertEquals(1, payload.version)
        assertTrue(payload.managedApps.isEmpty())
        assertTrue(payload.keywordRules.isEmpty())
        assertTrue(payload.categoryOverrides.isEmpty())
    }

    @Test
    fun `optional entry fields decode to null when absent`() {
        val minimal = """
            {
              "version": 1,
              "export_date": 100,
              "preferences": { "daily_summary_enabled": true, "sensitive_notifications_enabled": true },
              "rac_memory": [
                { "messageText": "hi", "userOverrideStatus": "MANUALLY_BLOCKED", "timestamp": 5 }
              ]
            }
        """.trimIndent()
        val entry = json.decodeFromString(BackupPayload.serializer(), minimal).racMemory.single()
        assertNull(entry.userAssignedCategory)
        assertNull(entry.packageName)
        assertEquals("hi", entry.messageText)
    }

    @Test
    fun `unknown keys from a newer writer are ignored`() {
        val withExtra = """
            {
              "version": 1,
              "export_date": 100,
              "future_field": "ignored",
              "preferences": { "daily_summary_enabled": true, "sensitive_notifications_enabled": true },
              "rac_memory": []
            }
        """.trimIndent()
        val payload = json.decodeFromString(BackupPayload.serializer(), withExtra)
        assertTrue(payload.racMemory.isEmpty())
    }

    @Test
    fun `malformed json is rejected`() {
        assertThrows(Exception::class.java) {
            json.decodeFromString(BackupPayload.serializer(), "{ not valid")
        }
    }
}
