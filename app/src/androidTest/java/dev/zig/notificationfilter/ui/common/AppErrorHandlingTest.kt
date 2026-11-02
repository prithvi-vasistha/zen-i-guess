package dev.zig.notificationfilter.ui.common

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zig.notificationfilter.ZigApp
import dev.zig.notificationfilter.core.errors.*
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.local.FilterState
import dev.zig.notificationfilter.data.local.db.*
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.ui.rules.CustomRulesViewModel
import dev.zig.notificationfilter.ui.settings.SettingsViewModel
import dev.zig.notificationfilter.domain.backup.BackupRestoreManager
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import dev.zig.notificationfilter.domain.memory.PersonalMemoryRepository
import dev.zig.notificationfilter.ui.theme.ZigTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppErrorHandlingTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<ZigApp>()
    private val prefix = "error-test-${UUID.randomUUID()}-"
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            app.getSharedPreferences(prefix + name, mode)
    }
    private val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
    private val dao = database.keywordRuleDao()
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = ZigUserPreferences(context)
    private val originalReports = AppErrors.pending.value.map { it.id }.toSet()

    @Before fun prepare() {
        prefs.defaultRulesSeeded = true
        prefs.contactsBypassEnabled = false
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
    }

    @After fun cleanup(): Unit = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        scope.cancel()
        database.close()
        app.filterState.refresh()
        AppErrors.pending.value.filterNot { it.id in originalReports }.forEach { AppErrors.dismiss(it.id) }
        app.deleteSharedPreferences(prefix + "zig_user_prefs")
        app.deleteSharedPreferences(prefix + "zig_error_reports")
    }

    @Test fun actualSqlFailuresInRuleEditsShowDetailsAndAllowEveryActionToRetry(): Unit = runBlocking {
        val vm = rulesViewModel(dao)
        compose.setContent {
            ZigTheme {
                Column {
                    Text("Rules screen is running")
                    Button(onClick = { vm.addRule("OTP", KeywordRuleType.ALLOW) }) { Text("Save test rule") }
                }
                AppErrorDialog()
            }
        }
        // Use SQLite itself to reject each write, including Room's suspend DAO path.
        rejectWrites("INSERT")
        compose.onNodeWithText("Save test rule").performClick()
        assertError("Adding keyword rule", "Forced INSERT SQL failure")
        assertTrue(dao.getAllSnapshot().isEmpty())
        removeTrigger()
        compose.onNodeWithText("Save test rule").performClick()
        await { dao.getAllSnapshot().size == 1 }
        val saved = dao.getAllSnapshot().single()

        rejectWrites("UPDATE")
        compose.runOnIdle { vm.updateRule(saved, "OTP, bank") }
        assertError("Updating keyword rule", "Forced UPDATE SQL failure")
        assertEquals(listOf("OTP"), dao.getAllSnapshot().single().conditions)
        removeTrigger()
        compose.runOnIdle { vm.updateRule(saved, "OTP, bank") }
        await { dao.getAllSnapshot().single().conditions.size == 2 }

        rejectWrites("DELETE")
        compose.runOnIdle { vm.deleteRule(saved) }
        assertError("Deleting keyword rule", "Forced DELETE SQL failure")
        assertEquals(1, dao.getAllSnapshot().size)
        removeTrigger()
        compose.runOnIdle { vm.deleteRule(saved) }
        await { dao.getAllSnapshot().isEmpty() }
        compose.onNodeWithText("Rules screen is running").assertIsDisplayed()
    }

    @Test fun failedDatabaseObservationAutomaticallyRecoversOnTheSameScreen() = runBlocking {
        dao.insert(KeywordRuleEntity(conditions = listOf("Recovery rule"), ruleType = KeywordRuleType.ALLOW.name))
        var attempts = 0
        val failingDao = object : KeywordRuleDao by dao {
            override fun getByType(type: String): Flow<List<KeywordRuleEntity>> = flow {
                if (++attempts == 1) throw SQLiteException("Forced database read failure")
                emitAll(dao.getByType(type))
            }
        }
        val vm = rulesViewModel(failingDao)
        compose.setContent {
            ZigTheme {
                val rules by vm.allowRules.collectAsState()
                Text(rules.joinToString { it.conditions.joinToString() })
                AppErrorDialog()
            }
        }
        assertError("Loading allow rules", "Forced database read failure")
        compose.waitUntil(10_000) { vm.allowRules.value.size == 1 }
        compose.onNodeWithText("Recovery rule").assertIsDisplayed()
        assertTrue(attempts >= 2)
    }

    @Test fun backgroundFailureSurvivesRestartAndItsDialogCanBeDismissedDurably() = runBlocking {
        val reporter = ErrorReporter()
        ErrorReportStore.initialize(context, reporter)
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO + reporter.handler("Background summary"))
        try {
            background.launch { throw SQLiteException("Forced background SQL failure") }.join()
            assertTrue(background.isActive)
            val continued = CompletableDeferred<Boolean>()
            background.launch { continued.complete(true) }.join()
            assertTrue(continued.await())
            val restarted = ErrorReporter()
            ErrorReportStore.initialize(context, restarted)
            assertEquals(reporter.pending.value, restarted.pending.value)
            compose.setContent { ZigTheme { Text("App reopened"); AppErrorDialog(restarted) } }
            compose.onNodeWithText("Something went wrong").assertIsDisplayed()
            compose.onNodeWithText("Background summary").assertIsDisplayed()
            compose.onNodeWithText("SQLiteException: Forced background SQL failure").assertIsDisplayed()
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithText("App reopened").assertIsDisplayed()
            val afterDismissal = ErrorReporter()
            ErrorReportStore.initialize(context, afterDismissal)
            assertTrue(afterDismissal.pending.value.isEmpty())
        } finally { background.cancel() }
    }

    @Test fun realBackupFileErrorsClearTheBusyStateAndLeaveSettingsUsable() {
        val contacts = ContactsSyncManager(context, scope)
        val filter = FilterState(database, DefaultRuleSeeder(dao, prefs), contacts, prefs)
        val scheduler = DailySummaryScheduler(context, prefs)
        val unusedEmbedder = object : TextEmbedder {
            override suspend fun embed(text: String): FloatArray? = error("File access must fail before embedding")
        }
        val memory = PersonalMemoryRepository(database.notificationReviewDao(), unusedEmbedder)
        val backups = BackupRestoreManager(database.notificationReviewDao(), database.managedAppDao(), filter,
            dao, database.appCategoryOverrideDao(), prefs, scheduler, unusedEmbedder, memory)
        lateinit var vm: SettingsViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(context, prefs, scheduler, backups, contacts, memory, app.notificationHealth) as T
            })[SettingsViewModel::class.java]
        }
        compose.setContent { ZigTheme { Text("Settings are running"); AppErrorDialog() } }
        val missingInput = File(context.cacheDir, prefix + "missing-backup.json")
        compose.runOnIdle { vm.importFrom(Uri.fromFile(missingInput)) }
        assertError("Importing backup", "FileNotFoundException")
        compose.waitUntil(10_000) { !vm.isBusy.value }
        // A second action uses the same ViewModel scope after the import failure.
        val unwritableOutput = File(context.cacheDir, prefix + "missing-directory/backup.json")
        compose.runOnIdle { vm.exportTo(Uri.fromFile(unwritableOutput)) }
        assertError("Exporting backup", "FileNotFoundException")
        compose.waitUntil(10_000) { !vm.isBusy.value }
        compose.onNodeWithText("Settings are running").assertIsDisplayed()
    }

    @Test fun corruptPreferenceTypeUsesADefaultAndReportsTheActualError() {
        context.getSharedPreferences("zig_user_prefs", Context.MODE_PRIVATE)
            .edit().putString("daily_summary_enabled", "wrong type").commit()
        assertFalse(prefs.dailySummaryEnabled)
        val error = AppErrors.pending.value.last { it.operation == "Reading app settings" }
        assertTrue(error.detail.contains("ClassCastException"))
    }

    @Test fun failedScreenTaskShowsADialogWithoutClosingTheScreen() {
        compose.setContent {
            ZigTheme {
                Text("Screen is still running")
                SafeLaunchedEffect(Unit, operation = "Loading screen") {
                    throw IOException("Forced screen task failure")
                }
                AppErrorDialog()
            }
        }
        assertError("Loading screen", "Forced screen task failure")
        compose.onNodeWithText("Screen is still running").assertIsDisplayed()
    }

    private fun assertError(operation: String, detail: String) {
        compose.waitUntil(10_000) { AppErrors.pending.value.any { it.operation == operation && it.detail.contains(detail) } }
        // Previous fault-injection tests may have queued unrelated reports.
        AppErrors.pending.value.filterNot { it.operation == operation && it.detail.contains(detail) }
            .forEach { AppErrors.dismiss(it.id) }
        compose.onNodeWithText("Something went wrong").assertIsDisplayed()
        compose.onNodeWithText(operation).assertIsDisplayed()
        compose.onNode(hasText(detail, substring = true)).assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Something went wrong").assertDoesNotExist()
    }

    private fun rejectWrites(action: String) {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER force_error BEFORE $action ON keyword_rule " +
            "BEGIN SELECT RAISE(ABORT, 'Forced $action SQL failure'); END")
    }

    private fun removeTrigger() = database.openHelper.writableDatabase.execSQL("DROP TRIGGER force_error")

    private fun rulesViewModel(rules: KeywordRuleDao): CustomRulesViewModel {
        val filter = FilterState(database, DefaultRuleSeeder(dao, prefs), ContactsSyncManager(context, scope), prefs)
        lateinit var vm: CustomRulesViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CustomRulesViewModel(rules, filter, prefs) as T
            })[CustomRulesViewModel::class.java]
        }
        return vm
    }

    private suspend fun await(check: suspend () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(30)
    }
}
