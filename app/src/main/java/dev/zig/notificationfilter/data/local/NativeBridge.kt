package dev.zig.notificationfilter.data.local

object NativeBridge {

    init {
        System.loadLibrary("rust_filter")
    }

    external fun isAppManaged(pkg: String): Boolean
    external fun isContactWhitelisted(contact: String): Boolean
    external fun addAppToManaged(pkg: String)
    external fun removeAppFromManaged(pkg: String)
    external fun addContactToWhitelist(contact: String)
    external fun clearContactWhitelist()
    external fun addKeywordRuleToWhitelist(joinedKeywords: String)
    external fun clearKeywordWhitelist()
    external fun containsWhitelistedKeyword(content: String): Boolean
    external fun addKeywordRuleToBlocklist(joinedKeywords: String)
    external fun clearKeywordBlocklist()
    external fun containsBlocklistedKeyword(content: String): Boolean
}
