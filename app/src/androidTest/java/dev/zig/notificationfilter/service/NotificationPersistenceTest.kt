package dev.zig.notificationfilter.service

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.local.FilterReadiness
import dev.zig.notificationfilter.data.local.FilterState
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.*
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun versionNineMigrationPreservesHistoryAndAddsHonestDeliveryDefaults() = runBlocking {
        val name = "migration-${UUID.randomUUID()}"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("dev.zig.notificationfilter.data.local.db.AppDatabase/9.json").bufferedReader().use { it.readText() })
            .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("""INSERT INTO notification_review
                (id,jobId,packageName,title,content,timestamp,systemDecision,reviewState,syncStatus,modelConfidence,inferredCategory,userOverrideStatus,messageText)
                VALUES (1,'old-job','old-app','OTP','123456',1,'KEYWORD_PASS','PENDING','UNPROCESSED',0,'UNKNOWN','NONE','OTP 123456')""")
            old.version = 9
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_9_10).build()
        try {
            val old = checkNotNull(database.notificationReviewDao().getById(1L))
            assertEquals("123456", old.content)
            assertEquals("LEGACY", old.deliveryStatus)
            assertEquals("", old.deliveryDetail)
            assertTrue(database.notificationProcessingDao().pending().isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun pendingKeysAndCompletedFingerprintsSurviveDatabaseReopen() = runBlocking {
        val name = "pending-${UUID.randomUUID()}"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var database = open()
        try {
            database.notificationProcessingDao().save(NotificationProcessingEntity("source", "fingerprint", 10, 20, "job", "DEFERRED"))
            database.notificationProcessingDao().save(NotificationProcessingEntity("done", "done-fingerprint", 10, 20, "done-job", "COMPLETE"))
            database.close()
            database = open()
            assertEquals("source", database.notificationProcessingDao().pending().single().sourceKey)
            assertEquals("done-fingerprint", database.notificationProcessingDao().get("done")?.fingerprint)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun listenerReadinessWaitsForTheCompleteSavedSnapshot() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val preferences = ZigUserPreferences(context)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val state = FilterState(database, DefaultRuleSeeder(database.keywordRuleDao(), preferences),
            ContactsSyncManager(context, scope), preferences)
        val transactionEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pkg = "startup-${UUID.randomUUID()}"
        try {
            val saving = launch(Dispatchers.IO) {
                database.withTransaction {
                    database.managedAppDao().insert(ManagedAppEntity(pkg))
                    database.keywordRuleDao().insert(KeywordRuleEntity(conditions = listOf(pkg)))
                    transactionEntered.complete(Unit)
                    release.await()
                }
            }
            transactionEntered.await()
            val loading = launch(Dispatchers.IO) { state.awaitReady() }
            delay(150L)
            assertFalse(loading.isCompleted)
            assertEquals(FilterReadiness.LOADING, state.readiness.value)
            release.complete(Unit)
            withTimeout(10_000L) { saving.join(); loading.join() }
            assertEquals(FilterReadiness.READY, state.readiness.value)
            assertTrue(NativeBridge.isAppManaged(pkg))
            assertEquals(NativeBridge.KeywordMatch.ALLOW, NativeBridge.matchKeywords(pkg))
        } finally {
            release.complete(Unit)
            database.close(); scope.cancel()
            (context as dev.zig.notificationfilter.ZigApp).filterState.refresh()
        }
    }
}
