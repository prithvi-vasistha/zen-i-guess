package dev.zig.notificationfilter.domain.classifier

import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import dev.zig.notificationfilter.domain.memory.MemoryVector
import dev.zig.notificationfilter.domain.memory.PersonalMemory
import dev.zig.notificationfilter.domain.memory.VectorSearchEngine
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
