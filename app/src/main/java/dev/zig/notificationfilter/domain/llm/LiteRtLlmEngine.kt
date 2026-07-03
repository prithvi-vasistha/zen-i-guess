package dev.zig.notificationfilter.domain.llm

// Phase 1: MediaPipe / LiteRT imports disabled. Restore in Phase 2 alongside TFLite wiring.
// import com.google.mediapipe.tasks.genai.llminference.LlmInference
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope

// Phase 1: coroutine/sync imports unused until Phase 2 inference is restored.
// import kotlinx.coroutines.Dispatchers
// import kotlinx.coroutines.Job
// import kotlinx.coroutines.delay
// import kotlinx.coroutines.launch
// import kotlinx.coroutines.sync.Mutex
// import kotlinx.coroutines.sync.withLock
// import kotlinx.coroutines.withContext
// import java.io.File
// import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiteRtLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : LlmEngine {

    // TODO: PHASE 2 - Insert TFLite Model Execution Here
    //
    // ── LiteRT / MediaPipe inference (disabled for Phase 1 migration) ─────────
    //
    // private companion object {
    //     private const val MODEL_ASSET_NAME = "qwen_250m_4bit.task"
    //     private const val METADATA_PLACEHOLDER = "{metadataBlock}"
    //     private const val TTL_MS = 300_000L
    //     private const val MAX_TOKENS = 1024
    //
    //     private val PROMPT_TEMPLATE = """
    //         You are a strict notification spam filter for a privacy-focused Android app.
    //         Your only job: decide if this notification genuinely needs the user's immediate human attention.
    //         Reply with exactly one word: TRUE or FALSE.
    //         ...
    //     """.trimIndent()
    // }
    //
    // private var llmInference: LlmInference? = null
    // private val mutex = Mutex()
    // private var keepAliveJob: Job? = null
    //
    // override suspend fun evaluate(metadataBlock: String): Boolean {
    //     val inference = mutex.withLock {
    //         if (llmInference == null) {
    //             val modelPath = ensureModelFile()
    //             val options = LlmInference.LlmInferenceOptions.builder()
    //                 .setModelPath(modelPath)
    //                 .setMaxTokens(MAX_TOKENS)
    //                 .build()
    //             llmInference = withContext(Dispatchers.IO) {
    //                 LlmInference.createFromOptions(context, options)
    //             }
    //         }
    //         llmInference!!
    //     }
    //
    //     keepAliveJob?.cancel()
    //
    //     val prompt = PROMPT_TEMPLATE.replace(METADATA_PLACEHOLDER, metadataBlock)
    //     val response = withContext(Dispatchers.IO) {
    //         inference.generateResponse(prompt)
    //     }
    //
    //     val result = response.trim().uppercase().startsWith("TRUE")
    //
    //     keepAliveJob = applicationScope.launch {
    //         delay(TTL_MS)
    //         mutex.withLock {
    //             llmInference?.close()
    //             llmInference = null
    //         }
    //         System.gc()
    //     }
    //
    //     return result
    // }
    //
    // private suspend fun ensureModelFile(): String = withContext(Dispatchers.IO) {
    //     val modelFile = File(context.filesDir, MODEL_ASSET_NAME)
    //     if (!modelFile.exists()) {
    //         val tmp = File(context.filesDir, "$MODEL_ASSET_NAME.tmp")
    //         try {
    //             context.assets.open(MODEL_ASSET_NAME).use { input ->
    //                 tmp.outputStream().use { output -> input.copyTo(output) }
    //             }
    //             if (!tmp.renameTo(modelFile)) {
    //                 throw IOException("Atomic rename of staged model file failed")
    //             }
    //         } catch (e: IOException) {
    //             tmp.delete()
    //             throw e
    //         }
    //     }
    //     modelFile.absolutePath
    // }
    // ──────────────────────────────────────────────────────────────────────────

    // Phase 1 passthrough: always allow. Replaced by TFLite inference in Phase 2.
    override suspend fun evaluate(metadataBlock: String): Boolean = true
}
