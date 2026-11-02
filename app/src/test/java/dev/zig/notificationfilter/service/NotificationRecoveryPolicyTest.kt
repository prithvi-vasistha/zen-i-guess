package dev.zig.notificationfilter.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRecoveryPolicyTest {
    private val now = 100_000_000L
    private fun process(state: String? = null, old: String? = null, signature: String = "new",
        previousTime: Long? = null, age: Long = 0L, replay: Boolean = true) =
        NotificationRecoveryPolicy.shouldProcess(old, state, signature, previousTime, now - age, now, replay)

    @Test fun completedFingerprintIsNeverReplayed() { assertFalse(process("COMPLETE", "new")) }
    @Test fun newUpdateOfCompletedNotificationIsProcessed() { assertTrue(process("COMPLETE", "old")) }
    @Test fun delayedOlderCallbackCannotReplaceNewerUpdate() { assertFalse(process(previousTime = now, age = 1L)) }
    @Test fun recentMissedNotificationIsRecovered() { assertTrue(process(age = 60_000L)) }
    @Test fun oldOtpIsNotResurrectedOnReconnect() { assertFalse(process(age = 11 * 60_000L)) }
    @Test fun pendingUnlockCanSurviveLongerThanReconnectWindow() { assertTrue(process("DEFERRED", "new", age = 60 * 60_000L)) }
    @Test fun pendingUnlockExpiresAfterRetentionWindow() { assertFalse(process("DEFERRED", "new", age = 25 * 60 * 60_000L)) }
    @Test fun liveCallbackIsNotRejectedBecauseItsSourceTimestampIsOld() { assertTrue(process(age = 11 * 60_000L, replay = false)) }
    @Test fun interruptedProcessingIsRetryableWhileRecent() { assertTrue(process("ERROR", "new")) }
}
