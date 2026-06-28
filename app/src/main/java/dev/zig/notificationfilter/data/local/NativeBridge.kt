package dev.zig.notificationfilter.data.local

object NativeBridge {

    init {
        System.loadLibrary("rust_filter")
    }

    external fun isAppWhitelisted(pkg: String): Boolean
    external fun isContactWhitelisted(contact: String): Boolean
    external fun addAppToWhitelist(pkg: String)
    external fun addContactToWhitelist(contact: String)
}
