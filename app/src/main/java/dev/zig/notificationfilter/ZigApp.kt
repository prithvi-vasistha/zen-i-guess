package dev.zig.notificationfilter

import android.Manifest
import android.app.Application
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.zig.notificationfilter.core.di.ApplicationScope
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.DefaultRuleSeeder
import dev.zig.notificationfilter.data.local.db.DemoDataSeeder
import dev.zig.notificationfilter.data.local.db.KeywordRuleDao
import dev.zig.notificationfilter.data.local.db.ManagedAppDao
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ZigApp : Application(), Configuration.Provider {

    @Inject lateinit var managedAppDao: ManagedAppDao
    @Inject lateinit var keywordRuleDao: KeywordRuleDao
    @Inject lateinit var contactsSyncManager: ContactsSyncManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dailySummaryScheduler: DailySummaryScheduler
    @Inject lateinit var defaultRuleSeeder: DefaultRuleSeeder
    @Inject lateinit var demoDataSeeder: DemoDataSeeder
    @Inject lateinit var preferences: ZigUserPreferences

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    // Provides Hilt-aware WorkManager configuration so @HiltWorker can receive
    // injected dependencies. WorkManager's default auto-init is disabled in the manifest
    // (tools:node="remove" on the WorkManagerInitializer provider) so we own init here.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Repopulate Rust in-process sets from Room on cold start.
        // The NotificationListenerService binds before MainActivity ever opens,
        // so these syncs must happen at Application level, not in any ViewModel.
        appScope.launch {
            // Seed default rules before the NativeBridge snapshot so they are active
            // in the Rust filter set from the very first launch.
            defaultRuleSeeder.seedIfNeeded()

            managedAppDao.getAllPackageNames().forEach { pkg ->
                NativeBridge.addAppToManaged(pkg)
            }
            keywordRuleDao.getAllSnapshot().forEach { rule ->
                NativeBridge.addKeywordRuleToWhitelist(rule.conditions.joinToString("||"))
            }
            // Insert the two sample notifications on first launch so the onboarding tour
            // has real cards to demonstrate Allow / Block / Undo and the category chip.
            demoDataSeeder.seedIfNeeded()
        }

        // The ContactObserver must not be started before onboarding is complete.
        // The setup checklist is the gated path for READ_CONTACTS acquisition;
        // starting the observer earlier would crash on Samsung devices, which enforce
        // the permission at ContentObserver registration time.
        // On subsequent cold starts (onboarding already done) both calls are safe:
        // register() is permission-guarded and idempotent, syncContacts() is
        // guarded by its own permission check below.
        if (preferences.onboardingCompleted && preferences.contactsBypassEnabled) {
            contactsSyncManager.register()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                    PermissionChecker.PERMISSION_GRANTED) {
                contactsSyncManager.syncContacts()
            }
        }

        // Enqueue the daily summary worker. KEEP policy means this is a no-op on every
        // subsequent launch once already scheduled. Users who opt out call cancel() from
        // the ViewModel, which removes the unique work entry; re-enabling calls schedule().
        dailySummaryScheduler.schedule()
    }
}
