package dev.zig.notificationfilter.domain.memory

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** One entry in the Personal Memory corpus: a past user override and its embedding. */
data class MemoryVector(
    val embedding: FloatArray,
    /** true = user MANUALLY_BLOCKED this notification; false = MANUALLY_ALLOWED. */
    val blocked: Boolean,
)

/** A corpus entry ranked by its cosine similarity to a query embedding. */
data class Neighbor(
    val similarity: Float,
    val blocked: Boolean,
)

/**
 * In-memory K-Nearest-Neighbours search over the Personal Memory corpus.
 *
 * Cosine similarity is computed defensively (dividing by the vector norms) rather than
 * assuming unit-length inputs, so the engine is correct even if an un-normalised vector
 * ever reaches it. For the L2-normalised embeddings we actually store, this reduces to a
 * dot product at negligible extra cost for ~100-d vectors.
 *
 * Pure and stateless — all thresholds and the ensemble decision live in EnsembleClassifier.
 */
@Singleton
class VectorSearchEngine @Inject constructor() {

    /** Returns the [k] most similar corpus entries to [query], highest similarity first. */
    fun search(query: FloatArray, corpus: List<MemoryVector>, k: Int): List<Neighbor> {
        if (query.isEmpty() || corpus.isEmpty() || k <= 0) return emptyList()
        return corpus.asSequence()
            .filter { it.embedding.size == query.size }
            .map { Neighbor(cosineSimilarity(query, it.embedding), it.blocked) }
            .sortedByDescending { it.similarity }
            .take(k)
            .toList()
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
