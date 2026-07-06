package dev.zig.notificationfilter.domain.backup

import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.data.local.db.ReviewState
import dev.zig.notificationfilter.data.local.db.SyncStatus
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import dev.zig.notificationfilter.domain.memory.PersonalMemory
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
)

/**
 * Serializes ZiG's user data to / from a JSON stream for offline, permissionless
 * Backup & Restore via the Storage Access Framework. No network, no cloud — the caller
 * supplies a user-chosen stream from `contentResolver.open{Input,Output}Stream(uri)`.
 *
 * Two data sets travel in the payload:
 *  - the two user-facing preference toggles, and
 *  - the RAC (Retrieval-Augmented Classification) override corpus as TEXT + verdict.
 *
 * On restore, each override is reconstructed as a synthetic review row tagged
 * `systemDecision = "RESTORED"`. That tag keeps it out of the inbox and archive (whose
 * queries filter on the real decision values) while still feeding the personal-memory and
 * exact-match layers (which key only on `userOverrideStatus` / `messageText`). Embeddings
 * are recomputed on-device from the restored text — never carried in the file — so a backup
 * stays valid even if the embedding model changes between export and import.
 */
@Singleton
class BackupRestoreManager @Inject constructor(
    private val dao: NotificationReviewDao,
    private val preferences: ZigUserPreferences,
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

        // 1. Preferences.
        preferences.dailySummaryEnabled = payload.preferences.dailySummaryEnabled
        preferences.sensitiveNotificationsEnabled = payload.preferences.sensitiveNotificationsEnabled

        // 2. Override corpus. Drop any prior restore first so re-importing is idempotent.
        val entries = payload.racMemory
        val rows = entries.map { it.toRestoredEntity() }
        dao.deleteRestored()
        val ids = dao.restoreOverrides(rows)

        // 3. Re-embed each restored row from its text so it can rejoin the KNN corpus.
        //    A blank/failed embedding is skipped — the row still serves the exact-match layer.
        var embedded = 0
        entries.forEachIndexed { index, entry ->
            val vector = embedder.embed(entry.messageText) ?: return@forEachIndexed
            dao.updateEmbedding(ids[index], vector)
            embedded++
        }

        // 4. Refresh the in-memory corpus cache so restored memory takes effect immediately.
        personalMemory.reload()

        RestoreResult(overridesRestored = rows.size, overridesEmbedded = embedded)
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
