package dev.zig.notificationfilter.data.local

object NativeBridge {

    init {
        System.loadLibrary("rust_filter")
    }

    // The same monitor protects lookups and complete snapshot replacements. A reader
    // can observe the old or new rules, never the cleared intermediate JNI sets.
    @Synchronized external fun isAppManaged(pkg: String): Boolean
    @Synchronized external fun isContactWhitelisted(contact: String): Boolean
    @Synchronized external fun addAppToManaged(pkg: String)
    @Synchronized external fun removeAppFromManaged(pkg: String)
    @Synchronized external fun addContactToWhitelist(contact: String)
    @Synchronized external fun clearContactWhitelist()
    @Synchronized external fun addKeywordRuleToWhitelist(joinedKeywords: String)
    @Synchronized external fun clearKeywordWhitelist()
    @Synchronized external fun containsWhitelistedKeyword(content: String): Boolean
    @Synchronized external fun addKeywordRuleToBlocklist(joinedKeywords: String)
    @Synchronized external fun clearKeywordBlocklist()
    @Synchronized external fun containsBlocklistedKeyword(content: String): Boolean

    private var managedSnapshot: Set<String> = emptySet()

    @Synchronized
    fun replaceRules(managed: Set<String>, allow: List<String>, block: List<String>) {
        (managedSnapshot - managed).forEach(::removeAppFromManaged)
        managed.forEach(::addAppToManaged)
        managedSnapshot = managed
        clearKeywordWhitelist()
        clearKeywordBlocklist()
        allow.forEach(::addKeywordRuleToWhitelist)
        block.forEach(::addKeywordRuleToBlocklist)
    }

    @Synchronized
    fun replaceContacts(names: List<String>) {
        clearContactWhitelist()
        names.forEach(::addContactToWhitelist)
    }

    enum class KeywordMatch { BLOCK, ALLOW, NONE }

    @Synchronized
    fun matchKeywords(content: String): KeywordMatch = when {
        containsBlocklistedKeyword(content) -> KeywordMatch.BLOCK
        containsWhitelistedKeyword(content) -> KeywordMatch.ALLOW
        else -> KeywordMatch.NONE
    }
}
