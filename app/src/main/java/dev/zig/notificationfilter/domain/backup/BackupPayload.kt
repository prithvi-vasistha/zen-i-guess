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
 * @property version           schema version. Bumped on a shape change so [BackupRestoreManager]
 *                             can refuse a file it cannot read. v2 added managedApps,
 *                             keywordRules and categoryOverrides; v3 added keywordRulesV2
 *                             carrying the rule type (ALLOW / BLOCK).
 * @property exportDate        Unix epoch seconds the file was written. Informational only.
 * @property preferences       the two genuine user-facing toggles. Internal lifecycle flags
 *                             (onboarding, seeding guards, terms) are intentionally excluded —
 *                             restoring them across devices would misfire onboarding/seeding.
 * @property managedApps       package names ZiG is filtering (the managed_app table).
 * @property keywordRules      v1/v2 compat: each Rules-Vault rule as its list of keyword
 *                             conditions (type information absent — treated as ALLOW on import).
 * @property keywordRulesV2    v3+: each rule with its explicit [KeywordRuleBackupEntry.ruleType].
 *                             Supersedes [keywordRules] when present.
 * @property categoryOverrides per-app default category assignments.
 * @property racMemory         every real manual override, as the classifier's memory corpus.
 */
@Serializable
data class BackupPayload(
    val version: Int = CURRENT_VERSION,
    @SerialName("export_date") val exportDate: Long,
    val preferences: BackupPreferences,
    @SerialName("managed_apps") val managedApps: List<String> = emptyList(),
    @SerialName("keyword_rules") val keywordRules: List<List<String>> = emptyList(),
    @SerialName("keyword_rules_v2") val keywordRulesV2: List<KeywordRuleBackupEntry> = emptyList(),
    @SerialName("category_overrides") val categoryOverrides: List<CategoryOverrideEntry> = emptyList(),
    @SerialName("rac_memory") val racMemory: List<RacMemoryEntry>,
) {
    companion object {
        /** The schema version this build writes and is able to read. */
        const val CURRENT_VERSION = 3
    }
}

/** A typed keyword rule entry used in [BackupPayload.keywordRulesV2] (v3+). */
@Serializable
data class KeywordRuleBackupEntry(
    val conditions: List<String>,
    @SerialName("rule_type") val ruleType: String = "ALLOW",
)

/** One app-level default-category assignment (the app_category_override table). */
@Serializable
data class CategoryOverrideEntry(
    val packageName: String,
    val defaultCategory: String,
)

@Serializable
data class BackupPreferences(
    @SerialName("daily_summary_enabled") val dailySummaryEnabled: Boolean,
    @SerialName("daily_summary_hour") val dailySummaryHour: Int = 20,
    @SerialName("daily_summary_minute") val dailySummaryMinute: Int = 0,
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
