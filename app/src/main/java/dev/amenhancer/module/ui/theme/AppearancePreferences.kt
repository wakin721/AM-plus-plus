package dev.amenhancer.module.ui.theme

import android.content.Context
import android.content.res.Configuration

internal enum class AppThemeMode(val displayName: String) {
    SYSTEM("自动"),
    LIGHT("浅色"),
    DARK("深色"),
}

internal data class AppAppearanceSettings(
    val mode: AppThemeMode = AppThemeMode.SYSTEM,
)

internal class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun settings(): AppAppearanceSettings = AppAppearanceSettings(
        mode = enumValue(KEY_MODE, AppThemeMode.SYSTEM),
    )

    fun save(settings: AppAppearanceSettings) {
        preferences.edit()
            .remove("ui_style")
            .remove("dynamic_color")
            .remove("palette")
            .putString(KEY_MODE, settings.mode.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        preferences.getString(key, null)
            ?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
            ?: fallback

    companion object {
        private const val PREFERENCES_NAME = "appearance"
        private const val KEY_MODE = "theme_mode"

        fun themedContext(base: Context): Context {
            val mode = AppearancePreferences(base).settings().mode
            if (mode == AppThemeMode.SYSTEM) return base
            val configuration = Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or when (mode) {
                    AppThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
                    AppThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
                    AppThemeMode.SYSTEM -> Configuration.UI_MODE_NIGHT_UNDEFINED
                }
            }
            return base.createConfigurationContext(configuration)
        }
    }
}
