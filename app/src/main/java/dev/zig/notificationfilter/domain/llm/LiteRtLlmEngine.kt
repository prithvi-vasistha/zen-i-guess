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
            You are a strict notification spam filter for a privacy-focused Android app.
            Your only job: decide if this notification genuinely needs the user's immediate human attention.
            Reply with exactly one word: TRUE or FALSE.

            RULE: When the commercial or promotional intent is even slightly possible, always answer FALSE.

            TRUE only if the notification is clearly one of:
            - A personal message from a real human (SMS, WhatsApp, Telegram, iMessage, etc.)
            - A stranger or unknown person reaching out directly
            - OTP, verification code, or 2FA code
            - Bank transaction: debit, credit, UPI transfer, or card payment
            - Security alert: suspicious login, password changed, account breach
            - Delivery update: ride arriving, driver assigned, food arriving, package arriving today
            - Calendar event, meeting reminder, or alarm
            - Emergency alert or travel disruption (flight delay, train cancelled)

            FALSE for ALL of the following — no exceptions:
            - Any body text containing these words (case-insensitive): CASHBACK, CASH BACK,
              OFFER, DISCOUNT, SALE, DEAL, EARN, REWARD, REFER, REFERRAL, FREE, LUCKY,
              PRIZE, WIN, WINNER, LOAN, PRE-APPROVED, CREDIT LIMIT, UPGRADE NOW,
              LIMITED TIME, HURRY, EXPIRES, CLICK HERE, TAP TO CLAIM
            - Promotional or marketing messages from any brand, business, or service
            - Referral programs ("refer a friend", "invite and earn", etc.)
            - Bulk SMS that advertises a product, account, or financial product
            - App feature announcements, tips, or onboarding nudges
            - Social media activity: likes, comments, new followers, trending posts
            - News, articles, sports scores, or entertainment updates
            - App update available or changelog notifications
            - Any notification where a business is asking the user to take a commercial action

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
            Package Name: com.google.android.apps.messaging
            App Name: Messages
            Category: msg
            Title: AD-650022-P
            Body: Earn Rs.250 CASHBACK! Refer a friend to open a Safe Second Account with us today.
            Channel ID: promo_sms
            Timestamp: 1719600000000
            Answer:
            FALSE

            Notification:
            Package Name: net.one97.paytm
            App Name: Paytm
            Category: None
            Title: Exclusive for you
            Body: You are PRE-APPROVED for a Rs.50,000 personal loan! Tap to claim now.
            Channel ID: offers_channel
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
