package dev.zig.notificationfilter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPostingSettingsTest {
    private fun settings(permission: Boolean = true, app: Boolean = true, channel: Boolean = true) =
        NotificationPostingSettings(permission, app, channel, 4, true, true)

    @Test fun missingPermissionIsExplicitEvenWhenAppAlsoDisabled() {
        assertEquals(NotificationDeliveryStatus.NOT_POSTED_PERMISSION, settings(false, false).blockingStatus())
    }
    @Test fun disabledAppIsReportedSeparately() {
        assertEquals(NotificationDeliveryStatus.NOT_POSTED_APP_BLOCKED, settings(app = false).blockingStatus())
    }
    @Test fun disabledChannelIsReportedSeparately() {
        assertEquals(NotificationDeliveryStatus.NOT_POSTED_CHANNEL_BLOCKED, settings(channel = false).blockingStatus())
    }
    @Test fun enabledPostingIsNotBlocked() { assertNull(settings().blockingStatus()) }
    @Test fun quietChannelStillAllowsPosting() { assertNull(settings().copy(channelImportance = 2, channelHasSound = false).blockingStatus()) }
}
