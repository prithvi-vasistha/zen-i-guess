package dev.zig.notificationfilter

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.zig.notificationfilter.core.di.ApplicationScope
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

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Repopulate both Rust in-process sets from Room on cold start.
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
    }
}
