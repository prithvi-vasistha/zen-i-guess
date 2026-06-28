package dev.zig.notificationfilter.domain.llm

interface LlmEngine {
    suspend fun evaluate(metadataBlock: String): Boolean
}
