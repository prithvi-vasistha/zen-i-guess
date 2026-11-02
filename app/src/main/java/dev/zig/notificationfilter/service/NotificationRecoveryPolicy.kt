package dev.zig.notificationfilter.service

/** Reconnection must not resurrect an old OTP or replay completed notifications. */
internal object NotificationRecoveryPolicy {
    const val RECENT_WINDOW_MS = 10L * 60L * 1_000L
    const val RETENTION_MS = 24L * 60L * 60L * 1_000L

    fun shouldProcess(previousFingerprint: String?, previousState: String?, fingerprint: String,
        previousPostTime: Long?, postTime: Long, now: Long, recovering: Boolean): Boolean {
        if (previousPostTime != null && postTime < previousPostTime) return false
        if (previousFingerprint == fingerprint && previousState == "COMPLETE") return false
        if (recovering && now - postTime > RETENTION_MS) return false
        if (recovering && now - postTime > RECENT_WINDOW_MS && previousState != "DEFERRED") return false
        return true
    }
}
