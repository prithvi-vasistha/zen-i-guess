package dev.zig.notificationfilter.data.local.db

import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds a small set of high-confidence keyword rules on first launch.
 *
 * Each rule is an AND-chain: all terms must appear in a notification body for it to pass
 * through immediately, bypassing the ML classifier. Rules are picked to be tight enough
 * that legitimate spam can virtually never trigger them — a spammer sending a real OTP
 * or an authentic bank debit phrase would be indistinguishable from the genuine article.
 *
 * Seeding is guarded by [ZigUserPreferences.defaultRulesSeeded] and runs exactly once.
 * Users can remove any rule from the Rules Vault at any time.
 *
 * Call [seedIfNeeded] before [dev.zig.notificationfilter.data.local.NativeBridge]'s
 * keyword snapshot so the rules land in the Rust set on the very first launch.
 */
@Singleton
class DefaultRuleSeeder @Inject constructor(
    private val keywordRuleDao: KeywordRuleDao,
    private val preferences: ZigUserPreferences,
) {

    suspend fun seedIfNeeded() {
        if (preferences.defaultRulesSeeded) return

        DEFAULT_RULES.forEach { conditions ->
            keywordRuleDao.insert(KeywordRuleEntity(conditions = conditions))
        }

        preferences.defaultRulesSeeded = true
    }

    companion object {
        val DEFAULT_RULES: List<List<String>> = listOf(
            listOf("otp"),
            listOf("verification code"),
            listOf("has been credited"),
            listOf("has been debited"),
            listOf("has been delivered"),
        )
    }
}
