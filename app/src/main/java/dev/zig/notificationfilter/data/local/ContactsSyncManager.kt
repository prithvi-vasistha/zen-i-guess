package dev.zig.notificationfilter.data.local

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
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

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            appScope.launch(Dispatchers.IO) { doSync() }
        }
    }

    /** Called once from ZigApp.onCreate() — runs for the lifetime of the process. */
    fun register() {
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            observer,
        )
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

    private fun doSync() {
        if (!hasContactsPermission()) return
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
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PermissionChecker.PERMISSION_GRANTED
}
