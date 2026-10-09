package dev.zig.notificationfilter.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationDeliveryPolicyTest {
    @Test
    fun `matched contact receives an image even when images are disabled`() {
        assertEquals(NotificationDeliveryDecision.FORWARD,
            decide(hasImage = true, imagesEnabled = false, contactBypass = true))
    }

    @Test
    fun `non-contact image is blocked after an earlier allow decision`() {
        assertEquals(NotificationDeliveryDecision.BLOCK_IMAGE,
            decide(hasImage = true, imagesEnabled = false))
    }

    @Test
    fun `enabled images preserve the earlier allow decision`() {
        assertEquals(NotificationDeliveryDecision.FORWARD,
            decide(hasImage = true, imagesEnabled = true))
    }

    @Test
    fun `text without an image is unaffected by the image toggle`() {
        assertEquals(NotificationDeliveryDecision.FORWARD,
            decide(hasImage = false, imagesEnabled = false))
    }

    @Test
    fun `sensitive shortcut cannot allow a private non-contact image while locked`() {
        assertEquals(NotificationDeliveryDecision.DEFER_UNTIL_UNLOCK,
            decide(hasImage = true, imagesEnabled = false, privateWhileLocked = true))
    }

    @Test
    fun `redacted private content is deferred even when its image cannot be seen`() {
        assertEquals(NotificationDeliveryDecision.DEFER_UNTIL_UNLOCK,
            decide(hasImage = false, imagesEnabled = false, privateWhileLocked = true))
    }

    @Test
    fun `matched contact remains exempt on the lock screen when sensitive bypass is enabled`() {
        assertEquals(NotificationDeliveryDecision.FORWARD,
            decide(hasImage = true, imagesEnabled = false, contactBypass = true,
                privateWhileLocked = true))
    }

    @Test
    fun `turning off sensitive notifications preserves deferral for contacts too`() {
        assertEquals(NotificationDeliveryDecision.DEFER_UNTIL_UNLOCK,
            decide(hasImage = true, imagesEnabled = false, contactBypass = true,
                privateWhileLocked = true, sensitiveNotificationsEnabled = false))
    }

    @Test
    fun `enabled image toggle preserves the sensitive lock screen shortcut`() {
        assertEquals(NotificationDeliveryDecision.FORWARD,
            decide(hasImage = true, imagesEnabled = true, privateWhileLocked = true))
    }

    @Test
    fun `deferred image is blocked once content is available after unlock`() {
        assertEquals(NotificationDeliveryDecision.BLOCK_IMAGE,
            decide(hasImage = true, imagesEnabled = false, privateWhileLocked = false,
                sensitiveNotificationsEnabled = false))
    }

    private fun decide(
        hasImage: Boolean,
        imagesEnabled: Boolean,
        contactBypass: Boolean = false,
        privateWhileLocked: Boolean = false,
        sensitiveNotificationsEnabled: Boolean = true,
    ) = NotificationDeliveryPolicy.decide(
        hasImage, imagesEnabled, contactBypass, privateWhileLocked, sensitiveNotificationsEnabled,
    )
}
