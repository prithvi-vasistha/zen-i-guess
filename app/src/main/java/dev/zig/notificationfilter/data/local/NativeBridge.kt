package dev.zig.notificationfilter.data.local

object NativeBridge {

    init {
        System.loadLibrary("rust_filter")
    }

    external fun isAppManaged(pkg: String): Boolean
    external fun isContactWhitelisted(contact: String): Boolean
    external fun addAppToManaged(pkg: String)
    external fun addContactToWhitelist(contact: String)
}
