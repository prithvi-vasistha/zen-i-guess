package dev.zig.notificationfilter.domain.memory

/**
 * The user's Personal Memory: the corpus of past manual overrides used to retrieve
 * similar historical decisions during ensemble classification.
 *
 * Kept as an interface so the ensemble and the ViewModel depend on a small surface that
 * is trivial to fake in unit tests, while the concrete repository owns embedding + caching.
 */
interface PersonalMemory {

    /** The current override corpus (embedded rows only). Cached in memory between overrides. */
    suspend fun corpus(): List<MemoryVector>

    /**
     * Records a user override for review row [id]: embeds the notification's text and persists
     * the vector so the row joins the corpus. The block/allow direction is read from the row's
     * stored userOverrideStatus, so the caller must update that status before calling this.
     */
    suspend fun rememberOverride(id: Long)

    /** Drops review row [id] from the corpus (e.g. on undo) by clearing its embedding. */
    suspend fun forgetOverride(id: Long)

    /**
     * Invalidates the cached corpus so the next [corpus] call re-reads from the database.
     * Called after a bulk change the per-row hooks don't cover — e.g. a backup restore that
     * writes many override rows and their embeddings directly.
     */
    suspend fun reload()

    /**
     * Wipes all learned AI state: clears every embedding vector and resets every user override
     * decision in the database, then invalidates the in-memory corpus cache. The next incoming
     * notification will find an empty corpus and fall back to the base model and rules engine.
     * Managed apps and keyword rules are never touched.
     */
    suspend fun clearAllMemory()
}
