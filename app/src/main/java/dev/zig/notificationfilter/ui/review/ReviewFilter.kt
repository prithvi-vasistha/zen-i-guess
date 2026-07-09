package dev.zig.notificationfilter.ui.review

// Quick filter chips shown above the sort row. Applied Kotlin-side in the ViewModel
// after the DAO search, so no extra query variants are needed.
//   ALL          — every reviewable row (AI + rule matches).
//   AI_DECISIONS  — only rows the on-device classifier decided (MODEL_BLOCKED / PUBLISHED /
//                   legacy LLM_BLOCKED). These are the actionable Allow/Block rows.
//   BLOCKED       — anything silenced, whether by the model or a keyword rule
//                   (MODEL_BLOCKED / LLM_BLOCKED / KEYWORD_BLOCKED).
enum class ChipFilter { ALL, AI_DECISIONS, BLOCKED }

data class ReviewFilter(
    val query: String = "",
    val sortField: SortField = SortField.TIME,
    val sortDirection: SortDirection = SortDirection.DESC,
    val chipFilter: ChipFilter = ChipFilter.ALL,
)
