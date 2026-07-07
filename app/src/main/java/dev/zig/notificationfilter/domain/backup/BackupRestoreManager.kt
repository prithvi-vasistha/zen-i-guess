package dev.zig.notificationfilter.domain.backup

import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.AppCategoryOverrideDao
import dev.zig.notificationfilter.data.local.db.AppCategoryOverrideEntity
import dev.zig.notificationfilter.data.local.db.KeywordRuleDao
import dev.zig.notificationfilter.data.local.db.KeywordRuleEntity
import dev.zig.notificationfilter.data.local.db.ManagedAppDao
import dev.zig.notificationfilter.data.local.db.ManagedAppEntity
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.data.local.db.ReviewState
import dev.zig.notificationfilter.data.local.db.SyncStatus
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import dev.zig.notificationfilter.domain.memory.PersonalMemory
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a backup file's [BackupPayload.version] is newer than this build can read. */
class IncompatibleBackupVersionException(val fileVersion: Int) :
    Exception("Backup version $fileVersion is newer than supported ${BackupPayload.CURRENT_VERSION}")

/** Outcome of a successful restore, surfaced to the UI for the confirmation message. */
data class RestoreResult(
    val overridesRestored: Int,
    val overridesEmbedded: Int,
    val managedAppsAdded: Int,
    val keywordRulesAdded: Int,
    val categoryOverridesRestored: Int,
)

/**
 * Serializes ZiG's user data to / from a JSON stream for offline, permissionless
 * Backup & Restore via the Storage Access Framework. No network, no cloud — the caller
 * supplies a user-chosen stream from `contentResolver.open{Input,Output}Stream(uri)`.
 *
 * The payload carries everything needed to reproduce a user's setup on a new device:
 *  - the user-facing preference toggles,
 *  - the managed-apps list, keyword rules and per-app category overrides, and
 *  - the RAC (Retrieval-Augmented Classification) override corpus as TEXT + verdict.
 *
 * Restore is non-destructive (merge/union): it adds to the target device without removing
 * anything already there, and re-importing the same file is idempotent. Two details matter:
 *
 *  - Managed apps and keyword rules also live in the Rust filter engine ([NativeBridge]),
 *    which the notification service actually consults. Writing Room is not enough, so restore
 *    re-syncs the engine exactly as ZigApp does on cold start.
 *  - Each override is reconstructed as a synthetic review row tagged `systemDecision =
 *    "RESTORED"`, which keeps it out of the inbox/archive (they filter on real decision
 *    values) while still feeding the personal-memory and exact-match layers. Embeddings are
 *    recomputed on-device from the restored text — never carried in the file — so a backup
 *    stays valid even if the embedding model changes between export and import.
 */
