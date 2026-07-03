package dev.zig.notificationfilter.domain.memory

import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [PersonalMemory] backed by Room + [TextEmbedder].
 *
 * The corpus is small (bounded by the number of manual overrides) so it is cached in memory
 * and only re-read from the database after an override changes it. This avoids a full-table
 * scan on every incoming notification while keeping the cache trivially consistent: any write
 * that could change the corpus invalidates it, and the next read reloads from the DB.
 */
@Singleton
class PersonalMemoryRepository @Inject constructor(
    private val dao: NotificationReviewDao,
    private val embedder: TextEmbedder,
) : PersonalMemory {

    private val mutex = Mutex()
    @Volatile private var cache: List<MemoryVector>? = null

    override suspend fun corpus(): List<MemoryVector> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: loadCorpus().also { cache = it }
        }
    }

    private suspend fun loadCorpus(): List<MemoryVector> =
        dao.getPersonalMemory().mapNotNull { row ->
            val vector = row.embedding ?: return@mapNotNull null
            MemoryVector(embedding = vector, blocked = row.userOverrideStatus == "MANUALLY_BLOCKED")
        }

    override suspend fun rememberOverride(id: Long) {
        val row = dao.getById(id) ?: return
        val embedding = embedder.embed(embeddingText(row)) ?: return
        dao.updateEmbedding(id, embedding)
        invalidate()
    }

    override suspend fun forgetOverride(id: Long) {
        dao.updateEmbedding(id, null)
        invalidate()
    }

    private fun invalidate() {
        cache = null
    }

    private companion object {
        // Same text the classifier/embedder sees at inference time (see the service pipeline):
        // title and content joined by a space, blanks dropped. Keeping this identical ensures a
        // stored override embeds to the same space a live notification is searched against.
        fun embeddingText(row: NotificationReviewEntity): String =
            listOf(row.title, row.content).filter { it.isNotBlank() }.joinToString(" ")
    }
}
