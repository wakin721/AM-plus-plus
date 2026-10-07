package dev.amenhancer.module.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.app.UiModeManager

/** Neutral surfaces and red controls; brightness always follows the system. */
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
            val dark = when (context.getSystemService(UiModeManager::class.java)?.nightMode) {
                UiModeManager.MODE_NIGHT_YES -> true
                UiModeManager.MODE_NIGHT_NO -> false
                // AUTO/CUSTOM use the resolved system configuration, so the
                // host's own theme cannot override the system's current mode.
                else -> Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            return forDark(dark)
        }
    }
}
