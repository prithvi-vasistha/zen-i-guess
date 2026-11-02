package dev.zig.notificationfilter

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.zig.notificationfilter.core.di.ApplicationScope
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.local.FilterState
import dev.zig.notificationfilter.data.local.db.DemoDataSeeder
import dev.zig.notificationfilter.data.local.db.KeywordRuleDao
import dev.zig.notificationfilter.data.local.db.ManagedAppDao
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.NotificationHealth
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import kotlinx.coroutines.CoroutineScope
import dev.zig.notificationfilter.core.errors.AppErrors
import dev.zig.notificationfilter.core.errors.ErrorReportStore
import dev.zig.notificationfilter.core.errors.launchSafely
import javax.inject.Inject

@HiltAndroidApp
class ZigApp : Application(), Configuration.Provider {

    @Inject lateinit var managedAppDao: ManagedAppDao
    @Inject lateinit var keywordRuleDao: KeywordRuleDao
    @Inject lateinit var contactsSyncManager: ContactsSyncManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dailySummaryScheduler: DailySummaryScheduler
    @Inject lateinit var demoDataSeeder: DemoDataSeeder
    @Inject lateinit var preferences: ZigUserPreferences
    @Inject lateinit var filterState: FilterState
    @Inject lateinit var notificationHealth: NotificationHealth

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
        AppErrors.run("Preparing error reporting") { ErrorReportStore.initialize(this) }
        notificationHealth.ensureListenerConnected()
        // Repopulate Rust in-process sets from Room on cold start.
        // The NotificationListenerService binds before MainActivity ever opens,
        // so these syncs must happen at Application level, not in any ViewModel.
        appScope.launchSafely("Loading saved notification settings") {
            // Seed default rules before the NativeBridge snapshot so they are active
            // in the Rust filter set from the very first launch.
            filterState.awaitReady()
            // Insert the two sample notifications on first launch so the onboarding tour
            // has real cards to demonstrate Allow / Block / Undo and the category chip.
            demoDataSeeder.seedIfNeeded()
        }

        // The ContactObserver must not be started before onboarding is complete.
        // The setup checklist is the gated path for READ_CONTACTS acquisition;
        // starting the observer earlier would crash on Samsung devices, which enforce
        // the permission at ContentObserver registration time.
        // Registration is permission-guarded and idempotent; FilterState loads
        // contacts before marking startup rules ready.
        if (preferences.onboardingCompleted && preferences.contactsBypassEnabled) {
            AppErrors.run("Starting contact observation") { contactsSyncManager.register() }
        }

        // Restore the chosen recap time and retire the old periodic WorkManager job.
        // Scheduling also turns the recap off if Android's timing access was revoked.
        AppErrors.run("Scheduling daily summary") { dailySummaryScheduler.schedule() }
    }
}
