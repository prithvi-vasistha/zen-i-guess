package dev.zig.notificationfilter.data.local

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    // Guards against duplicate initial syncs across ZigApp.onCreate() and MainActivity.onResume().
    // Volatile because it is written on the main thread and read on Dispatchers.IO.
    @Volatile private var initialSyncDone = false

    // Prevents double-registration if register() is called multiple times (e.g. onResume).
    @Volatile private var isRegistered = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            appScope.launch(Dispatchers.IO) { doSync() }
        }
    }

    /**
     * Registers the ContentObserver for the process lifetime.
     * Safe to call from any entry point — guards against both missing permission
     * (Samsung enforces READ_CONTACTS at registerContentObserver time) and
     * accidental double-registration on subsequent onResume() calls.
     */
    fun register() {
        if (!hasContactsPermission() || isRegistered) return
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            observer,
        )
        isRegistered = true
    }

    /**
     * Triggers a full contact sync on Dispatchers.IO.
     * Sets [initialSyncDone] immediately so concurrent calls from [requestSyncIfNeeded]
     * do not launch a duplicate sync.
     */
    fun syncContacts() {
        initialSyncDone = true
        appScope.launch(Dispatchers.IO) { doSync() }
    }

    /**
     * Called from MainActivity.onResume() to fire the very first sync after the user
     * grants READ_CONTACTS via the runtime permission prompt. No-ops on every subsequent
     * resume because [initialSyncDone] is set on the first invocation.
     */
    fun requestSyncIfNeeded() {
        if (!initialSyncDone && hasContactsPermission()) {
            syncContacts()
        }
    }

    /**
     * Stops observing contacts and clears the Rust in-process contact whitelist.
     * Called when the user disables the Smart Contact Bypass from Settings.
     * Does NOT revoke READ_CONTACTS — the OS permission is left intact.
     * Safe to call if never registered.
     */
    fun unregister() {
        if (!isRegistered) return
        context.contentResolver.unregisterContentObserver(observer)
        isRegistered = false
        initialSyncDone = false
        NativeBridge.clearContactWhitelist()
    }

    private fun doSync() {
        if (!hasContactsPermission()) return
        try {
            NativeBridge.clearContactWhitelist()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null,
                null,
                null,
            )?.use { cursor ->
                val col = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                if (col == -1) return
                while (cursor.moveToNext()) {
                    val name = cursor.getString(col)?.trim()?.lowercase()
                    if (!name.isNullOrBlank()) {
                        NativeBridge.addContactToWhitelist(name)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("ZIG_CRASH", "Native JNI crash during contact sync: ", e)
        }
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PermissionChecker.PERMISSION_GRANTED
}
