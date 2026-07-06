package dev.zig.notificationfilter.ui.tour

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zig.notificationfilter.ui.navigation.ZigScreen

/**
 * One stop on the guided tour.
 *
 * @param tab       index into [ZigScreen.all] the pager should show for this step.
 * @param targetKey coach-mark key to spotlight, or null for a centered message with
 *                  no cut-out (welcome / finish cards).
 */
data class TourStep(
    val tab: Int,
    val targetKey: String?,
    val title: String,
    val body: String,
)

/** Stable coach-mark keys shared between the instrumented screens and the tour steps. */
object TourKeys {
    const val NAV_REVIEW = "nav_review"
    const val NAV_APPS = "nav_apps"
    const val NAV_RULES = "nav_rules"
    const val NAV_LOGS = "nav_logs"

    const val APPS_ADD_FAB = "apps_add_fab"
    const val REVIEW_ARCHIVE = "review_archive"
    const val REVIEW_CATEGORY = "review_category"
    const val REVIEW_SETTINGS = "review_settings"
    const val RULES_INPUT = "rules_input"
    const val LOGS_HEADER = "logs_header"

    // The bottom navigation bar — kept visible (undimmed, no border) for the whole tour.
    const val NAV_BAR = "nav_bar"

    // Screen-name titles — each is spotlighted once as its screen is introduced.
    const val TITLE_REVIEW = "title_review"
    const val TITLE_APPS = "title_apps"
    const val TITLE_RULES = "title_rules"
    const val TITLE_SETTINGS = "title_settings"
}

fun navKeyFor(screen: ZigScreen): String = when (screen) {
    ZigScreen.Review -> TourKeys.NAV_REVIEW
    ZigScreen.Apps -> TourKeys.NAV_APPS
    ZigScreen.Rules -> TourKeys.NAV_RULES
    ZigScreen.Settings -> TourKeys.TITLE_SETTINGS
    ZigScreen.Logs -> TourKeys.NAV_LOGS
}

// Tab indices, resolved from the canonical nav order so they stay correct if it changes.
private val TAB_REVIEW = ZigScreen.all.indexOf(ZigScreen.Review)
private val TAB_APPS = ZigScreen.all.indexOf(ZigScreen.Apps)
private val TAB_RULES = ZigScreen.all.indexOf(ZigScreen.Rules)
// Developer-only Logs tab — re-add `Logs` to ZigScreen.all first, then uncomment this and its step.
// private val TAB_LOGS = ZigScreen.all.indexOf(ZigScreen.Logs)

/**
 * The scripted walkthrough: navigate to a tab, spotlight one control, explain it. Kept to
 * four teaching steps plus a closing card — add apps, assign category, archive, rules vault.
 */
val TOUR_STEPS: List<TourStep> = listOf(
    TourStep(
        tab = TAB_APPS,
        targetKey = TourKeys.APPS_ADD_FAB,
        title = "Add apps",
        body = "Tap ＋ to pick which apps ZiG manages. Choose an app, confirm, and ZiG starts filtering its notifications. Apps you don't add pass through untouched.",
    ),
    TourStep(
        tab = TAB_REVIEW,
        targetKey = TourKeys.REVIEW_CATEGORY,
        title = "Sort by category",
        body = "ZiG tags every notification with a category. Tap an app's chip to set a default for everything it sends, or a single notification's chip to fix just that one — ZiG learns from each change.",
    ),
    TourStep(
        tab = TAB_REVIEW,
        targetKey = TourKeys.REVIEW_ARCHIVE,
        title = "Archive",
        body = "Switch to a 30-day history to find and re-label anything you missed more than a day ago.",
    ),
    TourStep(
        tab = TAB_RULES,
        targetKey = TourKeys.RULES_INPUT,
        title = "Rules Vault",
        body = "Add keywords like OTP or Verification here. Matching notifications skip the AI and always come through instantly.",
    ),
    TourStep(
        tab = TAB_APPS,
        targetKey = null,
        title = "You're ready to go",
        body = "That's it! Head to Apps to choose what ZiG should watch. You can revisit any screen from the bottom bar anytime.",
    ),
)

/** Drives the current step. Navigation to each step's tab happens in the overlay. */
class TourController(
    val steps: List<TourStep>,
    val registry: CoachMarkRegistry,
    private val onFinish: () -> Unit,
) {
    var currentIndex by mutableIntStateOf(0)
        private set

    val currentStep: TourStep get() = steps[currentIndex]
    val isFirst: Boolean get() = currentIndex == 0
    val isLast: Boolean get() = currentIndex == steps.lastIndex

    fun next() {
        if (isLast) onFinish() else currentIndex++
    }

    fun back() {
        if (!isFirst) currentIndex--
    }

    fun skip() = onFinish()
}

@Composable
fun rememberTourController(
    steps: List<TourStep>,
    registry: CoachMarkRegistry,
    onFinish: () -> Unit,
): TourController = remember(registry) {
    TourController(steps = steps, registry = registry, onFinish = onFinish)
}
