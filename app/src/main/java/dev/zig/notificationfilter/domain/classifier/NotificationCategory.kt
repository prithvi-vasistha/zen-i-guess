package dev.zig.notificationfilter.domain.classifier

// Tokens must match exactly what was used in the training data format:
//   "[CATEGORY_XXX] | package.name | message body"
// The Keras Tokenizer strips brackets and underscores, so [CATEGORY_FINANCE]
// becomes the two tokens "category" + "finance" — identical to training.
enum class NotificationCategory(val token: String) {
    FINANCE("[CATEGORY_FINANCE]"),
    FRAUD("[CATEGORY_FRAUD]"),
    SOCIAL("[CATEGORY_SOCIAL]"),
    SHOPPING("[CATEGORY_SHOPPING]"),
    FOOD("[CATEGORY_FOOD]"),
    TRANSPORT("[CATEGORY_TRANSPORT]"),
    UNKNOWN("[CATEGORY_UNKNOWN]");

    companion object {

        // ── Package → category ────────────────────────────────────────────────

        private val FINANCE_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user",   // Google Pay
            "net.one97.paytm",                           // Paytm
            "com.phonepe.app",                           // PhonePe
            "com.amazon.mShop.android.shopping",         // Amazon Pay
            "com.mobikwik_new",
            "com.freecharge.android",
            "com.dreamplug.androidapp",                  // CRED
            "com.sbi.lotusintouch",                      // SBI YONO
            "com.axis.mobile",
            "com.csam.icici.bank.imobile",
            "com.hdfc.mobilebanking",
            "com.kotak.mobilebanking",
            "com.rbl.bank.myrbl",
            "com.indusind.mobile",
            "com.yesbank",
            "com.bankofbaroda.mconnect",
            "com.upi.hsbc",
        )

        private val SOCIAL_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.plus",
            "com.google.android.apps.messaging",         // SMS / RCS
            "com.instagram.android",
            "com.snapchat.android",
            "com.discord",
            "com.linkedin.android",
            "com.twitter.android",
            "com.zhiliaoapp.musically",                  // TikTok
            "com.microsoft.teams",
            "com.slack",
            "com.facebook.orca",                         // Messenger
            "com.facebook.katana",
        )

        private val SHOPPING_PACKAGES = setOf(
            "com.flipkart.android",
            "com.myntra.android",
            "com.nykaa.android",
            "com.meesho.supply",
            "com.shopclues",
            "com.ajio.app",
            "com.purplle.app",
        )

        private val FOOD_PACKAGES = setOf(
            "in.swiggy.android",
            "com.application.zomato",
            "com.blinkit.consumer",
            "com.bigbasket.mobileapp",
            "com.grofers.customerapp",
            "com.zepto.app",
            "com.dunzo.user",
            "com.magicpin.magicpin",
        )

        private val TRANSPORT_PACKAGES = setOf(
            "com.olacabs.customer",
            "com.ubercab",
            "com.rapido.passenger",
            "com.yatri.driver",
            "com.irctc.ticketbooking",
            "in.cleartrip.android",
            "com.makemytrip",
            "com.goibibo",
            "com.easemytrip.android",
            "air.com.indigo.mobile",
            "com.airindia.mobile",
        )

        // ── Text-based signals ────────────────────────────────────────────────
        // Checked before package look-up because fraudulent messages frequently
        // arrive from trusted package names (banking SMS via Messages app).

        private val FRAUD_KEYWORDS = setOf(
            "suspicious", "unauthorized", "fraud", "blocked your account",
            "kyc expired", "kyc update", "account suspended", "account blocked",
            "urgent action", "verify your account", "deactivated",
            "click here to restore", "your account will be",
        )

        // ── Resolver ──────────────────────────────────────────────────────────
        // Priority: fraud text signal → package mapping → UNKNOWN fallback.
        fun resolve(packageName: String, text: String): NotificationCategory {
            val lower = text.lowercase()
            if (FRAUD_KEYWORDS.any { lower.contains(it) }) return FRAUD
            if (packageName in FINANCE_PACKAGES) return FINANCE
            if (packageName in SOCIAL_PACKAGES) return SOCIAL
            if (packageName in SHOPPING_PACKAGES) return SHOPPING
            if (packageName in FOOD_PACKAGES) return FOOD
            if (packageName in TRANSPORT_PACKAGES) return TRANSPORT
            return UNKNOWN
        }
    }
}
