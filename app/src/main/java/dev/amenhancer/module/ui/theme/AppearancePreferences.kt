package dev.amenhancer.module.ui.theme

import android.content.Context
import android.content.res.Configuration

internal enum class AppUiStyle(val displayName: String) {
    ORIGINAL("原有主题"),
    MATERIAL3("Material 3 Expressive"),
}

internal enum class AppThemeMode(val displayName: String) {
    SYSTEM("自动"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** Presets mirror the four-quadrant palette cards in Android's color picker. */
internal enum class AppThemePalette(
    val displayName: String,
    val primary: Long,
    val primaryContainer: Long,
    val secondary: Long,
    val secondaryContainer: Long,
    val tertiary: Long,
    val tertiaryContainer: Long,
) {
    SAGE(
        "鼠尾草",
        0xFF356A35,
        0xFFB7E9AC,
        0xFF3F6F72,
        0xFFB5E3E5,
        0xFF5C6140,
        0xFFE0E6B7,
    ),
    MINT(
        "薄荷",
        0xFF006B57,
        0xFF9DECCF,
        0xFF356A72,
        0xFFB9E3EB,
        0xFF4D6356,
        0xFFCFE8D8,
    ),
    CYAN(
        "青蓝",
        0xFF006879,
        0xFF8CE8FA,
        0xFF3E5F90,
        0xFFC6DCFF,
        0xFF63597C,
        0xFFE9DDFF,
    ),
    BLUE(
        "蓝色",
        0xFF365E91,
        0xFFC9DFFF,
        0xFF5A5D72,
        0xFFDFE1F9,
        0xFF75546F,
        0xFFFFD7F3,
    ),
    INDIGO(
        "靛蓝",
        0xFF575A91,
        0xFFDEE0FF,
        0xFF5E5D72,
        0xFFE3E1F9,
        0xFF79536B,
        0xFFFFD8EB,
    ),
    VIOLET(
        "紫罗兰",
        0xFF66558F,
        0xFFE9DDFF,
        0xFF635B70,
        0xFFE9DFF7,
        0xFF7E5263,
        0xFFFFD9E2,
    ),
    PURPLE(
        "紫色",
        0xFF775086,
        0xFFF9D8FF,
        0xFF6C586B,
        0xFFF5DDF2,
        0xFF82524F,
        0xFFFFDAD5,
    ),
    PINK(
        "粉色",
        0xFF884A6A,
        0xFFFFD8E8,
        0xFF74565F,
        0xFFFFD9E1,
        0xFF7D5635,
        0xFFFFDCBD,
    ),
    CORAL(
        "珊瑚",
        0xFF98472F,
        0xFFFFDBD1,
        0xFF80553F,
        0xFFFFDBCA,
        0xFF6F5D10,
        0xFFF7E28B,
    ),
    AMBER(
        "琥珀",
        0xFF8A5100,
        0xFFFFDDB3,
        0xFF665E40,
        0xFFEDE2BD,
        0xFF47664A,
        0xFFC9ECC9,
    ),
    LIME(
        "青柠",
        0xFF5D6400,
        0xFFE3EB87,
        0xFF3E6656,
        0xFFBDECD6,
        0xFF5C6140,
        0xFFE0E6B7,
    ),
}

internal data class AppAppearanceSettings(
    val style: AppUiStyle = AppUiStyle.MATERIAL3,
    val dynamicColor: Boolean = true,
    val palette: AppThemePalette = AppThemePalette.CORAL,
    val mode: AppThemeMode = AppThemeMode.SYSTEM,
)

internal class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun settings(): AppAppearanceSettings = AppAppearanceSettings(
        style = enumValue(KEY_STYLE, AppUiStyle.MATERIAL3),
        dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, true),
        palette = enumValue(KEY_PALETTE, AppThemePalette.CORAL),
        mode = enumValue(KEY_MODE, AppThemeMode.SYSTEM),
    )

    fun save(settings: AppAppearanceSettings) {
        preferences.edit()
            .putString(KEY_STYLE, settings.style.name)
            .putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
            .putString(KEY_PALETTE, settings.palette.name)
            .putString(KEY_MODE, settings.mode.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        preferences.getString(key, null)
            ?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
            ?: fallback

    companion object {
        private const val PREFERENCES_NAME = "appearance"
        private const val KEY_STYLE = "ui_style"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_PALETTE = "palette"
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
