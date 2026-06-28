package dev.zig.notificationfilter.ui.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.ManagedAppDao
import dev.zig.notificationfilter.data.local.db.ManagedAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ManagedAppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val managedAppDao: ManagedAppDao,
) : ViewModel() {

    data class InstalledApp(
        val packageName: String,
        val appName: String,
        val icon: ImageBitmap,
        val isManaged: Boolean,
    )

    data class UiState(
        val isLoading: Boolean = true,
        val apps: List<InstalledApp> = emptyList(),
    )

    private data class RawApp(
        val packageName: String,
        val appName: String,
        val icon: ImageBitmap,
    )

    private val rawApps = MutableStateFlow<List<RawApp>>(emptyList())

    private val managedPackagesFlow = managedAppDao.getAll()
        .map { entities -> entities.map { it.packageName }.toSet() }

    val uiState: StateFlow<UiState> = combine(rawApps, managedPackagesFlow) { raw, managed ->
        if (raw.isEmpty()) {
            UiState(isLoading = true)
        } else {
            UiState(
                isLoading = false,
                apps = raw.map { app ->
                    InstalledApp(
                        packageName = app.packageName,
                        appName = app.appName,
                        icon = app.icon,
                        isManaged = app.packageName in managed,
                    )
                },
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState(isLoading = true),
    )

    init {
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { info ->
                    pm.getLaunchIntentForPackage(info.packageName) != null &&
                        info.packageName != context.packageName
                }
                .map { info ->
                    RawApp(
                        packageName = info.packageName,
                        appName = pm.getApplicationLabel(info).toString(),
                        icon = loadIcon(pm, info),
                    )
                }
                .sortedBy { it.appName.lowercase() }
            rawApps.value = apps
        }
    }

    // Icons are scaled to 48×48 px (not dp) to bound memory use.
    // 48 px × 48 px × 4 bytes/pixel ≈ 9 KB per icon; ~200 apps ≈ 1.8 MB total.
    // Adaptive icons can be very large drawables; drawing them into a fixed bitmap
    // avoids OOM on low-end devices.
    private fun loadIcon(pm: PackageManager, info: ApplicationInfo): ImageBitmap {
        val drawable = pm.getApplicationIcon(info)
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, 48, 48)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }

    fun setManaged(packageName: String, isManaged: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isManaged) {
                managedAppDao.insert(ManagedAppEntity(packageName))
                NativeBridge.addAppToManaged(packageName)
            } else {
                managedAppDao.delete(ManagedAppEntity(packageName))
                NativeBridge.removeAppFromManaged(packageName)
            }
        }
    }
}
