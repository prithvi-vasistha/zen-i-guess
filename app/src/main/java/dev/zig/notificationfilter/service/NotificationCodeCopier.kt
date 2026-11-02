package dev.zig.notificationfilter.service

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import dev.zig.notificationfilter.domain.NotificationPublisher

/** Uses only the current Android notification; no database/model initialization is required. */
internal class NotificationCodeCopier(private val context: Context) {
    fun copy(intent: Intent): Boolean {
        if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            Toast.makeText(context, "Unlock your phone to copy the code", Toast.LENGTH_SHORT).show()
            return false
        }
        val id = intent.getIntExtra(NotificationActionReceiver.EXTRA_ZIG_NOTIF_ID, Int.MIN_VALUE)
        val tag = intent.getStringExtra(NotificationPublisher.EXTRA_ZIG_NOTIF_TAG)
        val code = intent.getStringExtra(EXTRA_CODE)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val current = context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == id && it.tag == tag }
        if (tag == null || code.isNullOrBlank() || token == null || current == null ||
            current.notification.extras.getString(EXTRA_TOKEN) != token ||
            current.notification.extras.getString(EXTRA_CODE) != code) {
            Toast.makeText(context, "This code is no longer available", Toast.LENGTH_SHORT).show()
            return false
        }
        val clip = ClipData.newPlainText("Verification code", code).apply {
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    companion object {
        const val EXTRA_CODE = "zig_copy_code"
        const val EXTRA_TOKEN = "zig_copy_token"
    }
}
