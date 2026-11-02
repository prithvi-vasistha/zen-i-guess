package dev.zig.notificationfilter.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.zig.notificationfilter.core.errors.AppErrors
import kotlinx.coroutines.CoroutineScope

/** Keep screen tasks inside an error boundary while preserving effect cancellation. */
@Composable
fun SafeLaunchedEffect(vararg keys: Any?, operation: String = "Updating screen",
    block: suspend CoroutineScope.() -> Unit) {
    LaunchedEffect(*keys) {
        AppErrors.attempt(operation) { block() }
    }
}
