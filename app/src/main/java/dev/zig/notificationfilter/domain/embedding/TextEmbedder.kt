package dev.zig.notificationfilter.domain.embedding

/**
 * Converts notification text into a dense, L2-normalised embedding vector, fully on-device.
 *
 * Implementations must make no network calls. Because embeddings are L2-normalised,
 * cosine similarity between two vectors reduces to their dot product.
 */
interface TextEmbedder {

    /**
     * Embeds [text], returning a normalised float vector, or `null` when the text is blank
     * or the embedder is unavailable. Callers treat `null` as "no embedding" and fall back
     * to the base model rather than failing the pipeline.
     */
    suspend fun embed(text: String): FloatArray?
}
