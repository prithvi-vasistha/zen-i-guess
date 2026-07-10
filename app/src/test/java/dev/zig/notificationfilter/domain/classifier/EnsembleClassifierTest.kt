package dev.zig.notificationfilter.domain.classifier

import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import dev.zig.notificationfilter.domain.memory.MemoryVector
import dev.zig.notificationfilter.domain.memory.PersonalMemory
import dev.zig.notificationfilter.domain.memory.VectorSearchEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EnsembleClassifierTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeBaseEngine(private val allowed: Boolean, private val confidence: Float) :
        ZigClassifierEngine {
        override suspend fun evaluate(
            category: NotificationCategory,
            packageName: String,
            text: String,
        ): ClassifierResult = ClassifierResult(allowed, confidence, category)
    }

    private class FakeEmbedder(private val vector: FloatArray?) : TextEmbedder {
        override suspend fun embed(text: String): FloatArray? = vector
    }

    private class FakeMemory(private val vectors: List<MemoryVector>) : PersonalMemory {
        override suspend fun corpus(): List<MemoryVector> = vectors
        override suspend fun rememberOverride(id: Long) = Unit
        override suspend fun forgetOverride(id: Long) = Unit
        override suspend fun reload() = Unit
        override suspend fun clearAllMemory() = Unit
    }

    // getExactMatchOverride resolves against [overridesByKey] (key -> userOverrideStatus); every
    // other method is inert. An empty map means the cache always misses, so the ensemble falls
    // through to the base + memory logic the other tests exercise.
    private class FakeReviewDao(
        private val overridesByKey: Map<String, String> = emptyMap(),
    ) : NotificationReviewDao {
        override suspend fun insert(entity: NotificationReviewEntity) = Unit
        override fun getFilteredNotificationsFlow(cutoffTimestamp: Long): Flow<List<NotificationReviewEntity>> = emptyFlow()
        override fun getPendingReviewFlow(): Flow<List<NotificationReviewEntity>> = emptyFlow()
        override suspend fun updateReviewState(id: Long, state: String) = Unit
        override suspend fun setReviewStateOnly(id: Long, state: String) = Unit
        override suspend fun updateOverrideStatus(id: Long, status: String) = Unit
        override suspend fun updateUserAssignedCategory(id: Long, category: String?) = Unit
        override suspend fun getById(id: Long): NotificationReviewEntity? = null
        override suspend fun updateEmbedding(id: Long, embedding: FloatArray?) = Unit
        override suspend fun getPersonalMemory(): List<NotificationReviewEntity> = emptyList()
        override fun getArchivedNotificationsFlow(cutoffTimestamp: Long): Flow<List<NotificationReviewEntity>> = emptyFlow()
        override fun searchActiveFlow(cutoffTimestamp: Long, query: String): Flow<List<NotificationReviewEntity>> = emptyFlow()
        override fun searchArchiveFlow(archiveCutoffTimestamp: Long, query: String): Flow<List<NotificationReviewEntity>> = emptyFlow()
        override suspend fun countReviewableToday(startOfDayMs: Long): Int = 0
        override suspend fun getUnprocessedReviews(): List<NotificationReviewEntity> = emptyList()
        override suspend fun markAsExported(ids: List<Long>) = Unit
        override suspend fun getExactMatchOverride(text: String): NotificationReviewEntity? =
            overridesByKey[text]?.let { status ->
                NotificationReviewEntity(
                    jobId = "test", packageName = "com.example.app", title = "", content = "",
                    timestamp = 0L, systemDecision = "MODEL_BLOCKED",
                    userOverrideStatus = status, messageText = text,
                )
            }
        override suspend fun getAllOverrides(): List<NotificationReviewEntity> = emptyList()
        override suspend fun restoreOverrides(overrides: List<NotificationReviewEntity>): List<Long> = emptyList()
        override suspend fun deleteRestored() = Unit
        override suspend fun clearAiMemory() = Unit
        override suspend fun cascadeOverride(
            packageName: String,
            title: String,
            content: String,
            state: String,
            status: String,
            cutoff: Long,
            tappedId: Long,
        ) = Unit
    }

    // Regression test for the messaging-app cache-key bug: the base model would ALLOW and the
    // full conversation text has grown ("~ppv Hi\nHi"), but the stable per-message exactMatchKey
    // ("~ppv Hi") matches a prior manual block, so the block is replayed without invoking the model.
    @Test
    fun `exact-match key replays a manual block even as the conversation grows`() {
        val classifier = EnsembleClassifier(
            baseEngine = FakeBaseEngine(allowed = true, confidence = 0.0f),
            embedder = FakeEmbedder(null),
            memory = FakeMemory(emptyList()),
            searchEngine = VectorSearchEngine(),
            reviewDao = FakeReviewDao(mapOf("~ppv Hi" to "MANUALLY_BLOCKED")),
        )
        val result = runBlocking {
            classifier.evaluate(
                category = NotificationCategory.UNKNOWN,
                packageName = "com.example.app",
                text = "~ppv Hi\nHi",
                exactMatchKey = "~ppv Hi",
            )
        }
        assertEquals(DecisionSource.EXACT_MATCH_OVERRIDE, result.source)
        assertEquals(false, result.allowed)
    }

    private fun ensemble(
        baseAllowed: Boolean,
        baseConfidence: Float,
        query: FloatArray?,
        corpus: List<MemoryVector>,
    ) = EnsembleClassifier(
        baseEngine = FakeBaseEngine(baseAllowed, baseConfidence),
        embedder = FakeEmbedder(query),
        memory = FakeMemory(corpus),
        searchEngine = VectorSearchEngine(),
        reviewDao = FakeReviewDao(),
    )

    private fun evaluate(classifier: EnsembleClassifier) = runBlocking {
        classifier.evaluate(NotificationCategory.UNKNOWN, "com.example.app", "some text")
    }

    // A vector nearly identical to [base] so cosine clears the HIGH_SIMILARITY gate.
    private fun near(base: FloatArray): FloatArray = FloatArray(base.size) { base[it] + 0.001f }

    private val query = floatArrayOf(1f, 0f, 0f, 0f)

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `no embedding falls open to base model`() {
        val result = evaluate(ensemble(baseAllowed = false, baseConfidence = 0.9f, query = null, corpus = emptyList()))

        assertEquals(DecisionSource.BASE_MODEL, result.source)
        assertEquals(false, result.allowed)
    }

    @Test
    fun `cold start below min neighbors keeps base verdict`() {
        // Only two neighbours present; MIN_NEIGHBORS is 3.
        val corpus = listOf(
            MemoryVector(near(query), blocked = true),
            MemoryVector(near(query), blocked = true),
        )
        val result = evaluate(ensemble(baseAllowed = true, baseConfidence = 0.1f, query = query, corpus = corpus))

        assertEquals(DecisionSource.BASE_MODEL, result.source)
        assertEquals(true, result.allowed)
    }

    @Test
    fun `strong block consensus vetoes a base allow`() {
        val corpus = List(4) { MemoryVector(near(query), blocked = true) }
        val result = evaluate(ensemble(baseAllowed = true, baseConfidence = 0.2f, query = query, corpus = corpus))

        assertEquals(DecisionSource.PERSONAL_MEMORY, result.source)
        assertEquals(false, result.allowed)
        // Base confidence is preserved for telemetry even when memory overrides.
        assertEquals(0.2f, result.baseConfidence, 1e-6f)
    }

    @Test
    fun `strong allow consensus vetoes a base block (bidirectional)`() {
        val corpus = List(4) { MemoryVector(near(query), blocked = false) }
        val result = evaluate(ensemble(baseAllowed = false, baseConfidence = 0.8f, query = query, corpus = corpus))

        assertEquals(DecisionSource.PERSONAL_MEMORY, result.source)
        assertEquals(true, result.allowed)
    }

    @Test
    fun `high similarity but split consensus keeps base verdict`() {
        // 3 block + 3 allow, all highly similar → winner share 0.5 < CONSENSUS_SHARE.
        val corpus = List(3) { MemoryVector(near(query), blocked = true) } +
            List(3) { MemoryVector(near(query), blocked = false) }
        val result = evaluate(ensemble(baseAllowed = true, baseConfidence = 0.3f, query = query, corpus = corpus))

        assertEquals(DecisionSource.BASE_MODEL, result.source)
        assertEquals(true, result.allowed)
    }

    @Test
    fun `strong consensus but low similarity keeps base verdict`() {
        // Orthogonal neighbours: unanimous block label but cosine ~0 < HIGH_SIMILARITY.
        val orthogonal = floatArrayOf(0f, 1f, 0f, 0f)
        val corpus = List(4) { MemoryVector(orthogonal, blocked = true) }
        val result = evaluate(ensemble(baseAllowed = true, baseConfidence = 0.4f, query = query, corpus = corpus))

        assertEquals(DecisionSource.BASE_MODEL, result.source)
        assertEquals(true, result.allowed)
    }
}
