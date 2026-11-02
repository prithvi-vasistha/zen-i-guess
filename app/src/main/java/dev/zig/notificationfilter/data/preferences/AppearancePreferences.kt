package dev.zig.notificationfilter.data.preferences

/** Stable IDs keep saved settings and portable backups independent of enum names. */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "System"), LIGHT("light", "Light"), DARK("dark", "Dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

enum class ThemeStyle(val id: String, val label: String, val description: String) {
    ZIG("zig", "ZiG", "Clean, neutral surfaces"),
    SOFT("soft", "Soft", "Surfaces tinted with your accent"),
    PAPER("paper", "Paper", "Warm, comfortable surfaces"),
    AMOLED("amoled", "AMOLED", "Pure black in dark mode");
}

enum class AccentColor(val id: String, val label: String) {
    GREEN("green", "Green"), BLUE("blue", "Blue"), PURPLE("purple", "Purple"),
    ROSE("rose", "Rose"), ORANGE("orange", "Orange"), TEAL("teal", "Teal"),
    RED("red", "Red"), AMBER("amber", "Amber");
}

data class AppearancePreferences(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val style: ThemeStyle = ThemeStyle.ZIG,
    val accent: AccentColor = AccentColor.GREEN,
) {
    companion object {
        fun fromIds(mode: String?, style: String?, accent: String?) = AppearancePreferences(
            ThemeMode.entries.firstOrNull { it.id == mode } ?: ThemeMode.SYSTEM,
            ThemeStyle.entries.firstOrNull { it.id == style } ?: ThemeStyle.ZIG,
            AccentColor.entries.firstOrNull { it.id == accent } ?: AccentColor.GREEN,
        )
    }
}
