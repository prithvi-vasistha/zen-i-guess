package dev.zig.notificationfilter.domain.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiteRtLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : LlmEngine {

    private companion object {
        private const val MODEL_ASSET_NAME = "qwen_250m_4bit.task"
        private const val METADATA_PLACEHOLDER = "{metadataBlock}"
        private const val TTL_MS = 300_000L

        // Total token budget for the inference session (prompt + generated output).
        // The few-shot prompt is ~500 tokens; the reply is 1 word. 1024 is safe headroom.
        private const val MAX_TOKENS = 1024

        // {metadataBlock} in a triple-quoted string is not Kotlin interpolation — it is
        // a literal placeholder replaced at call time via String.replace().
        private val PROMPT_TEMPLATE = """
            You are a binary notification classifier.
            Your job is to determine whether the notification requires immediate or near-term attention.
            Reply with exactly one word: TRUE or FALSE.

            TRUE if the notification is:
            - Some Human Stranger Reaching out
            - Notifications about a person now being reachable
            - OTP or verification code
            - Bank debit or credit
            - UPI transaction
            - Card payment
            - Security alert
            - Suspicious login
            - Password change
            - Ride arrival
            - Driver assigned
            - Food arriving
            - Package arriving today
            - Bill due
            - Calendar reminder
            - Meeting reminder
            - Travel update
            - Flight or train delay
            - Emergency alert

            FALSE if the notification is:
            - Advertisement
            - Promotion
            - Sale
            - Coupon
            - Cashback
            - Newsletter
            - News
            - Social media activity
            - Likes
            - Comments
            - Recommendations
            - Entertainment
            - App update
            - Feature announcement

            Examples:

            Notification:
            Package Name: com.google.android.apps.messaging
            App Name: Messages
            Category: msg
            Title: Bank Alert
            Body: Your OTP for login is 841623
            Channel ID: otp_channel
            Timestamp: 1719600000000
            Answer:
            TRUE

            Notification:
            Package Name: com.google.android.apps.nbu.paisa.user
            App Name: Google Pay
            Category: None
            Title: Transaction
            Body: Rs. 540 paid to Starbucks using UPI.
            Channel ID: payment_alerts
            Timestamp: 1719600000000
            Answer:
            TRUE

            Notification:
            Package Name: com.amazon.mShop.android.shopping
            App Name: Amazon
            Category: promo
            Title: Deals
            Body: Weekend Sale! Flat 70% off on shoes.
            Channel ID: marketing_channel
            Timestamp: 1719600000000
            Answer:
            FALSE

            Notification:
            {metadataBlock}
            Answer:""".trimIndent()
    }

    private var llmInference: LlmInference? = null
    private val mutex = Mutex()
    private var keepAliveJob: Job? = null

    override suspend fun evaluate(metadataBlock: String): Boolean {
        // Mutex guards initialization only. A local reference is captured so
        // inference can run outside the lock without blocking future callers.
        val inference = mutex.withLock {
            if (llmInference == null) {
                val modelPath = ensureModelFile()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                llmInference = withContext(Dispatchers.IO) {
                    LlmInference.createFromOptions(context, options)
                }
            }
            llmInference!!
        }

        // Reset TTL: every successful evaluation buys another 5 minutes.
        keepAliveJob?.cancel()

        val prompt = PROMPT_TEMPLATE.replace(METADATA_PLACEHOLDER, metadataBlock)
        val response = withContext(Dispatchers.IO) {
            inference.generateResponse(prompt)
        }

        // Aggressively trim whitespace and newlines before testing the first word.
        // startsWith is intentional: a model that outputs "TRUE." or "TRUE\n" still passes.
        val result = response.trim().uppercase().startsWith("TRUE")

        keepAliveJob = applicationScope.launch {
            delay(TTL_MS)
            mutex.withLock {
                llmInference?.close()
                llmInference = null
            }
            System.gc()
        }

        return result
    }

    // Copies the model from APK assets to internal storage on first launch.
    // Uses a .tmp staging file so a crash mid-copy leaves no corrupt .task file.
    // Subsequent calls return immediately if the file already exists.
    private suspend fun ensureModelFile(): String = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, MODEL_ASSET_NAME)
        if (!modelFile.exists()) {
            val tmp = File(context.filesDir, "$MODEL_ASSET_NAME.tmp")
            try {
                context.assets.open(MODEL_ASSET_NAME).use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (!tmp.renameTo(modelFile)) {
                    throw IOException("Atomic rename of staged model file failed")
                }
            } catch (e: IOException) {
                tmp.delete()
                throw e
            }
        }
        modelFile.absolutePath
    }
}
