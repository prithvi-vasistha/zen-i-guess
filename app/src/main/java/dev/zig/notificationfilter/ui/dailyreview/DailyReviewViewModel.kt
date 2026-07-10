package dev.zig.notificationfilter.ui.dailyreview

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.ReviewState
import dev.zig.notificationfilter.domain.memory.PersonalMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

// One flashcard = one deduplicated group of identical notifications (same package, title,
// and content). [ids] holds every underlying row so a single swipe decides all of them.
data class DailyReviewCard(
    val key: String,
    val packageName: String,
    val title: String,
    val content: String,
    val count: Int,
    val ids: List<Long>,
    // The AI's original verdict, shown as context on the card: true = allowed (PUBLISHED),
    // false = blocked (MODEL_BLOCKED / LLM_BLOCKED).
    val aiAllowed: Boolean,
)

sealed interface DailyReviewUiState {
    data object Loading : DailyReviewUiState
    data class Deck(val cards: List<DailyReviewCard>) : DailyReviewUiState
    // Deck exhausted (or nothing to review today): the "Inbox Zero" success state.
    data object AllCaughtUp : DailyReviewUiState
}

@HiltViewModel
class DailyReviewViewModel @Inject constructor(
    private val dao: NotificationReviewDao,
    private val personalMemory: PersonalMemory,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DailyReviewUiState>(DailyReviewUiState.Loading)
    val uiState: StateFlow<DailyReviewUiState> = _uiState.asStateFlow()

    private val labelCache = ConcurrentHashMap<String, String>()
    private val _packageLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val packageLabels: StateFlow<Map<String, String>> = _packageLabels.asStateFlow()

    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
    private val _appIcons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val appIcons: StateFlow<Map<String, ImageBitmap>> = _appIcons.asStateFlow()

    init {
        loadDeck()
    }

    // One-shot snapshot: fetch today's undecided AI decisions, then collapse identical
    // notifications into single cards. groupBy preserves first-seen order and the DAO returns
    // newest-first, so the freshest groups sit at the top of the deck.
    private fun loadDeck() {
        viewModelScope.launch {
            val startOfDayMs = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            val rows = withContext(Dispatchers.IO) { dao.getTodayAiEvaluatedUndecided(startOfDayMs) }
            val cards = rows
                .groupBy { Triple(it.packageName, it.title, it.content) }
                .map { (_, group) ->
                    val first = group.first()
                    DailyReviewCard(
                        key = "${first.packageName}|${first.title}|${first.content}",
                        packageName = first.packageName,
                        title = first.title,
                        content = first.content,
                        count = group.size,
                        ids = group.map { it.id },
                        aiAllowed = first.systemDecision == "PUBLISHED",
                    )
                }

            _uiState.value = if (cards.isEmpty()) {
                DailyReviewUiState.AllCaughtUp
            } else {
                DailyReviewUiState.Deck(cards)
            }
            resolveLabels(cards.map { it.packageName }.toSet())
        }
    }

    // A swipe decision. Removes the card from the deck immediately (optimistic) so the deck
    // advances the moment the card flies off, then persists the decision to every underlying
    // row and records ONE representative into Personal Memory (embedding the same text N times
    // would only add duplicate vectors to the KNN corpus).
    fun onDecision(card: DailyReviewCard, allow: Boolean) {
        val current = (_uiState.value as? DailyReviewUiState.Deck)?.cards ?: return
        val remaining = current.filterNot { it.key == card.key }
        _uiState.value = if (remaining.isEmpty()) {
            DailyReviewUiState.AllCaughtUp
        } else {
            DailyReviewUiState.Deck(remaining)
        }

        viewModelScope.launch(Dispatchers.IO) {
            val state = if (allow) ReviewState.ALLOWED else ReviewState.BLOCKED
            val status = if (allow) "MANUALLY_ALLOWED" else "MANUALLY_BLOCKED"
            // Status written first: rememberOverride reads it back to label the embedding.
            dao.applyDecisionToIds(card.ids, state.name, status)
            personalMemory.rememberOverride(card.ids.first())
        }
    }

    private suspend fun resolveLabels(packageNames: Set<String>) {
        val toResolve = packageNames.filter { !labelCache.containsKey(it) }
        if (toResolve.isEmpty()) return
        withContext(Dispatchers.IO) {
            toResolve.forEach { pkg ->
                labelCache[pkg] = try {
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast('.')
                }
                loadIcon(pkg)?.let { iconCache[pkg] = it }
            }
        }
        _packageLabels.value = HashMap(labelCache)
        _appIcons.value = HashMap(iconCache)
    }

    // Icons drawn into a fixed 48×48 px bitmap to bound memory use (same approach as the review
    // and managed-apps view models).
    private fun loadIcon(packageName: String): ImageBitmap? = try {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, 48, 48)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
