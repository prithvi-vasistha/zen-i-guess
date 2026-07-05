package dev.zig.notificationfilter.domain.classifier

/** Which layer of the ensemble produced the final verdict. */
enum class DecisionSource {
    /** The TFLite base model's verdict stands (Base Instinct). */
    BASE_MODEL,

    /** Personal Memory reached a confident consensus and overrode the base model. */
    PERSONAL_MEMORY,

    /** Exact-match cache: the user previously overrode this identical text — replayed instantly. */
    EXACT_MATCH_OVERRIDE,
}

/**
 * Outcome of the Retrieval-Augmented ensemble.
 *
 * [allowed] is the final verdict the pipeline acts on. [source] cleanly indicates whether that
 * verdict came from the base model or was vetoed by Personal Memory, and the remaining fields
 * carry the evidence behind the decision for logging and debugging.
 */
data class EnsembleResult(
    val allowed: Boolean,
    val source: DecisionSource,
    /** Base model P(BLOCK) sigmoid — preserved for active-learning telemetry regardless of source. */
    val baseConfidence: Float,
    val category: NotificationCategory,
    /** Cosine similarity of the single nearest neighbour, or 0 when the corpus was empty. */
    val topSimilarity: Float,
    /** Weighted share of the winning label among the retrieved neighbours (0..1). */
    val consensusShare: Float,
    /** Number of neighbours actually retrieved (may be fewer than K on a small corpus). */
    val neighborCount: Int,
)
