package dev.zig.notificationfilter.domain.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The on-disk JSON shape written by [BackupRestoreManager]. Fully offline and
 * user-portable: the user exports this via the Storage Access Framework and can
 * open it in any text editor to see exactly what ZiG stored — no opaque blobs.
 *
 * Deliberately text-only: the RAC (Retrieval-Augmented Classification) corpus is
 * carried as the notification text plus the user's verdict, NOT as raw embedding
 * vectors. Vectors are model-specific; a future embedder would render old vectors
 * meaningless. Re-embedding from text on import keeps the backup forward-compatible
 * across model versions and keeps the file small and inspectable.
 *
 * @property version     schema version. Bumped only on a breaking shape change so
 *                       [BackupRestoreManager] can refuse a file it cannot read.
 * @property exportDate  Unix epoch seconds the file was written. Informational only.
 * @property preferences the two genuine user-facing toggles. Internal lifecycle flags
 *                       (onboarding, seeding guards, terms) are intentionally excluded —
 *                       restoring them across devices would misfire onboarding/seeding.
 * @property racMemory   every manual override, as the classifier's memory corpus.
 */
@Serializable
data class BackupPayload(
    val version: Int = CURRENT_VERSION,
    @SerialName("export_date") val exportDate: Long,
    val preferences: BackupPreferences,
    @SerialName("rac_memory") val racMemory: List<RacMemoryEntry>,
) {
    companion object {
        /** The schema version this build writes and is able to read. */
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class BackupPreferences(
    @SerialName("daily_summary_enabled") val dailySummaryEnabled: Boolean,
    @SerialName("sensitive_notifications_enabled") val sensitiveNotificationsEnabled: Boolean,
)

/**
 * One manual override the user made. On import this is reconstructed as a synthetic
 * review row (see [BackupRestoreManager]) that feeds the personal-memory and exact-match
 * layers without appearing in the inbox or archive.
 *
 * @property messageText         the classifier input (title + content joined) — both the
 *                               exact-match key and the text re-embedded on import.
 * @property userOverrideStatus  "MANUALLY_ALLOWED" or "MANUALLY_BLOCKED".
 * @property userAssignedCategory the category the user pinned, if any. Nullable.
 * @property packageName         source app, for provenance. Nullable for older backups.
 * @property timestamp           Unix epoch millis of the original override, preserved so
 *                               getExactMatchOverride's "most recent wins" ordering holds.
 */
@Serializable
data class RacMemoryEntry(
    val messageText: String,
    val userOverrideStatus: String,
    val userAssignedCategory: String? = null,
    val packageName: String? = null,
    val timestamp: Long,
)
