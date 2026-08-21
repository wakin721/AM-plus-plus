package dev.amenhancer.module.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
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

private fun AppThemePalette.lightColorScheme(): ColorScheme {
    val primaryColor = Color(primary.toInt())
    val primaryContainerColor = Color(primaryContainer.toInt())
    val secondaryColor = Color(secondary.toInt())
    val secondaryContainerColor = Color(secondaryContainer.toInt())
    val foreground = primaryColor.mixWith(Color.Black, 0.56f)
    val lightBackground = staticBackgroundColor(dark = false)

    return lightColorScheme(
        primary = primaryColor,
        onPrimary = Color.White,
        primaryContainer = primaryContainerColor,
        onPrimaryContainer = primaryColor,
        secondary = secondaryColor,
        onSecondary = Color.White,
        secondaryContainer = secondaryContainerColor,
        onSecondaryContainer = secondaryColor,
        tertiary = Color(tertiary.toInt()),
        onTertiary = Color.White,
        tertiaryContainer = Color(tertiaryContainer.toInt()),
        onTertiaryContainer = Color(tertiary.toInt()),
        background = lightBackground,
        onBackground = foreground,
        surface = lightBackground,
        onSurface = foreground,
        surfaceVariant = secondaryContainerColor.mixWith(Color.White, 0.54f),
        onSurfaceVariant = secondaryColor.mixWith(Color.Black, 0.32f),
        surfaceTint = primaryColor,
        outline = secondaryColor.mixWith(Color.White, 0.28f),
        outlineVariant = secondaryContainerColor.mixWith(Color.White, 0.18f),
        surfaceDim = primaryContainerColor.mixWith(Color.White, 0.50f),
        surfaceBright = primaryContainerColor.mixWith(Color.White, 0.88f),
        surfaceContainerLowest = primaryContainerColor.mixWith(Color.White, 0.76f),
        surfaceContainerLow = primaryContainerColor.mixWith(Color.White, 0.68f),
        surfaceContainer = primaryContainerColor.mixWith(Color.White, 0.58f),
        surfaceContainerHigh = primaryContainerColor.mixWith(Color.White, 0.48f),
        surfaceContainerHighest = primaryContainerColor.mixWith(Color.White, 0.38f),
    )
}

private fun AppThemePalette.darkColorScheme(): ColorScheme {
    val primaryColor = Color(primary.toInt())
    val primaryContainerColor = Color(primaryContainer.toInt())
    val secondaryColor = Color(secondary.toInt())
    val secondaryContainerColor = Color(secondaryContainer.toInt())
    val darkBackground = staticBackgroundColor(dark = true)
    val foreground = primaryContainerColor.mixWith(Color.White, 0.18f)

    return darkColorScheme(
        primary = primaryContainerColor,
        onPrimary = primaryColor,
        primaryContainer = primaryColor,
        onPrimaryContainer = primaryContainerColor,
        secondary = secondaryContainerColor,
        onSecondary = secondaryColor,
        secondaryContainer = secondaryColor,
        onSecondaryContainer = secondaryContainerColor,
        tertiary = Color(tertiaryContainer.toInt()),
        onTertiary = Color(tertiary.toInt()),
        tertiaryContainer = Color(tertiary.toInt()),
        onTertiaryContainer = Color(tertiaryContainer.toInt()),
        background = darkBackground,
        onBackground = foreground,
        surface = darkBackground,
        onSurface = foreground,
        surfaceVariant = secondaryColor.mixWith(Color.Black, 0.46f),
        onSurfaceVariant = secondaryContainerColor.mixWith(Color.White, 0.08f),
        surfaceTint = primaryContainerColor,
        outline = secondaryContainerColor.mixWith(Color.Black, 0.28f),
        outlineVariant = secondaryColor.mixWith(Color.Black, 0.42f),
        surfaceDim = primaryColor.mixWith(Color.Black, 0.84f),
        surfaceBright = primaryColor.mixWith(Color.Black, 0.46f),
        surfaceContainerLowest = primaryColor.mixWith(Color.Black, 0.78f),
        surfaceContainerLow = primaryColor.mixWith(Color.Black, 0.68f),
        surfaceContainer = primaryColor.mixWith(Color.Black, 0.60f),
        surfaceContainerHigh = primaryColor.mixWith(Color.Black, 0.50f),
        surfaceContainerHighest = primaryColor.mixWith(Color.Black, 0.42f),
    )
}

internal fun AppThemePalette.staticBackgroundColor(dark: Boolean): Color =
    if (dark) {
        Color(primary.toInt()).mixWith(Color.Black, 0.72f)
    } else {
        Color(primaryContainer.toInt()).mixWith(Color.White, 0.66f)
    }

private fun Color.mixWith(other: Color, fraction: Float): Color {
    val amount = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * amount,
        green = green + (other.green - green) * amount,
        blue = blue + (other.blue - blue) * amount,
        alpha = alpha + (other.alpha - alpha) * amount,
    )
}
