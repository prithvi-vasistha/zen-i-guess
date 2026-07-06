package dev.zig.notificationfilter.domain.backup

import dev.zig.notificationfilter.data.local.db.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the invariants that let a restored override feed the classifier's memory without
 * masquerading as a real notification in the inbox or archive.
 */
class RestoredEntityTest {

    private fun entry() = RacMemoryEntry(
        messageText = "Your package has shipped",
        userOverrideStatus = "MANUALLY_BLOCKED",
        userAssignedCategory = "CATEGORY_PROMOTIONS",
        packageName = "com.shop.app",
        timestamp = 42L,
    )

    @Test
    fun `restored row is tagged RESTORED so it stays out of inbox and archive`() {
        assertEquals(RESTORED_DECISION, entry().toRestoredEntity().systemDecision)
    }

    @Test
    fun `restored row takes a fresh auto id and never reuses the source id`() {
        assertEquals(0L, entry().toRestoredEntity().id)
    }

    @Test
    fun `messageText is preserved as the exact-match and re-embed key`() {
        assertEquals("Your package has shipped", entry().toRestoredEntity().messageText)
    }

    @Test
    fun `title and content are blank - the joined messageText carries the text`() {
        val row = entry().toRestoredEntity()
        assertTrue(row.title.isEmpty())
        assertTrue(row.content.isEmpty())
    }

    @Test
    fun `override status, category and timestamp survive the round trip`() {
        val row = entry().toRestoredEntity()
        assertEquals("MANUALLY_BLOCKED", row.userOverrideStatus)
        assertEquals("CATEGORY_PROMOTIONS", row.userAssignedCategory)
        assertEquals(42L, row.timestamp)
    }

    @Test
    fun `restored row is marked EXPORTED so it is not re-fed to the training set`() {
        assertEquals(SyncStatus.EXPORTED, entry().toRestoredEntity().syncStatus)
    }

    @Test
    fun `embedding starts null and is recomputed on device after insert`() {
        assertNull(entry().toRestoredEntity().embedding)
    }

    @Test
    fun `missing package name maps to empty string`() {
        val row = entry().copy(packageName = null).toRestoredEntity()
        assertEquals("", row.packageName)
    }
}
