package dev.zig.notificationfilter.ui.review

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.zig.notificationfilter.data.local.db.*
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.memory.MemoryTestDependencies
import dev.zig.notificationfilter.domain.memory.PersonalMemoryRepository
import dev.zig.notificationfilter.ui.dailyreview.DailyReviewScreen
import dev.zig.notificationfilter.ui.dailyreview.DailyReviewUiState
import dev.zig.notificationfilter.ui.dailyreview.DailyReviewViewModel
import dev.zig.notificationfilter.ui.theme.ZigTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewDecisionRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val dao = database.notificationReviewDao()
    private val embedder = EntryPointAccessors.fromApplication(context, MemoryTestDependencies::class.java).embedder()
    private val memory = PersonalMemoryRepository(dao, embedder)
    private val store = ViewModelStore()
    private val prefs = ZigUserPreferences(context)
    private val originalTourSeen = prefs.notificationsTourSeen

    @Before fun prepare() {
        prefs.notificationsTourSeen = true
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            automation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
        }
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        prefs.notificationsTourSeen = originalTourSeen
        database.close()
    }

    @Test fun leftAndRightSwipesPersistDeduplicatedDecisionsAndRealMemory() = runBlocking {
        seed("first", "First card", "PUBLISHED", 2)
        seed("second", "Second card", "MODEL_BLOCKED", 1)
        val vm = viewModel { DailyReviewViewModel(dao, memory, context) }
        compose.setContent { ZigTheme { DailyReviewScreen(onDone = {}, viewModel = vm) } }
        compose.waitUntil(10_000) { vm.uiState.value is DailyReviewUiState.Deck }
        compose.onNodeWithTag("daily-review-card").performTouchInput { swipeLeft(durationMillis = 400) }
        compose.waitForIdle()
        await { dao.getByJobId("second-0")?.embedding != null }
        assertEquals("MANUALLY_BLOCKED", dao.getByJobId("second-0")?.userOverrideStatus)
        compose.onNodeWithTag("daily-review-card").performTouchInput { swipeRight(durationMillis = 400) }
        compose.waitForIdle()
        await { dao.getByJobId("first-1")?.embedding != null || dao.getByJobId("first-0")?.embedding != null }
        for (index in 0..1) assertEquals("MANUALLY_ALLOWED", dao.getByJobId("first-$index")?.userOverrideStatus)
        assertEquals(2, memory.corpus().size)
        assertEquals(1, memory.corpus().count { it.blocked })
        compose.runOnIdle { assertEquals(DailyReviewUiState.AllCaughtUp, vm.uiState.value) }
    }

    @Test fun normalBlockAndAllowControlsSaveMemoryAndUndoClearsTheVector() = runBlocking {
        seed("normal", "Normal review card", "PUBLISHED", 2)
        val vm = viewModel { NotificationReviewViewModel(dao, database.appCategoryOverrideDao(),
            database.managedAppDao(), memory, prefs, context) }
        compose.setContent { ZigTheme { NotificationReviewRoute(viewModel = vm) } }
        compose.waitUntil(10_000) { vm.uiState.value is ReviewUiState.Content }
        compose.onAllNodesWithText("Block & Mute")[0].performScrollTo().performClick()
        await { dao.getByJobId("normal-0")?.embedding != null || dao.getByJobId("normal-1")?.embedding != null }
        for (index in 0..1) assertEquals("MANUALLY_BLOCKED", dao.getByJobId("normal-$index")?.userOverrideStatus)
        assertTrue(memory.corpus().single().blocked)
        compose.onAllNodesWithText("Allow")[0].performScrollTo().performClick()
        await { dao.getByJobId("normal-0")?.userOverrideStatus == "MANUALLY_ALLOWED" && memory.corpus().none { it.blocked } }
        val embedded = dao.getPersonalMemory().single()
        compose.runOnIdle { vm.onUndoClicked(embedded.id) }
        await { dao.getById(embedded.id)?.embedding == null }
        assertEquals("NONE", dao.getById(embedded.id)?.userOverrideStatus)
        assertTrue(memory.corpus().isEmpty())
    }

    @Test fun failedSwipeWriteRestoresTheCardAndAllowsRetry() = runBlocking {
        seed("retry", "Retry card", "PUBLISHED", 1)
        var failNext = true
        val failingDao = object : NotificationReviewDao by dao {
            override suspend fun applyDecisionToIds(ids: List<Long>, state: String, status: String) {
                if (failNext) { failNext = false; error("Simulated write failure") }
                dao.applyDecisionToIds(ids, state, status)
            }
        }
        val vm = viewModel { DailyReviewViewModel(failingDao, memory, context) }
        compose.setContent { ZigTheme { DailyReviewScreen(onDone = {}, viewModel = vm) } }
        compose.waitUntil(10_000) { vm.uiState.value is DailyReviewUiState.Deck }
        compose.onNodeWithTag("daily-review-card").performTouchInput { swipeLeft(durationMillis = 400) }
        compose.waitForIdle()
        compose.waitUntil(10_000) { !failNext && vm.uiState.value is DailyReviewUiState.Deck }
        assertEquals("NONE", dao.getByJobId("retry-0")?.userOverrideStatus)
        compose.onNodeWithTag("daily-review-card").assertIsDisplayed()
            .performTouchInput { swipeLeft(durationMillis = 400) }
        compose.waitForIdle()
        await { dao.getByJobId("retry-0")?.embedding != null }
        assertEquals("MANUALLY_BLOCKED", dao.getByJobId("retry-0")?.userOverrideStatus)
        assertTrue(memory.corpus().single().blocked)
    }

    private suspend fun seed(prefix: String, title: String, decision: String, count: Int) {
        repeat(count) { index -> dao.insert(NotificationReviewEntity(jobId = "$prefix-$index", packageName = "review.test",
            title = title, content = "Message to review", timestamp = System.currentTimeMillis(), systemDecision = decision,
            messageText = "$title Message to review")) }
    }

    private inline fun <reified T : ViewModel> viewModel(crossinline create: () -> T): T {
        lateinit var result: T
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
            })[T::class.java]
        }
        return result
    }

    private suspend fun await(check: suspend () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(30)
    }
}
