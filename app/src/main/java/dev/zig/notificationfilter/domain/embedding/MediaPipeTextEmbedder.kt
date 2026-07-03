package dev.zig.notificationfilter.domain.embedding

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder as MpTextEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TextEmbedder] backed by MediaPipe's Text Embedder task running the Universal Sentence
 * Encoder model in assets/text_embedder.tflite.
 *
 * MediaPipe bundles the SentencePiece custom op the model's in-graph tokenizer needs, which
 * the bare LiteRT runtime cannot resolve. `setL2Normalize(true)` guarantees unit-length
 * vectors so downstream cosine similarity is a plain dot product.
 *
 * The underlying task is created lazily on first use (mirroring LocalModelEngine) and reused
 * for the app lifetime. MediaPipe's TextEmbedder is not documented as thread-safe, so a mutex
 * serialises concurrent embed() calls — matching the pipeline's single-notification cadence.
 */
@Singleton
class MediaPipeTextEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextEmbedder {

    private companion object {
        private const val TAG = "MediaPipeTextEmbedder"
        private const val MODEL_ASSET = "text_embedder.tflite"
    }

    private val mutex = Mutex()

    // Lazily initialised. Null means initialisation failed once; we do not retry every call
    // to avoid log spam and repeated native load attempts — embed() fails open to null.
    @Volatile private var initFailed = false

    private val embedder: MpTextEmbedder? by lazy {
        try {
            val options = MpTextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .build()
                )
                .setL2Normalize(true)
                .build()
            MpTextEmbedder.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise text embedder — Personal Memory disabled", e)
            initFailed = true
            null
        }
    }

    override suspend fun embed(text: String): FloatArray? {
        if (text.isBlank() || initFailed) return null
        return withContext(Dispatchers.IO) {
            val engine = embedder ?: return@withContext null
            try {
                mutex.withLock {
                    val result = engine.embed(text)
                    result.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Embedding failed for input of length ${text.length}", e)
                null
            }
        }
    }
}