@Singleton
class BackupRestoreManager @Inject constructor(
    private val dao: NotificationReviewDao,
    private val managedAppDao: ManagedAppDao,
    private val keywordRuleDao: KeywordRuleDao,
    private val categoryOverrideDao: AppCategoryOverrideDao,
    private val preferences: ZigUserPreferences,
    private val dailySummaryScheduler: DailySummaryScheduler,
    private val embedder: TextEmbedder,
    private val personalMemory: PersonalMemory,
) {
    // prettyPrint: the exported file is meant to be human-inspectable (privacy transparency).
    // ignoreUnknownKeys: a future build's optional fields must not break an older reader.
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Reads every override and the user preferences, serializes them, and writes the JSON
     * to [outputStream]. The stream is closed on completion. Runs off the main thread.
     */
    suspend fun createBackup(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val payload = BackupPayload(
            exportDate = System.currentTimeMillis() / 1000,
            preferences = BackupPreferences(
                dailySummaryEnabled = preferences.dailySummaryEnabled,
                sensitiveNotificationsEnabled = preferences.sensitiveNotificationsEnabled,
            ),
            managedApps = managedAppDao.getAllPackageNames(),
            keywordRules = keywordRuleDao.getAllSnapshot().map { it.conditions },
            categoryOverrides = categoryOverrideDao.getAllSnapshot().map {
                CategoryOverrideEntry(it.packageName, it.defaultCategory)
            },
            racMemory = dao.getAllOverrides().map { row ->
                RacMemoryEntry(
                    messageText = row.messageText,
                    userOverrideStatus = row.userOverrideStatus,
                    userAssignedCategory = row.userAssignedCategory,
                    packageName = row.packageName,
                    timestamp = row.timestamp,
                )
            },
        )
        outputStream.use { stream ->
            stream.write(json.encodeToString(BackupPayload.serializer(), payload).toByteArray())
        }
    }

    /**
     * Parses the JSON from [inputStream], applies preferences, and rebuilds the override
     * corpus. Parsing (and the version check) happen before any write, so a malformed or
     * too-new file leaves existing data untouched. The stream is closed on completion.
     *
     * @throws IncompatibleBackupVersionException if the file is newer than this build.
     * @throws kotlinx.serialization.SerializationException if the file is not valid backup JSON.
     */
    suspend fun restoreBackup(inputStream: InputStream): RestoreResult = withContext(Dispatchers.IO) {
        val text = inputStream.use { it.readBytes().decodeToString() }
        val payload = json.decodeFromString(BackupPayload.serializer(), text)
        if (payload.version > BackupPayload.CURRENT_VERSION) {
            throw IncompatibleBackupVersionException(payload.version)
        }

        // 1. Preferences. Re-run the daily-summary scheduler side-effect so the WorkManager
        //    job matches the imported toggle (writing the flag alone would leave them drifted).
        preferences.dailySummaryEnabled = payload.preferences.dailySummaryEnabled
        preferences.sensitiveNotificationsEnabled = payload.preferences.sensitiveNotificationsEnabled
        if (payload.preferences.dailySummaryEnabled) dailySummaryScheduler.schedule()
        else dailySummaryScheduler.cancel()

        // 2. Managed apps (merge). INSERT IGNORE dedupes by packageName; the Rust engine is the
        //    thing the service actually checks, so mirror each into it as ZigApp does on start.
        val existingApps = managedAppDao.getAllPackageNames().toSet()
        var managedAppsAdded = 0
        payload.managedApps.forEach { pkg ->
            managedAppDao.insert(ManagedAppEntity(pkg))
            NativeBridge.addAppToManaged(pkg)
            if (pkg !in existingApps) managedAppsAdded++
        }

        // 3. Keyword rules (merge, de-duplicated by their condition list). After writing, rebuild
        //    the Rust keyword set from the full Room snapshot so it reflects the merged rules.
        val existingRules = keywordRuleDao.getAllSnapshot().map { it.conditions }.toMutableSet()
        var keywordRulesAdded = 0
        payload.keywordRules.forEach { conditions ->
            if (conditions.isNotEmpty() && existingRules.add(conditions)) {
                keywordRuleDao.insert(KeywordRuleEntity(conditions = conditions))
                keywordRulesAdded++
            }
        }
        NativeBridge.clearKeywordWhitelist()
        keywordRuleDao.getAllSnapshot().forEach { rule ->
            NativeBridge.addKeywordRuleToWhitelist(rule.conditions.joinToString("||"))
        }

        // 4. Category overrides (upsert — REPLACE by packageName).
        payload.categoryOverrides.forEach {
            categoryOverrideDao.upsert(AppCategoryOverrideEntity(it.packageName, it.defaultCategory))
        }

        // 5. Override corpus. Drop any prior restore first so re-importing is idempotent.
        val entries = payload.racMemory
        val rows = entries.map { it.toRestoredEntity() }
        dao.deleteRestored()
        val ids = dao.restoreOverrides(rows)

        // 6. Re-embed each restored row from its text so it can rejoin the KNN corpus.
        //    A blank/failed embedding is skipped — the row still serves the exact-match layer.
        var embedded = 0
        entries.forEachIndexed { index, entry ->
            val vector = embedder.embed(entry.messageText) ?: return@forEachIndexed
            dao.updateEmbedding(ids[index], vector)
            embedded++
        }

        // 7. Refresh the in-memory corpus cache so restored memory takes effect immediately.
        personalMemory.reload()

        RestoreResult(
            overridesRestored = rows.size,
            overridesEmbedded = embedded,
            managedAppsAdded = managedAppsAdded,
            keywordRulesAdded = keywordRulesAdded,
            categoryOverridesRestored = payload.categoryOverrides.size,
        )
    }
}

/** systemDecision tag for restored rows — never produced by the live pipeline. */
internal const val RESTORED_DECISION = "RESTORED"
private const val RESTORED_JOB_PREFIX = "restore_"

// Builds a synthetic review row from a backup entry. id = 0 lets Room auto-assign a fresh
// primary key (never reuses the exporting device's ids). systemDecision = "RESTORED" hides
// it from the inbox/archive; syncStatus = EXPORTED keeps it out of the nightly training CSV
// (it was already the user's exported correction, not a new signal to learn from again).
// Kept as an internal top-level function so the restore invariants are unit-testable
// without an Android context.
internal fun RacMemoryEntry.toRestoredEntity(): NotificationReviewEntity =
    NotificationReviewEntity(
        id = 0,
        jobId = RESTORED_JOB_PREFIX + UUID.randomUUID(),
        packageName = packageName ?: "",
        title = "",
        content = "",
        timestamp = timestamp,
        systemDecision = RESTORED_DECISION,
        reviewState = ReviewState.PENDING,
        syncStatus = SyncStatus.EXPORTED,
        userAssignedCategory = userAssignedCategory,
        userOverrideStatus = userOverrideStatus,
        embedding = null,
        messageText = messageText,
    )
