package dev.zig.notificationfilter.ui.review

import androidx.compose.ui.graphics.Color

/** Container and on-container colours for each notification category chip. */
data class CategoryChipColors(val container: Color, val onContainer: Color)

private val CATEGORY_COLORS = mapOf(
    "FINANCE"   to CategoryChipColors(Color(0xFFE8F5E9), Color(0xFF1B5E20)),
    "FRAUD"     to CategoryChipColors(Color(0xFFFFEBEE), Color(0xFFB71C1C)),
    "SOCIAL"    to CategoryChipColors(Color(0xFFEDE7F6), Color(0xFF4527A0)),
    "SHOPPING"  to CategoryChipColors(Color(0xFFFFF3E0), Color(0xFFE65100)),
    "FOOD"      to CategoryChipColors(Color(0xFFFFF8E1), Color(0xFFF57F17)),
    "TRANSPORT" to CategoryChipColors(Color(0xFFE0F7FA), Color(0xFF006064)),
    "UNKNOWN"   to CategoryChipColors(Color(0xFFF5F5F5), Color(0xFF424242)),
)

private val FALLBACK = CategoryChipColors(Color(0xFFF5F5F5), Color(0xFF424242))

/**
 * Returns the container/on-container colour pair for [category].
 * Accepts either the display form ("SOCIAL") or the prefixed form ("CATEGORY_SOCIAL").
 */
fun categoryChipColors(category: String): CategoryChipColors =
    CATEGORY_COLORS[category.removePrefix("CATEGORY_")] ?: FALLBACK
