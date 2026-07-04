package dev.zig.notificationfilter.data.local.db

import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds two sample notifications on the very first launch so the onboarding tour can
 * demonstrate the review flow (Allow / Block / Undo) and the category chip on real cards.
 *
 * These are ordinary [notification_review][NotificationReviewEntity] rows — every existing
 * interaction (allow, block, undo, category edit, search, archive) works on them unchanged.
 * They are inserted once, guarded by [ZigUserPreferences.demoSeeded], and are then the user's
 * to act on or dismiss like any other notification.
 *
 * Both rows share [DEMO_PACKAGE] so they group under a single "Zig Test Notification" app
 * card. Their [DEMO_JOB_PREFIX] jobIds let [PersonalMemoryRepository] skip them, so acting on
 * a demo card never pollutes the on-device KNN corpus.
 */
@Singleton
class DemoDataSeeder @Inject constructor(
    private val reviewDao: NotificationReviewDao,
    private val preferences: ZigUserPreferences,
) {

    suspend fun seedIfNeeded() {
        if (preferences.demoSeeded) return

        val now = System.currentTimeMillis()

        // A message ZiG let through — the card shows "Block & Mute" (with Undo after acting).
        reviewDao.insert(
            NotificationReviewEntity(
                jobId = "${DEMO_JOB_PREFIX}allow",
                packageName = DEMO_PACKAGE,
                title = "Alex",
                content = "Running 10 minutes late — see you soon!",
                timestamp = now - 5 * 60_000L,
                systemDecision = "PUBLISHED",
                modelConfidence = 0.08f,
                inferredCategory = "CATEGORY_SOCIAL",
            ),
        )

        // A promo ZiG blocked — the card shows "Allow" (with Undo after acting).
        reviewDao.insert(
            NotificationReviewEntity(
                jobId = "${DEMO_JOB_PREFIX}block",
                packageName = DEMO_PACKAGE,
                title = "MegaMart",
                content = "FLASH SALE! 70% off everything — ends tonight. Shop now!",
                timestamp = now - 6 * 60_000L,
                systemDecision = "MODEL_BLOCKED",
                modelConfidence = 0.93f,
                inferredCategory = "CATEGORY_SHOPPING",
            ),
        )

        preferences.demoSeeded = true
    }

    companion object {
        // Synthetic package for the demo rows. Not installed, so the review screen resolves
        // its label to DEMO_APP_LABEL explicitly (see NotificationReviewViewModel).
        const val DEMO_PACKAGE = "dev.zig.notificationfilter.demo"
        const val DEMO_APP_LABEL = "Zig Test Notification"

        // jobId prefix marking rows as demo data so Personal Memory ignores them.
        const val DEMO_JOB_PREFIX = "demo-"
    }
}
