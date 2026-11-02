package dev.zig.notificationfilter.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@Dao
interface NotificationReviewDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: NotificationReviewEntity)

    @Query("SELECT * FROM notification_review WHERE jobId = :jobId LIMIT 1")
    suspend fun getByJobId(jobId: String): NotificationReviewEntity?

    @Update
    suspend fun update(entity: NotificationReviewEntity)

    // A deferred notification becomes one completed card, preserving any existing user state.
    @Transaction
    suspend fun upsertPipeline(entity: NotificationReviewEntity) {
        val previous = getByJobId(entity.jobId)
        if (previous == null) insert(entity) else update(previous.copy(
            title = entity.title, content = entity.content, systemDecision = entity.systemDecision,
            modelConfidence = entity.modelConfidence, inferredCategory = entity.inferredCategory,
            messageText = entity.messageText, deliveryStatus = entity.deliveryStatus,
            deliveryDetail = entity.deliveryDetail,
        ))
    }

    @Query("UPDATE notification_review SET deliveryStatus = :status, deliveryDetail = :detail WHERE jobId = :jobId")
    suspend fun updateDelivery(jobId: String, status: String, detail: String)

    // Active review inbox: classifier-suppressed notifications within the rolling 24-hour window.
    // MANAGED_FAIL rows are excluded — those notifications never passed the managed-apps gate
    // so they are not meaningful candidates for user review.
    // cutoffTimestamp = System.currentTimeMillis() - 24h, recomputed hourly by the ViewModel.
    // IN clause covers legacy LLM_BLOCKED rows (Phase 1) and new MODEL_BLOCKED rows (Phase 2+).
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED')
        AND reviewState != 'DISMISSED'
        AND timestamp >= :cutoffTimestamp
        ORDER BY timestamp DESC
    """)
    fun getFilteredNotificationsFlow(cutoffTimestamp: Long): Flow<List<NotificationReviewEntity>>

    // Unreviewed suppressed notifications only — useful for badge counts and "needs action" views.
    @Query("""
        SELECT * FROM notification_review
        WHERE reviewState = 'PENDING'
        AND systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED')
        ORDER BY timestamp DESC
    """)
    fun getPendingReviewFlow(): Flow<List<NotificationReviewEntity>>

    // Updating reviewState also resets syncStatus to UNPROCESSED so a user changing
    // their mind (ALLOWED → BLOCKED) is re-exported to the training dataset.
    @Query("UPDATE notification_review SET reviewState = :state, syncStatus = 'UNPROCESSED' WHERE id = :id")
    suspend fun updateReviewState(id: Long, state: String)

    // Sets reviewState WITHOUT touching syncStatus or userOverrideStatus. Used by swipe-to-
    // dismiss (and its Undo), which only hide/restore a row in the inbox and must never
    // manufacture a training signal the way an explicit Allow/Block does.
    @Query("UPDATE notification_review SET reviewState = :state WHERE id = :id")
    suspend fun setReviewStateOnly(id: Long, state: String)

    @Query("UPDATE notification_review SET userOverrideStatus = :status WHERE id = :id")
    suspend fun updateOverrideStatus(id: Long, status: String)

    @Query("UPDATE notification_review SET userAssignedCategory = :category WHERE id = :id")
    suspend fun updateUserAssignedCategory(id: Long, category: String?)

    // ── Personal Memory (RAC / KNN) ───────────────────────────────────────────

    // Single-row fetch used by PersonalMemoryRepository to read a notification's
    // text before embedding it at override time.
    @Query("SELECT * FROM notification_review WHERE id = :id")
    suspend fun getById(id: Long): NotificationReviewEntity?

    // Bind a single BLOB: Room treats a FloatArray query parameter as a list and expands
    // it into SQL placeholders, even though entity fields use FloatArrayConverter.
    @Query("UPDATE notification_review SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbeddingBlob(id: Long, embedding: ByteArray?)

    suspend fun updateEmbedding(id: Long, embedding: FloatArray?) {
        updateEmbeddingBlob(id, FloatArrayConverter().fromFloatArray(embedding))
    }

    @Query("UPDATE notification_review SET messageText = :text WHERE id = :id")
    suspend fun updateMessageText(id: Long, text: String)

    // The vector-search corpus: every row the user has explicitly overridden AND
    // for which an embedding has been computed. Bounded by the number of manual
    // overrides, so an in-memory cosine scan over this set is cheap.
    @Query("""
        SELECT * FROM notification_review
        WHERE userOverrideStatus IN ('MANUALLY_ALLOWED', 'MANUALLY_BLOCKED')
        AND embedding IS NOT NULL
    """)
    suspend fun getPersonalMemory(): List<NotificationReviewEntity>

    // Classifier-blocked notifications older than the 24-hour active window — shown on the Archive screen.
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED')
        AND timestamp < :cutoffTimestamp
        ORDER BY timestamp DESC
    """)
    fun getArchivedNotificationsFlow(cutoffTimestamp: Long): Flow<List<NotificationReviewEntity>>

    // Search across packageName, title, and content with a LIKE '%query%' match.
    // Applied to the active window (timestamp >= cutoffTimestamp) for the inbox, and
    // to the archive window (timestamp >= archiveCutoffTimestamp) for the archive screen.
    // Sorting is applied in Kotlin on the emitted list — see ReviewFilter / SortBy.
    // Phase D: IN clause expanded to include PUBLISHED so the review screen shows
    // both model-blocked and model-allowed notifications with context-aware actions.
    // v3: the active inbox additionally surfaces deterministic rule matches
    // (CONTACT_PASS / KEYWORD_PASS / KEYWORD_BLOCKED / IMAGE_BLOCKED) as read-only badges, so the last 24h
    // is a complete picture of everything the managed-apps pipeline did.
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED', 'PUBLISHED',
                                 'CONTACT_PASS', 'KEYWORD_PASS', 'KEYWORD_BLOCKED', 'IMAGE_BLOCKED',
                                 'LOCKED_PASS', 'LOCKED_DEFERRED', 'CONTENT_HIDDEN', 'PIPELINE_ERROR')
        AND reviewState != 'DISMISSED'
        AND timestamp >= :cutoffTimestamp
        AND (packageName LIKE '%' || :query || '%'
          OR title      LIKE '%' || :query || '%'
          OR content    LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
    """)
    fun searchActiveFlow(cutoffTimestamp: Long, query: String): Flow<List<NotificationReviewEntity>>

    // The "Train" screen (formerly Archive) = last 30 days. AI decisions ONLY
    // (LLM_BLOCKED / MODEL_BLOCKED / PUBLISHED): these are the rows whose verdict the user can
    // correct to fine-tune the on-device model. Deterministic rule matches are immutable and so
    // deliberately excluded here — they live only in the 24h inbox. Overlaps with the inbox, so
    // today's AI-decided notifications appear in both.
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED', 'PUBLISHED')
        AND timestamp >= :archiveCutoffTimestamp
        AND (packageName LIKE '%' || :query || '%'
          OR title      LIKE '%' || :query || '%'
          OR content    LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
    """)
    fun searchArchiveFlow(archiveCutoffTimestamp: Long, query: String): Flow<List<NotificationReviewEntity>>

    // ── Cascading state synchronisation (v3) ───────────────────────────────────
    // Applies one Allow/Block decision to the tapped row (always, via id = :tappedId, so
    // corrections work on the 30-day Train screen too) plus every identical notification
    // (same package, title, and content) still inside the active 24h window. Bounding the
    // co-update by :cutoff keeps the cascade to recent duplicates; older duplicates keep their
    // prior state. syncStatus is reset so a changed decision is re-exported to the training
    // dataset (mirrors updateReviewState). The DB Flow re-emits, so all matching cards update
    // in the UI without a separate in-memory list.
    @Query("""
        UPDATE notification_review
        SET reviewState = :state, syncStatus = 'UNPROCESSED', userOverrideStatus = :status
        WHERE packageName = :packageName AND title = :title AND content = :content
        AND (timestamp >= :cutoff OR id = :tappedId)
    """)
    suspend fun cascadeOverride(
        packageName: String,
        title: String,
        content: String,
        state: String,
        status: String,
        cutoff: Long,
        tappedId: Long,
    )

    // Count of reviewable notifications (MODEL_BLOCKED + PUBLISHED) with a timestamp on or
    // after startOfDayMs. Retained for existing count consumers.
    @Query("""
        SELECT COUNT(*) FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED', 'PUBLISHED')
        AND timestamp >= :startOfDayMs
    """)
    suspend fun countReviewableToday(startOfDayMs: Long): Int

    // The recap belongs to its scheduled date even if Android delivers it after midnight.
    @Query("""
        SELECT COUNT(*) FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED', 'PUBLISHED')
        AND timestamp >= :startOfDayMs AND timestamp <= :scheduledAtMs
        AND jobId NOT LIKE 'demo-%'
    """)
    suspend fun countReviewableBetween(startOfDayMs: Long, scheduledAtMs: Long): Int

    // Called by the retraining WorkManager to fetch only rows that need to be
    // written to the master training CSV — avoids scanning the full table nightly.
    @Query("SELECT * FROM notification_review WHERE syncStatus = 'UNPROCESSED'")
    suspend fun getUnprocessedReviews(): List<NotificationReviewEntity>

    // Called after the WorkManager successfully appends a batch to the training CSV.
    @Query("UPDATE notification_review SET syncStatus = 'EXPORTED' WHERE id IN (:ids)")
    suspend fun markAsExported(ids: List<Long>)

    // ── Exact-match cache ──────────────────────────────────────────────────────
    // Looks up the most recent row the user explicitly overrode (MANUALLY_ALLOWED or
    // MANUALLY_BLOCKED) whose messageText equals :text. The NOCASE collation on the
    // column makes this query case-insensitive while still using the B-Tree index —
    // no LOWER() needed.
    @Query("""
        SELECT * FROM notification_review
        WHERE messageText = :text
        AND userOverrideStatus != 'NONE'
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getExactMatchOverride(text: String): NotificationReviewEntity?

    // ── Backup / Restore ───────────────────────────────────────────────────────

    // Every real row the user has explicitly overridden — the exportable RAC corpus.
    // Older database versions did not store messageText; their title/content can still
    // reconstruct the learned text. Demo rows (jobId 'demo-…') are
    // seeded illustrations, not the user's data, and are excluded too — mirroring the way
    // PersonalMemoryRepository already skips them from the KNN corpus.
    @Query("""
        SELECT * FROM notification_review
        WHERE userOverrideStatus != 'NONE'
        AND (messageText != '' OR title != '' OR content != '')
        AND jobId NOT LIKE 'demo-%'
    """)
    suspend fun getAllOverrides(): List<NotificationReviewEntity>

    // Restored rows always use fresh ids and are merged without replacing local history.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreOverrides(overrides: List<NotificationReviewEntity>): List<Long>

    // One transaction preserves earlier imports and makes repeated imports idempotent.
    // Return ids in input order, including existing rows, so an interrupted import can
    // resume embedding without inserting a second copy of any saved decision.
    @Transaction
    suspend fun mergeRestoredOverrides(overrides: List<NotificationReviewEntity>): List<Long> {
        // A local correction to the same original message wins over the older backup's
        // verdict/category. Prefer real history if a previous build imported a duplicate.
        val existing = getAllOverrides().sortedBy { it.systemDecision != "RESTORED" }
            .associate { it.overrideIdentity() to it.id }.toMutableMap()
        return overrides.map { row ->
            val id = existing.getOrPut(row.overrideIdentity()) { restoreOverrides(listOf(row)).single() }
            if (getById(id)?.messageText.isNullOrBlank()) updateMessageText(id, row.messageText)
            id
        }
    }

    // ── AI Memory reset ────────────────────────────────────────────────────────
    // Wipes all learned state from the notification_review table: clears embeddings,
    // resets user override decisions, and nulls category assignments. Demo rows
    // (jobId 'demo-…') are excluded because they are seeded illustrations, not user data.
    // The managed_app and keyword_rule tables are never referenced here.
    @Query("""
        UPDATE notification_review
        SET embedding = NULL,
            userOverrideStatus = 'NONE',
            userAssignedCategory = NULL
        WHERE jobId NOT LIKE 'demo-%'
    """)
    suspend fun clearAiMemory()

    // ── Daily Review (Phase 3) ─────────────────────────────────────────────────
    // Today's AI-evaluated notifications the user hasn't decided on yet — the source for the
    // gamified flashcard training deck. AI decisions only (LLM_BLOCKED / MODEL_BLOCKED /
    // PUBLISHED); deterministic rule matches are immutable and excluded. Only undecided rows
    // (userOverrideStatus = 'NONE') so the deck represents "still needs your input" and reaches
    // a true Inbox Zero. Demo rows are seeded illustrations, not user data, and are excluded.
    // Returned as a one-shot snapshot (not a Flow) so applying decisions can't reshuffle the
    // deck mid-swipe. Grouping/dedup happens in the ViewModel.
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED', 'PUBLISHED')
        AND userOverrideStatus = 'NONE'
        AND timestamp >= :startOfDayMs
        AND jobId NOT LIKE 'demo-%'
        ORDER BY timestamp DESC
    """)
    suspend fun getTodayAiEvaluatedUndecided(startOfDayMs: Long): List<NotificationReviewEntity>

    // Applies one Daily-Review swipe decision to every row in a deduplicated group at once
    // (all the identical notifications the card collapsed). syncStatus is reset so the decision
    // is re-exported to the training dataset, mirroring updateReviewState / cascadeOverride.
    @Query("""
        UPDATE notification_review
        SET reviewState = :state, syncStatus = 'UNPROCESSED', userOverrideStatus = :status
        WHERE id IN (:ids)
    """)
    suspend fun applyDecisionToIds(ids: List<Long>, state: String, status: String)

    // Live count of DEDUPLICATED cards in today's training deck — drives the "Daily Review"
    // call-to-action (banner on the inbox + button on the Train screen), which is hidden when
    // this is 0. Same predicate as getTodayAiEvaluatedUndecided; the GROUP BY collapses identical
    // notifications so the count matches the number of cards the user will actually swipe.
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT packageName FROM notification_review
            WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED', 'PUBLISHED')
            AND userOverrideStatus = 'NONE'
            AND timestamp >= :startOfDayMs
            AND jobId NOT LIKE 'demo-%'
            GROUP BY packageName, title, content
        )
    """)
    fun countTodayAiUndecidedFlow(startOfDayMs: Long): Flow<Int>
}

private data class OverrideIdentity(
    val text: String,
    val packageName: String,
    val timestamp: Long,
)

private fun NotificationReviewEntity.overrideIdentity() = OverrideIdentity(
    messageText.ifBlank { listOf(title, content).filter(String::isNotBlank).joinToString(" ") }
        .lowercase(Locale.ROOT),
    packageName, timestamp,
)
