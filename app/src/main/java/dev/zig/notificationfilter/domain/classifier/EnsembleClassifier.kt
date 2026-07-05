package dev.zig.notificationfilter.domain.classifier

import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import dev.zig.notificationfilter.domain.memory.Neighbor
import dev.zig.notificationfilter.domain.memory.PersonalMemory
import dev.zig.notificationfilter.domain.memory.VectorSearchEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retrieval-Augmented Classification: a weighted ensemble of the base TFLite model
 * (Base Instinct) and the user's Personal Memory of past overrides.
 *
 * The base model always produces an initial ALLOW/BLOCK verdict. Personal Memory may then
 * VETO that verdict — in either direction — but only under strict guards, so a sparse or
 * ambiguous memory can never destabilise the base model:
 *
 *   1. At least [MIN_NEIGHBORS] embedded overrides must be retrieved.
 *   2. The single nearest neighbour must be at least [HIGH_SIMILARITY] similar.
 *   3. The winning label's similarity-weighted share must be at least [CONSENSUS_SHARE].
 *
 * When all three hold, the memory consensus becomes the verdict and the decision is attributed
 * to [DecisionSource.PERSONAL_MEMORY]; otherwise the base verdict stands as
 * [DecisionSource.BASE_MODEL]. If embedding is unavailable, it fails open to the base model.
 */
@Singleton
class EnsembleClassifier @Inject constructor(
    private val baseEngine: ZigClassifierEngine,
    private val embedder: TextEmbedder,
    private val memory: PersonalMemory,
    private val searchEngine: VectorSearchEngine,
    private val reviewDao: NotificationReviewDao,
) {

    companion object {
        // Number of nearest neighbours retrieved from Personal Memory.
        const val K = 5

        // A memory veto requires the closest neighbour to be at least this similar (cosine).
        const val HIGH_SIMILARITY = 0.85f

        // ...and the winning label to hold at least this similarity-weighted share of the vote.
        const val CONSENSUS_SHARE = 0.75f

        // ...and at least this many neighbours to be present (guards against cold start).
        const val MIN_NEIGHBORS = 3
    }

    suspend fun evaluate(
        category: NotificationCategory,
        packageName: String,
        text: String,
    ): EnsembleResult {
        // ── Layer 0: Exact-match cache ─────────────────────────────────────────
        // If the user has previously overridden this exact text, replay that decision
        // immediately. No embedding, no TFLite, no KNN — zero battery spend.
        val exactMatch = reviewDao.getExactMatchOverride(text)
        if (exactMatch != null) {
            val allowed = exactMatch.userOverrideStatus == "MANUALLY_ALLOWED"
            return EnsembleResult(
                allowed = allowed,
                source = DecisionSource.EXACT_MATCH_OVERRIDE,
                baseConfidence = 0f,
                category = category,
                topSimilarity = 1f,
                consensusShare = 1f,
                neighborCount = 1,
            )
        }

        val base = baseEngine.evaluate(category, packageName, text)

        // Fail open to the base model when we cannot embed (blank text, embedder unavailable).
        val query = embedder.embed(text)
            ?: return base.asEnsemble(source = DecisionSource.BASE_MODEL, neighbors = emptyList())

        val neighbors = searchEngine.search(query, memory.corpus(), K)
        if (neighbors.size < MIN_NEIGHBORS) {
            return base.asEnsemble(source = DecisionSource.BASE_MODEL, neighbors = neighbors)
        }

        val blockWeight = neighbors.filter { it.blocked }.sumOf { it.similarity.toDouble() }
        val allowWeight = neighbors.filterNot { it.blocked }.sumOf { it.similarity.toDouble() }
        val totalWeight = blockWeight + allowWeight
        val memoryBlocked = blockWeight >= allowWeight
        val winnerShare = if (totalWeight == 0.0) 0.0 else {
            (if (memoryBlocked) blockWeight else allowWeight) / totalWeight
        }

        val topSimilarity = neighbors.first().similarity
        val veto = topSimilarity >= HIGH_SIMILARITY && winnerShare >= CONSENSUS_SHARE

        return if (veto) {
            EnsembleResult(
                allowed = !memoryBlocked,
                source = DecisionSource.PERSONAL_MEMORY,
                baseConfidence = base.confidence,
                category = base.category,
                topSimilarity = topSimilarity,
                consensusShare = winnerShare.toFloat(),
                neighborCount = neighbors.size,
            )
        } else {
            base.asEnsemble(source = DecisionSource.BASE_MODEL, neighbors = neighbors)
        }
    }

    // Wraps a base ClassifierResult as an EnsembleResult, attaching whatever neighbour
    // evidence was gathered (possibly none) for logging.
    private fun ClassifierResult.asEnsemble(
        source: DecisionSource,
        neighbors: List<Neighbor>,
    ): EnsembleResult = EnsembleResult(
        allowed = allowed,
        source = source,
        baseConfidence = confidence,
        category = category,
        topSimilarity = neighbors.firstOrNull()?.similarity ?: 0f,
        consensusShare = 0f,
        neighborCount = neighbors.size,
    )
}
