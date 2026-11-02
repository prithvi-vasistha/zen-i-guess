package dev.zig.notificationfilter.domain.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.zig.notificationfilter.ZigApp
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.local.FilterState
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.*
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.classifier.*
import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import dev.zig.notificationfilter.domain.memory.MemoryTestDependencies
import dev.zig.notificationfilter.domain.memory.PersonalMemoryRepository
import dev.zig.notificationfilter.domain.memory.VectorSearchEngine
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRestoreRegressionTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val prefix = "backup-test-${UUID.randomUUID()}-"
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val dao = database.notificationReviewDao()
    private val preferences = ZigUserPreferences(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val embedder = EntryPointAccessors.fromApplication(app, MemoryTestDependencies::class.java).embedder()
    private val memory = PersonalMemoryRepository(dao, embedder)
    private val filterState = FilterState(database, DefaultRuleSeeder(database.keywordRuleDao(), preferences),
        ContactsSyncManager(context, scope), preferences)

    private fun manager(engine: TextEmbedder = embedder) = BackupRestoreManager(dao, database.managedAppDao(),
        filterState, database.keywordRuleDao(), database.appCategoryOverrideDao(), preferences,
        DailySummaryScheduler(context, preferences), engine, memory)

    @Before fun prepare() { preferences.defaultRulesSeeded = true; preferences.contactsBypassEnabled = false }

    @After fun cleanup() = runBlocking {
        database.close()
        scope.cancel()
        app.deleteSharedPreferences(prefix + "zig_user_prefs")
        app.deleteSharedPreferences(prefix + "daily_summary_schedule")
        (app as ZigApp).filterState.refresh()
    }

    @Test fun versionOneMemoryOnlyBackupRestoresRealLearning() = verifyLegacy(1)
    @Test fun versionTwoBackupRestoresMemoryAppsAndAllowRules() = verifyLegacy(2)
    @Test fun versionThreeBackupRestoresMemoryAndBothRuleTypes() = verifyLegacy(3)

    private fun verifyLegacy(version: Int) = runBlocking {
        val result = manager().restoreBackup(fixture(version).byteInputStream())
        assertEquals(4, result.overridesRestored)
        assertEquals(4, result.overridesEmbedded)
        assertEquals(4, memory.corpus().size)
        assertEquals(3, memory.corpus().count { it.blocked })
        assertEquals(version >= 2, NativeBridge.isAppManaged("com.shop.app"))
        if (version >= 2) assertEquals(NativeBridge.KeywordMatch.ALLOW, NativeBridge.matchKeywords("OTP verification"))
        if (version >= 3) assertEquals(NativeBridge.KeywordMatch.BLOCK, NativeBridge.matchKeywords("promotion"))
        val classifier = EnsembleClassifier(object : ZigClassifierEngine {
            override suspend fun evaluate(category: NotificationCategory, packageName: String, text: String) =
                ClassifierResult(true, 0.1f, category)
        }, embedder, memory, VectorSearchEngine(), dao)
        val blocked = classifier.evaluate(NotificationCategory.UNKNOWN, "com.shop.app", OFFER)
        assertFalse(blocked.allowed)
        assertEquals(DecisionSource.EXACT_MATCH_OVERRIDE, blocked.source)
        val allowed = classifier.evaluate(NotificationCategory.UNKNOWN, "com.bank.app", SALARY)
        assertTrue(allowed.allowed)
        assertEquals(DecisionSource.EXACT_MATCH_OVERRIDE, allowed.source)
        val similar = classifier.evaluate(NotificationCategory.UNKNOWN, "com.shop.app", "$OFFER!")
        assertFalse(similar.allowed)
        assertEquals(DecisionSource.PERSONAL_MEMORY, similar.source)
        manager().restoreBackup(fixture(version).byteInputStream())
        assertEquals(4, dao.getAllOverrides().size)
        assertEquals(4, memory.corpus().size)
    }

    @Test fun importingAnotherOrEmptyBackupKeepsEarlierAndLocalMemory() = runBlocking {
        manager().restoreBackup(fixture(1).byteInputStream())
        val local = NotificationReviewEntity(jobId = "local-history", packageName = "local", title = "My local decision",
            content = "", messageText = "My local decision", timestamp = 500, systemDecision = "MODEL_BLOCKED",
            userOverrideStatus = "MANUALLY_ALLOWED")
        dao.insert(local)
        memory.rememberOverride(checkNotNull(dao.getByJobId(local.jobId)).id)
        val second = """{"version":1,"export_date":200,"preferences":{"daily_summary_enabled":false,
            "sensitive_notifications_enabled":true},"rac_memory":[{"messageText":"Second backup decision",
            "userOverrideStatus":"MANUALLY_BLOCKED","timestamp":600}]}"""
        manager().restoreBackup(second.byteInputStream())
        val empty = """{"version":1,"export_date":200,"preferences":{"daily_summary_enabled":false,
            "sensitive_notifications_enabled":true},"rac_memory":[]}"""
        manager().restoreBackup(empty.byteInputStream())
        assertEquals(6, memory.corpus().size)
        assertNotNull(dao.getExactMatchOverride(OFFER))
        assertNotNull(dao.getExactMatchOverride("Second backup decision"))
        assertEquals("MODEL_BLOCKED", dao.getByJobId(local.jobId)?.systemDecision)
    }

    @Test fun reimportingAnOlderBackupKeepsSubsequentCorrections() = runBlocking {
        manager().restoreBackup(fixture(3).byteInputStream())
        val latest = checkNotNull(dao.getExactMatchOverride(OFFER))
        dao.updateOverrideStatus(latest.id, "MANUALLY_ALLOWED")
        dao.updateUserAssignedCategory(latest.id, "CATEGORY_FINANCE")
        memory.reload()
        manager().restoreBackup(fixture(3).byteInputStream())
        assertEquals("MANUALLY_ALLOWED", dao.getExactMatchOverride(OFFER)?.userOverrideStatus)
        assertEquals("CATEGORY_FINANCE", dao.getExactMatchOverride(OFFER)?.userAssignedCategory)
        assertEquals(4, dao.getAllOverrides().size)
        assertEquals(2, memory.corpus().count { it.blocked })
    }

    @Test fun interruptedEmbeddingKeepsAllDecisionsAndRetryFinishesWithoutDuplicates() = runBlocking {
        var calls = 0
        val failing = object : TextEmbedder {
            override suspend fun embed(text: String): FloatArray? {
                if (++calls == 2) error("Simulated interrupted import")
                return embedder.embed(text)
            }
        }
        try {
            manager(failing).restoreBackup(fixture(1).byteInputStream())
            fail("Expected interruption")
        } catch (_: IllegalStateException) { }
        assertEquals(4, dao.getAllOverrides().size)
        assertNotNull(dao.getExactMatchOverride(SALARY))
        assertEquals(1, memory.corpus().size)
        val result = manager().restoreBackup(fixture(1).byteInputStream())
        assertEquals(4, result.overridesEmbedded)
        assertEquals(4, memory.corpus().size)
        assertEquals(4, dao.getAllOverrides().size)
    }

    @Test fun exportRecoversLegacyTextAndPreservesReminderTime() = runBlocking {
        preferences.dailySummaryHour = 19
        preferences.dailySummaryMinute = 35
        dao.insert(NotificationReviewEntity(jobId = "legacy-row", packageName = "local", title = "Legacy title",
            content = "Legacy body", timestamp = 500, systemDecision = "MODEL_BLOCKED", userOverrideStatus = "MANUALLY_BLOCKED"))
        val output = ByteArrayOutputStream()
        manager().createBackup(output)
        val payload = Json.decodeFromString(BackupPayload.serializer(), output.toString("UTF-8"))
        assertEquals("Legacy title Legacy body", payload.racMemory.single().messageText)
        assertEquals(19, payload.preferences.dailySummaryHour)
        assertEquals(35, payload.preferences.dailySummaryMinute)
        manager().restoreBackup(output.toByteArray().inputStream())
        assertEquals(1, dao.getAllOverrides().size)
        assertNotNull(dao.getExactMatchOverride("Legacy title Legacy body"))
        assertEquals(1, memory.corpus().size)
    }

    private fun fixture(version: Int) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("backups/version-$version.json").bufferedReader().use { it.readText() }

    @Test fun hiddenAlertChoiceSurvivesExportAndRestore() = runBlocking {
        preferences.hiddenContentAlertsEnabled = false
        val output = ByteArrayOutputStream()
        manager().createBackup(output)
        preferences.hiddenContentAlertsEnabled = true
        manager().restoreBackup(output.toByteArray().inputStream())
        assertFalse(preferences.hiddenContentAlertsEnabled)
        assertFalse(ZigUserPreferences(context).hiddenContentAlertsEnabled)
    }

    @Test fun olderBackupsPreserveAnExplicitHiddenAlertOptOut() = runBlocking {
        preferences.hiddenContentAlertsEnabled = false
        manager().restoreBackup(fixture(1).byteInputStream())
        assertFalse(preferences.hiddenContentAlertsEnabled)
    }

    private companion object {
        const val OFFER = "Shop Offer Exclusive sale on running shoes today"
        const val SALARY = "Bank Payment Your salary has been credited"
    }
}
