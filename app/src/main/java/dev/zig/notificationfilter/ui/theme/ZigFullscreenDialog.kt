package dev.zig.notificationfilter.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import android.os.Build

/** Dialog windows need their own system bar icons when ZiG overrides the phone's mode. */
@Composable
@Suppress("DEPRECATION") // Transparent bars are still needed on Android 14 and earlier.
fun ZigFullscreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val dark = LocalZigDarkTheme.current
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                // No exposed underlay needs dimming in a full-screen menu. Keeping it
                // undimmed also preserves the activity's themed system bars.
                it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                it.setDimAmount(0f)
                it.statusBarColor = android.graphics.Color.TRANSPARENT
                it.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        content()
    }
}
