package dev.zig.notificationfilter.core.errors

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@Serializable
data class AppErrorReport(val id: String, val operation: String, val detail: String, val timestamp: Long)

/** Reports failures independently of Room, so a broken database cannot hide its own error. */
open class ErrorReporter(private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val _pending = MutableStateFlow<List<AppErrorReport>>(emptyList())
    val pending = _pending.asStateFlow()
    private var save: ((List<AppErrorReport>) -> Unit)? = null
    private var logger: ((String, Throwable) -> Unit)? = null
    private val recent = linkedMapOf<Pair<String, String>, Long>()

    fun attachStorage(read: () -> List<AppErrorReport>, write: (List<AppErrorReport>) -> Unit,
        log: (String, Throwable) -> Unit) = synchronized(lock) {
        logger = log
        val saved = try { read() } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            report("Reading saved error details", failure)
            emptyList()
        }
        _pending.value = (saved + _pending.value).distinctBy { it.id }.takeLast(MAX_PENDING)
        save = write
        persist()
    }

    fun report(operation: String, failure: Throwable, suppressRepeatForMillis: Long = 0) = synchronized(lock) {
        rethrowIfUnrecoverable(failure)
        log(operation, failure)
        val detail = errorDetail(failure)
        val key = operation to detail
        val time = now()
        if (_pending.value.any { it.operation == operation && it.detail == detail }) return@synchronized
        if (suppressRepeatForMillis > 0 && recent[key]?.let { time - it < suppressRepeatForMillis } == true) return@synchronized
        recent[key] = time
        while (recent.size > 64) recent.remove(recent.keys.first())
        _pending.value = (_pending.value + AppErrorReport(UUID.randomUUID().toString(), operation, detail, time))
            .takeLast(MAX_PENDING)
        persist()
    }

    fun dismiss(id: String) = synchronized(lock) {
        _pending.value = _pending.value.filterNot { it.id == id }
        persist()
    }

    fun <T> run(operation: String, block: () -> T): T? = try { block() } catch (failure: Throwable) {
        report(operation, failure)
        null
    }

    suspend fun <T> attempt(operation: String, block: suspend () -> T): T? = try { block() } catch (failure: Throwable) {
        report(operation, failure)
        null
    }

    fun handler(operation: String) = CoroutineExceptionHandler { _, failure -> report(operation, failure) }

    private fun persist() {
        try { save?.invoke(_pending.value) } catch (failure: Throwable) {
            rethrowIfUnrecoverable(failure)
            // Keep the original report in memory if storage itself is unavailable. Never
            // recursively report an error while trying to save/dismiss the error dialog.
            log("Saving error details", failure)
        }
    }

    private fun log(operation: String, failure: Throwable) {
        try { logger?.invoke(operation, failure) } catch (loggingFailure: Throwable) {
            rethrowIfUnrecoverable(loggingFailure)
        }
    }

    private companion object { const val MAX_PENDING = 12 }
}

object AppErrors : ErrorReporter()

/** Cancellation is normal lifecycle control; fatal VM failures cannot safely be resumed. */
internal fun rethrowIfUnrecoverable(failure: Throwable) {
    if (failure is CancellationException || failure is VirtualMachineError || failure is ThreadDeath) throw failure
}

internal fun errorDetail(failure: Throwable): String {
    val seen = mutableSetOf<Throwable>()
    val details = mutableListOf<String>()
    var current: Throwable? = failure
    while (current != null && seen.add(current) && details.size < 8) {
        val cause = current
        details += "${cause.javaClass.simpleName.ifBlank { cause.javaClass.name }}: " +
            (cause.message?.takeIf(String::isNotBlank) ?: "No additional error details were provided.")
        current = cause.cause
    }
    return details.joinToString("\nCaused by: ")
}

fun CoroutineScope.launchSafely(operation: String, context: CoroutineContext = EmptyCoroutineContext,
    reporter: ErrorReporter = AppErrors, block: suspend CoroutineScope.() -> Unit): Job = launch(context) {
    try { block() } catch (failure: Throwable) { reporter.report(operation, failure) }
}

/** Keep observations alive after transient storage failures and retain the last good data. */
fun <T> Flow<T>.recoverErrors(operation: String, reporter: ErrorReporter = AppErrors,
    retryDelayMs: Long = 1_000L): Flow<T> = retryWhen { failure, attempt ->
    reporter.report(operation, failure, suppressRepeatForMillis = 60_000L)
    delay((retryDelayMs * (1L shl attempt.coerceAtMost(5).toInt())).coerceAtMost(30_000L))
    true
}
