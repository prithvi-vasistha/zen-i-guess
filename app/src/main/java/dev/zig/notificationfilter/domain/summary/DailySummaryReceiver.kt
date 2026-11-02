package dev.zig.notificationfilter.domain.summary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.zig.notificationfilter.core.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import dev.zig.notificationfilter.core.errors.launchSafely
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject
import dev.zig.notificationfilter.core.errors.AppErrors

@AndroidEntryPoint
class DailySummaryReceiver : BroadcastReceiver() {
    @Inject lateinit var delivery: DailySummaryDelivery
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailySummaryScheduler.ACTION_DELIVER) return
        val id = intent.getStringExtra(DailySummaryScheduler.EXTRA_OCCURRENCE) ?: return
        val pending = goAsync()
        appScope.launchSafely("Delivering daily summary alarm", Dispatchers.IO) {
            try { withTimeout(8_000L) { delivery.deliver(id) } }
            catch (e: TimeoutCancellationException) {
                AppErrors.report("Handling daily summary alarm", IllegalStateException("Daily summary delivery timed out", e))
            }
            catch (e: Exception) {
                AppErrors.report("Handling daily summary alarm", e)
            }
            finally { AppErrors.run("Finishing daily summary alarm") { pending.finish() } }
        }
    }
}
