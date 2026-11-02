package dev.zig.notificationfilter.domain.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.EntryPointAccessors
import dev.zig.notificationfilter.data.local.db.AppDatabase
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.domain.backup.RacMemoryEntry
import dev.zig.notificationfilter.domain.backup.toRestoredEntity
import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalMemoryRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val embedder = EntryPointAccessors.fromApplication(context, MemoryTestDependencies::class.java).embedder()

    @Test fun realEmbeddingEngineRemembersBothBlockAndAllow() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.notificationReviewDao()
            val memory = PersonalMemoryRepository(dao, embedder)
            for ((index, status) in listOf("MANUALLY_BLOCKED", "MANUALLY_ALLOWED").withIndex()) {
                val text = "Message $index Please check your delivery updates"
                dao.insert(NotificationReviewEntity(jobId = "memory-$index", packageName = "test", title = "Message $index",
                    content = "Please check your delivery updates", messageText = text,
                    timestamp = System.currentTimeMillis(), systemDecision = "MODEL_BLOCKED", userOverrideStatus = status))
                memory.rememberOverride(checkNotNull(dao.getByJobId("memory-$index")).id)
            }
            val corpus = memory.corpus()
            assertEquals(2, corpus.size)
            assertTrue(corpus.any { it.blocked })
            assertTrue(corpus.any { !it.blocked })
            assertTrue(corpus.all { it.embedding.isNotEmpty() && it.embedding.all(Float::isFinite) })
            for (index in 0..1) {
                val row = checkNotNull(dao.getByJobId("memory-$index"))
                database.openHelper.readableDatabase.query("SELECT typeof(embedding), length(embedding) FROM notification_review WHERE id = ?",
                    arrayOf(row.id)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("blob", cursor.getString(0))
                    assertEquals(checkNotNull(row.embedding).size * Float.SIZE_BYTES, cursor.getInt(1))
                }
                memory.forgetOverride(row.id)
                assertNull(dao.getById(row.id)?.embedding)
            }
            assertTrue(memory.corpus().isEmpty())
        } finally { database.close() }
    }

    @Test fun restoredDecisionsCanBeRelearnedFromTheirStoredMessageText() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.notificationReviewDao()
            val text = "A message remembered from an older backup"
            val id = dao.restoreOverrides(listOf(RacMemoryEntry(text, "MANUALLY_BLOCKED", timestamp = 100)
                .toRestoredEntity())).single()
            val memory = PersonalMemoryRepository(dao, embedder)
            memory.rememberOverride(id)
            assertNotNull(dao.getById(id)?.embedding)
            assertTrue(memory.corpus().single().blocked)
        } finally { database.close() }
    }

    @Test fun changedVerdictInvalidatesCachedLabelsEvenIfEmbeddingIsUnavailable() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.notificationReviewDao()
            val id = dao.restoreOverrides(listOf(NotificationReviewEntity(jobId = "cached-label", packageName = "test",
                title = "Cached decision", content = "", timestamp = 100, systemDecision = "MODEL_BLOCKED",
                userOverrideStatus = "MANUALLY_BLOCKED", embedding = floatArrayOf(1f, 0f)))).single()
            val memory = PersonalMemoryRepository(dao, object : TextEmbedder {
                override suspend fun embed(text: String): FloatArray? = null
            })
            assertTrue(memory.corpus().single().blocked)
            dao.updateOverrideStatus(id, "MANUALLY_ALLOWED")
            memory.rememberOverride(id)
            assertFalse(memory.corpus().single().blocked)
            assertNotNull(dao.getExactMatchOverride("Cached decision"))
        } finally { database.close() }
    }

    @Test fun undoDuringEmbeddingDoesNotResurrectTheRemovedVector() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.notificationReviewDao()
            val id = dao.restoreOverrides(listOf(NotificationReviewEntity(jobId = "undo-race", packageName = "test",
                title = "Undo decision", content = "", timestamp = 100, systemDecision = "MODEL_BLOCKED",
                userOverrideStatus = "MANUALLY_BLOCKED"))).single()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val memory = PersonalMemoryRepository(dao, object : TextEmbedder {
                override suspend fun embed(text: String): FloatArray {
                    started.complete(Unit)
                    finish.await()
                    return floatArrayOf(1f, 0f)
                }
            })
            val learning = launch { memory.rememberOverride(id) }
            started.await()
            dao.updateOverrideStatus(id, "NONE")
            memory.forgetOverride(id)
            finish.complete(Unit)
            learning.join()
            assertNull(dao.getById(id)?.embedding)
            assertTrue(memory.corpus().isEmpty())
        } finally { database.close() }
    }
}
