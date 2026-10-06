package dev.amenhancer.module.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue

/** Shared neutral surfaces and red controls for standalone and embedded settings. */
internal class AppleMusicSettingsPalette private constructor(val isDark: Boolean) {
    val background: Int = if (isDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    val surface: Int = if (isDark) 0xFF1E1E1E.toInt() else 0xFFF3F2F7.toInt()
    val primary: Int = 0xFFFA233B.toInt()
    val primaryPressed: Int = 0xFFD91E34.toInt()
    val primaryContainer: Int get() = surface
    val onSurface: Int = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val onSurfaceVariant: Int = if (isDark) 0xFF98989D.toInt() else 0xFF8E8E93.toInt()
    val outline: Int = if (isDark) 0xFF38383A.toInt() else 0xFFE5E5EA.toInt()
    val divider: Int = if (isDark) 0xFF38383A.toInt() else 0xFFD1D1D6.toInt()
    val disabledContainer: Int get() = surface
    val disabledIcon: Int get() = onSurfaceVariant
    val switchTrackOff: Int = if (isDark) 0xFF29292D.toInt() else 0xFFE3E3E8.toInt()

    companion object {
        private val lightPalette = AppleMusicSettingsPalette(false)
        private val darkPalette = AppleMusicSettingsPalette(true)

        fun forDark(dark: Boolean): AppleMusicSettingsPalette = if (dark) darkPalette else lightPalette

        fun resolve(context: Context): AppleMusicSettingsPalette {
            val background = TypedValue()
            val dark = if (context.theme.resolveAttribute(android.R.attr.colorBackground, background, true) &&
                background.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                Color.red(background.data) + Color.green(background.data) + Color.blue(background.data) < 384
            } else {
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            return forDark(dark)
        }
    }
}
