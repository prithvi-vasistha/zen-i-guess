package dev.zig.notificationfilter.domain.classifier

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalModelEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : ZigClassifierEngine {

    private companion object {
        private const val MODEL_FILE = "zig_classifier.tflite"
        private const val VOCAB_FILE = "vocab.json"

        // Must match the max_sequence_length the model was trained with.
        private const val SEQUENCE_LENGTH = 48

        // vocab.json label_map: {"ALLOW": 0, "BLOCK": 1}
        // The sigmoid output is P(BLOCK). Scores at or above this threshold are BLOCK;
        // scores below it are ALLOW.
        private const val BLOCK_THRESHOLD = 0.5f

        // Padding index (implicit in Keras — index 0 is reserved and absent from vocab.json).
        private const val PAD_INDEX = 0

        // <OOV> index from vocab.json oov_token field.
        private const val OOV_INDEX = 1

        // Matches Keras Tokenizer's default `filters` string:
        // '!"#$%&()*+,-./:;<=>?@[\]^_`{|}~\t\n'
        // Replacing these characters with a space before splitting on whitespace
        // replicates the same token sequence the model saw during training.
        private val KERAS_FILTERS = Regex("""[!"#${'$'}%&()*+,\-./:;<=>?@\[\\\]^_`{|}~\t\n]""")
    }

    // word_index map loaded once from vocab.json and kept for the app lifetime.
    private val vocab: Map<String, Int> by lazy { loadVocab() }

    // Memory-mapped model buffer — zero-copy view into the APK asset.
    private val modelBuffer: MappedByteBuffer by lazy { loadModelBuffer() }

    // TFLite Interpreter is not thread-safe; the mutex serialises concurrent evaluate() calls.
    //
    // resizeInput: the model was exported from Keras with the training batch size (32)
    // baked into the input tensor shape [32, 48]. Resizing to [1, 48] before allocating
    // tensors lets us do single-sample inference without sending 31 empty padding rows.
    private val interpreter: Interpreter by lazy {
        Interpreter(modelBuffer, Interpreter.Options().apply { numThreads = 2 }).also { interp ->
            interp.resizeInput(0, intArrayOf(1, SEQUENCE_LENGTH))
            interp.allocateTensors()
        }
    }
    private val interpreterMutex = Mutex()

    override suspend fun evaluate(
        category: NotificationCategory,
        packageName: String,
        text: String,
    ): Boolean = withContext(Dispatchers.IO) {
        // Replicate the training format exactly: "[CATEGORY_XXX] | package.name | body text"
        // The tokenizer's preprocessing strips brackets, underscores, pipes, and dots so
        // the model sees: "category xxx package name body text" — same as during training.
        val formatted = "${category.token} | $packageName | $text"
        val inputBuffer = buildInputBuffer(tokenize(formatted))
        val output = Array(1) { FloatArray(1) }

        interpreterMutex.withLock {
            interpreter.run(inputBuffer, output)
        }

        // output[0][0] = P(BLOCK) — label_map {"ALLOW": 0, "BLOCK": 1}.
        // Scores BELOW BLOCK_THRESHOLD are classified as ALLOW.
        output[0][0] < BLOCK_THRESHOLD
    }

    // ── Tokenizer ─────────────────────────────────────────────────────────────

    // Matches Keras Tokenizer behaviour:
    //   1. Lowercase the input.
    //   2. Replace Keras default filter characters with spaces.
    //   3. Split on whitespace, discarding empty segments.
    //   4. Map each token to its vocab index (OOV_INDEX for unknowns).
    //   5. Pad with PAD_INDEX or truncate to SEQUENCE_LENGTH.
    private fun tokenize(text: String): IntArray {
        val words = text
            .lowercase()
            .replace(KERAS_FILTERS, " ")
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

        return IntArray(SEQUENCE_LENGTH) { i ->
            if (i < words.size) vocab[words[i]] ?: OOV_INDEX else PAD_INDEX
        }
    }

    // Packs integer token indices into a direct ByteBuffer (INT32, native byte order)
    // matching the model's input tensor layout [1, SEQUENCE_LENGTH].
    private fun buildInputBuffer(tokens: IntArray): ByteBuffer =
        ByteBuffer.allocateDirect(Int.SIZE_BYTES * SEQUENCE_LENGTH).apply {
            order(ByteOrder.nativeOrder())
            tokens.forEach { putInt(it) }
            rewind()
        }

    // ── Asset loaders ─────────────────────────────────────────────────────────

    // vocab.json structure: { "word_index": { "token": index, ... }, ... }
    private fun loadVocab(): Map<String, Int> {
        val raw = context.assets.open(VOCAB_FILE).bufferedReader().readText()
        val wordIndex = JSONObject(raw).getJSONObject("word_index")
        return buildMap {
            wordIndex.keys().forEach { key -> put(key, wordIndex.getInt(key)) }
        }
    }

    // AssetFileDescriptor + FileChannel.map() gives TFLite a zero-copy memory-mapped
    // view of the model bytes, which is required for the aaptOptions noCompress to
    // actually benefit inference (compressed assets cannot be memory-mapped).
    private fun loadModelBuffer(): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        return FileInputStream(fd.fileDescriptor).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
}
