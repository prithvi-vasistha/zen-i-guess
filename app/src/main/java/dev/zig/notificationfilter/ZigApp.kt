package dev.zig.notificationfilter

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.zig.notificationfilter.core.di.ApplicationScope
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.ManagedAppDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ZigApp : Application() {

    @Inject
    lateinit var managedAppDao: ManagedAppDao

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Repopulate the Rust MANAGED_APPS in-process set from Room on cold start.
        // The NotificationListenerService can receive notifications before MainActivity
        // is ever opened, so the ViewModel alone cannot be relied on for this sync.
        appScope.launch {
            managedAppDao.getAllPackageNames().forEach { pkg ->
                NativeBridge.addAppToManaged(pkg)
            }
        }
    }
}
