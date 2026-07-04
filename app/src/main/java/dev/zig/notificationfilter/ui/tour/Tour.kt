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

    const val APPS_MANAGE_SWITCH = "apps_manage_switch"
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
}

fun navKeyFor(screen: ZigScreen): String = when (screen) {
    ZigScreen.Review -> TourKeys.NAV_REVIEW
    ZigScreen.Apps -> TourKeys.NAV_APPS
    ZigScreen.Rules -> TourKeys.NAV_RULES
    ZigScreen.Logs -> TourKeys.NAV_LOGS
}

// Tab indices, resolved from the canonical nav order so they stay correct if it changes.
private val TAB_REVIEW = ZigScreen.all.indexOf(ZigScreen.Review)
private val TAB_APPS = ZigScreen.all.indexOf(ZigScreen.Apps)
private val TAB_RULES = ZigScreen.all.indexOf(ZigScreen.Rules)
// Developer-only Logs tab — re-add `Logs` to ZigScreen.all first, then uncomment this and its step.
// private val TAB_LOGS = ZigScreen.all.indexOf(ZigScreen.Logs)

/** The scripted walkthrough: navigate to a tab, then explain the screen and one control. */
val TOUR_STEPS: List<TourStep> = listOf(
    TourStep(
        tab = TAB_APPS,
        targetKey = null,
        title = "Welcome to ZiG",
        body = "A quick tour of the four screens. You can skip it anytime.",
    ),
    TourStep(
        tab = TAB_APPS,
        targetKey = TourKeys.TITLE_APPS,
        title = "Apps",
        body = "Choose which apps ZiG manages. Apps you don't select pass through completely untouched.",
    ),
    TourStep(
        tab = TAB_APPS,
        targetKey = TourKeys.APPS_MANAGE_SWITCH,
        title = "Turn on an app",
        body = "Flip a switch to let ZiG intercept and filter that app's notifications.",
    ),
    TourStep(
        tab = TAB_REVIEW,
        targetKey = TourKeys.TITLE_REVIEW,
        title = "Notifications",
        body = "The last 24 hours of alerts ZiG processed. Allow or Block each one — your choices train ZiG to your preferences. We've added two sample notifications so you can try it.",
    ),
    TourStep(
        tab = TAB_REVIEW,
        targetKey = TourKeys.REVIEW_CATEGORY,
        title = "Category",
        body = "The on-device model tags each notification with a category and shows it here. Tap the chip to correct it — ZiG learns from your choice.",
    ),
    TourStep(
        tab = TAB_REVIEW,
        targetKey = TourKeys.REVIEW_ARCHIVE,
        title = "Archive",
        body = "Open a 30-day history here to find and fix anything you mislabelled more than a day ago.",
    ),
    TourStep(
        tab = TAB_REVIEW,
        targetKey = TourKeys.REVIEW_SETTINGS,
        title = "Settings",
        body = "Configure ZiG here — like the daily 8 PM recap of everything it filtered for you.",
    ),
    TourStep(
        tab = TAB_RULES,
        targetKey = TourKeys.TITLE_RULES,
        title = "Rules",
        body = "Keyword rules that bypass the AI entirely for time-sensitive alerts.",
    ),
    TourStep(
        tab = TAB_RULES,
        targetKey = TourKeys.RULES_INPUT,
        title = "Never miss an OTP",
        body = "Add keywords like OTP or Verification. Matching notifications always pass instantly — before the model even runs.",
    ),
    // Developer-only Logs step — commented out along with the Logs bottom-bar entry. To re-enable,
    // uncomment TAB_LOGS above, re-add Logs to ZigScreen.all, then uncomment this step.
    /*
    TourStep(
        tab = TAB_LOGS,
        targetKey = TourKeys.NAV_LOGS,
        title = "Logs",
        body = "A full pipeline trace of every notification and exactly why it was allowed or blocked.",
    ),
    */
    TourStep(
        tab = TAB_APPS,
        targetKey = null,
        title = "You're all set!",
        body = "Head to the Apps screen to pick what ZiG should watch. You can revisit any screen from the bottom bar.",
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
