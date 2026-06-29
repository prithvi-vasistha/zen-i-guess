package dev.zig.notificationfilter

import android.Manifest
import android.app.Application
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import dagger.hilt.android.HiltAndroidApp
import dev.zig.notificationfilter.core.di.ApplicationScope
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.KeywordRuleDao
import dev.zig.notificationfilter.data.local.db.ManagedAppDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ZigApp : Application() {

    @Inject lateinit var managedAppDao: ManagedAppDao
    @Inject lateinit var keywordRuleDao: KeywordRuleDao
    @Inject lateinit var contactsSyncManager: ContactsSyncManager

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Repopulate Rust in-process sets from Room on cold start.
        // The NotificationListenerService binds before MainActivity ever opens,
        // so these syncs must happen at Application level, not in any ViewModel.
        appScope.launch {
            managedAppDao.getAllPackageNames().forEach { pkg ->
                NativeBridge.addAppToManaged(pkg)
            }
            keywordRuleDao.getAllSnapshot().forEach { rule ->
                NativeBridge.addKeywordRuleToWhitelist(rule.conditions.joinToString("||"))
            }
        }

        // Register the ContentObserver for the full process lifetime so any contacts
        // change (add, edit, delete) is reflected in the Rust HashSet automatically.
        contactsSyncManager.register()

        // Perform the initial sync now if READ_CONTACTS was already granted on a previous
        // launch. If not yet granted, MainActivity.onResume() calls requestSyncIfNeeded()
        // after the runtime permission prompt resolves.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                PermissionChecker.PERMISSION_GRANTED) {
            contactsSyncManager.syncContacts()
        }
    }
}
