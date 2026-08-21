package dev.amenhancer.module.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive shell for AM++'s own UI.
 *
 * Apple Music injected surfaces intentionally do not use this theme so they can
 * continue to follow Apple Music's visual language.
 */
@Composable
internal fun AmppExpressiveTheme(
    appearance: AppAppearanceSettings = AppAppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme = when (appearance.mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> appearance.palette.darkColorScheme()
        else -> appearance.palette.lightColorScheme()
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = Shapes(
            largeIncreased = RoundedCornerShape(36.dp),
        ),
        content = content,
    )
}

private fun AppThemePalette.lightColorScheme() = lightColorScheme(
    primary = Color(primary.toInt()),
    onPrimary = Color.White,
    primaryContainer = Color(primaryContainer.toInt()),
    onPrimaryContainer = Color(primary.toInt()),
    secondary = Color(secondary.toInt()),
    onSecondary = Color.White,
    secondaryContainer = Color(secondaryContainer.toInt()),
    onSecondaryContainer = Color(secondary.toInt()),
    tertiary = Color(tertiary.toInt()),
    onTertiary = Color.White,
    tertiaryContainer = Color(tertiaryContainer.toInt()),
    onTertiaryContainer = Color(tertiary.toInt()),
)

private fun AppThemePalette.darkColorScheme() = darkColorScheme(
    primary = Color(primaryContainer.toInt()),
    onPrimary = Color(primary.toInt()),
    primaryContainer = Color(primary.toInt()),
    onPrimaryContainer = Color(primaryContainer.toInt()),
    secondary = Color(secondaryContainer.toInt()),
    onSecondary = Color(secondary.toInt()),
    secondaryContainer = Color(secondary.toInt()),
    onSecondaryContainer = Color(secondaryContainer.toInt()),
    tertiary = Color(tertiaryContainer.toInt()),
    onTertiary = Color(tertiary.toInt()),
    tertiaryContainer = Color(tertiary.toInt()),
    onTertiaryContainer = Color(tertiaryContainer.toInt()),
)
