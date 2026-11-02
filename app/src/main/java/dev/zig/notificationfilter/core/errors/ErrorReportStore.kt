package dev.zig.notificationfilter.core.errors

import android.content.Context
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object ErrorReportStore {
    fun initialize(context: Context, reporter: ErrorReporter = AppErrors) {
        val prefs = context.getSharedPreferences("zig_error_reports", Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(AppErrorReport.serializer())
        reporter.attachStorage(
            read = { json.decodeFromString(serializer, prefs.getString("pending", "[]") ?: "[]") },
            write = { reports -> check(prefs.edit().putString("pending", json.encodeToString(serializer, reports)).commit()) {
                "Could not save error details"
            } },
            log = { operation, failure -> Log.e("ZiG", operation, failure) },
        )
    }
}
