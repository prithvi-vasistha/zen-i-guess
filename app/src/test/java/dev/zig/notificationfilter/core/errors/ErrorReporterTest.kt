package dev.zig.notificationfilter.core.errors

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.sql.SQLException

class ErrorReporterTest {
    @Test fun sqlFailureReturnsSafelyWithTheActualMessageAndOperation() {
        val reporter = ErrorReporter()
        assertNull(reporter.run("Saving rule") { throw SQLException("near '?': syntax error") })
        assertEquals("Saving rule", reporter.pending.value.single().operation)
        assertEquals("SQLException: near '?': syntax error", reporter.pending.value.single().detail)
    }

    @Test fun originalCauseIsIncludedWhenAnOperationWrapsTheError() {
        val reporter = ErrorReporter()
        reporter.report("Importing backup", IOException("Import stopped", SQLException("database is full")))
        assertTrue(reporter.pending.value.single().detail.contains("IOException: Import stopped"))
        assertTrue(reporter.pending.value.single().detail.contains("SQLException: database is full"))
    }

    @Test fun guardedFailedChildDoesNotCancelOtherWork() = runBlocking {
        val reporter = ErrorReporter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val failing = scope.launchSafely("Saving review", reporter = reporter) { throw SQLException("read-only database") }
            failing.join()
            assertFalse(failing.isCancelled)
            val next = CompletableDeferred<Boolean>()
            scope.launchSafely("Next task", reporter = reporter) { next.complete(true) }.join()
            assertTrue(next.await())
            assertEquals(1, reporter.pending.value.size)
        } finally { scope.cancel() }
    }

    @Test fun applicationScopeBackstopReportsAnUnexpectedChildFailureAndStaysAlive() = runBlocking {
        val reporter = ErrorReporter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + reporter.handler("Background task"))
        try {
            scope.launch { throw SQLException("disk I/O error") }.join()
            assertTrue(scope.isActive)
            assertTrue(reporter.pending.value.single().detail.contains("disk I/O error"))
            val next = CompletableDeferred<Unit>()
            scope.launch { next.complete(Unit) }.join()
            next.await()
        } finally { scope.cancel() }
    }

    @Test fun lifecycleCancellationIsPropagatedWithoutAnErrorDialog() = runBlocking {
        val reporter = ErrorReporter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launchSafely("Closing screen", reporter = reporter) { throw CancellationException("Screen closed") }
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(reporter.pending.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun failedDatabaseObservationRetriesAndEmitsRecoveredData() = runBlocking {
        val reporter = ErrorReporter()
        var attempts = 0
        val recovered = flow {
            if (++attempts == 1) throw SQLException("database locked")
            emit("Saved rule")
        }.recoverErrors("Reading rules", reporter, retryDelayMs = 0).first()
        assertEquals("Saved rule", recovered)
        assertEquals(2, attempts)
        assertTrue(reporter.pending.value.single().detail.contains("database locked"))
    }

    @Test fun observationCancellationIsNotRetriedOrReported() = runBlocking {
        val reporter = ErrorReporter()
        try {
            flow<String> { throw CancellationException("Stopped observing") }
                .recoverErrors("Reading rules", reporter, 0).first()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(reporter.pending.value.isEmpty())
    }

    @Test fun pendingBackgroundReportSurvivesReporterRestartAndDismissalIsSaved() {
        var saved = emptyList<AppErrorReport>()
        val first = ErrorReporter()
        first.attachStorage({ saved }, { saved = it.toList() }, { _, _ -> })
        first.report("Daily summary", IOException("Could not open summary data"))
        val restarted = ErrorReporter()
        restarted.attachStorage({ saved }, { saved = it.toList() }, { _, _ -> })
        assertEquals(first.pending.value, restarted.pending.value)
        restarted.dismiss(restarted.pending.value.single().id)
        assertTrue(saved.isEmpty())
    }

    @Test fun unavailableErrorStorageStillLeavesTheOriginalReportInMemory() {
        val reporter = ErrorReporter()
        reporter.attachStorage({ emptyList() }, { throw IOException("Storage unavailable") }, { _, _ -> })
        reporter.report("Saving review", SQLException("Database unavailable"))
        assertTrue(reporter.pending.value.single().detail.contains("Database unavailable"))
        reporter.dismiss(reporter.pending.value.single().id)
        assertTrue(reporter.pending.value.isEmpty())
    }

    @Test fun unreadableSavedReportsAreReportedAndNewReportsStillWork() {
        val reporter = ErrorReporter()
        var saved = emptyList<AppErrorReport>()
        reporter.attachStorage({ throw IOException("Corrupt saved error data") }, { saved = it }, { _, _ -> })
        assertTrue(saved.single().detail.contains("Corrupt saved error data"))
        reporter.report("Next task", SQLException("New database failure"))
        assertEquals(2, saved.size)
        assertTrue(saved.last().detail.contains("New database failure"))
    }

    @Test fun failedDiagnosticLoggerDoesNotCrashTheOriginalOperation() {
        val reporter = ErrorReporter()
        reporter.attachStorage({ emptyList() }, { }, { _, _ -> throw IOException("Logging unavailable") })
        assertNull(reporter.run("Saving rule") { throw SQLException("Database failure") })
        assertTrue(reporter.pending.value.single().detail.contains("Database failure"))
    }

    @Test fun duplicatePendingFailuresAreCombinedButANewUserAttemptCanShowAgain() {
        val reporter = ErrorReporter()
        repeat(100) { reporter.report("Saving rule", SQLException("Database unavailable")) }
        assertEquals(1, reporter.pending.value.size)
        reporter.dismiss(reporter.pending.value.single().id)
        reporter.report("Saving rule", SQLException("Database unavailable"))
        assertEquals(1, reporter.pending.value.size)
    }

    @Test fun repeatedBackgroundFailuresDoNotImmediatelyReopenADismissedDialog() {
        var time = 100L
        val reporter = ErrorReporter { time }
        reporter.report("Observing rules", SQLException("Database unavailable"), 60_000)
        reporter.dismiss(reporter.pending.value.single().id)
        reporter.report("Observing rules", SQLException("Database unavailable"), 60_000)
        assertTrue(reporter.pending.value.isEmpty())
        time += 60_000
        reporter.report("Observing rules", SQLException("Database unavailable"), 60_000)
        assertEquals(1, reporter.pending.value.size)
    }

    @Test fun missingNativeLibraryIsReportedAsARecoverableOperationFailure() {
        val reporter = ErrorReporter()
        assertNull(reporter.run("Loading model") { throw UnsatisfiedLinkError("Native model library unavailable") })
        assertTrue(reporter.pending.value.single().detail.contains("UnsatisfiedLinkError: Native model library unavailable"))
    }

    @Test fun fatalVmFailureIsNotPretendedToBeRecoverable() {
        val reporter = ErrorReporter()
        assertThrows(OutOfMemoryError::class.java) {
            reporter.run("Task") { throw OutOfMemoryError("Fatal VM failure") }
        }
        assertTrue(reporter.pending.value.isEmpty())
    }
}
