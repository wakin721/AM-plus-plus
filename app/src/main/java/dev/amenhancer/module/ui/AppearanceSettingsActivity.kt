package dev.amenhancer.module.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.R
import dev.amenhancer.module.ui.theme.AmppExpressiveTheme
import dev.amenhancer.module.ui.theme.AppAppearanceSettings
import dev.amenhancer.module.ui.theme.AppThemeMode
import dev.amenhancer.module.ui.theme.AppThemePalette
import dev.amenhancer.module.ui.theme.AppUiStyle
import dev.amenhancer.module.ui.theme.AppearancePreferences

class AppearanceSettingsActivity : ComponentActivity() {
    private lateinit var appearancePreferences: AppearancePreferences
    private var appearance by mutableStateOf(AppAppearanceSettings())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppearancePreferences.themedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearancePreferences = AppearancePreferences(this)
        appearance = appearancePreferences.settings()
        configureSystemBars(appearance)
        setContent {
            AmppExpressiveTheme(
                appearance = appearance.copy(style = AppUiStyle.MATERIAL3),
            ) {
                AppearanceSettingsScreen(
                    appearance = appearance,
                    dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    navigateBack = ::finish,
                    updateAppearance = ::saveAppearance,
                )
            }
        }
    }

    private fun saveAppearance(updated: AppAppearanceSettings) {
        val modeChanged = updated.mode != appearance.mode
        appearancePreferences.save(updated)
        appearance = updated
        if (modeChanged) recreate()
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars(settings: AppAppearanceSettings) {
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val dark = when (settings.mode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SYSTEM -> resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        var flags = window.decorView.systemUiVisibility
        flags = if (dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }
}

@Composable
private fun AppearanceSettingsScreen(
    appearance: AppAppearanceSettings,
    dynamicColorSupported: Boolean,
    navigateBack: () -> Unit,
    updateAppearance: (AppAppearanceSettings) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
        ) {
            IconButton(
                modifier = Modifier.padding(top = 4.dp),
                onClick = navigateBack,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "返回",
                )
            }

            Text(
                modifier = Modifier.padding(top = 20.dp, bottom = 40.dp),
                text = "主题与色彩",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Normal,
            )

            SectionTitle("界面风格")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThemeStyleOption(
                    modifier = Modifier.weight(1f),
                    title = "原有主题",
                    summary = "经典 AM++",
                    selected = appearance.style == AppUiStyle.ORIGINAL,
                    onClick = {
                        updateAppearance(appearance.copy(style = AppUiStyle.ORIGINAL))
                    },
                )
                ThemeStyleOption(
                    modifier = Modifier.weight(1f),
                    title = "MD3",
                    summary = "Material 3 Expressive",
                    selected = appearance.style == AppUiStyle.MATERIAL3,
                    onClick = {
                        updateAppearance(appearance.copy(style = AppUiStyle.MATERIAL3))
                    },
                )
            }

            Spacer(Modifier.height(36.dp))
            SectionTitle("主题")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(
                    AppThemeMode.LIGHT,
                    AppThemeMode.DARK,
                    AppThemeMode.SYSTEM,
                ).forEach { mode ->
                    ThemeModePreview(
                        modifier = Modifier.weight(1f),
                        mode = mode,
                        selected = appearance.mode == mode,
                        onClick = { updateAppearance(appearance.copy(mode = mode)) },
                    )
                }
            }

            if (appearance.style == AppUiStyle.MATERIAL3) {
                Spacer(Modifier.height(34.dp))
                DynamicColorRow(
                    checked = appearance.dynamicColor && dynamicColorSupported,
                    supported = dynamicColorSupported,
                    onChanged = { enabled ->
                        updateAppearance(appearance.copy(dynamicColor = enabled))
                    },
                )

                Spacer(Modifier.height(36.dp))
                val paletteEnabled = !appearance.dynamicColor || !dynamicColorSupported
                SectionTitle("调色板")
                if (!paletteEnabled) {
                    Text(
                        modifier = Modifier.padding(top = 6.dp),
                        text = "动态颜色开启时不可选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (paletteEnabled) 1f else 0.38f)
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppThemePalette.entries.chunked(PALETTE_COLUMNS).forEach { paletteRow ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            paletteRow.forEach { palette ->
                                PaletteCard(
                                    modifier = Modifier.weight(1f),
                                    palette = palette,
                                    selected = appearance.palette == palette,
                                    enabled = paletteEnabled,
                                    onClick = {
                                        updateAppearance(appearance.copy(palette = palette))
                                    },
                                )
                            }
                            repeat(PALETTE_COLUMNS - paletteRow.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ThemeStyleOption(
    modifier: Modifier,
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = modifier
            .height(86.dp)
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 3.dp),
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeModePreview(
    modifier: Modifier,
    mode: AppThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val selectedColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val accentContainer = MaterialTheme.colorScheme.primaryContainer
    val borderWidth = if (selected) 3.dp else 1.dp
    val borderColor = if (selected) selectedColor else outlineColor

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(144.dp)
                .border(borderWidth, borderColor, shape)
                .padding(6.dp)
                .clip(RoundedCornerShape(15.dp)),
        ) {
            fun drawFace(dark: Boolean) {
                val background = if (dark) Color(0xFF171112) else Color(0xFFFFF8F7)
                val container = if (dark) Color(0xFF271D1E) else Color(0xFFF6ECEB)
                val muted = if (dark) Color(0xFF6B4B4F) else accent.copy(alpha = 0.42f)
                val bright = if (dark) accentContainer else accent
                val radius = 8.dp.toPx()

                drawRect(background)
                drawRoundRect(
                    color = container,
                    topLeft = Offset(size.width * 0.09f, size.height * 0.08f),
                    size = Size(size.width * 0.68f, size.height * 0.26f),
                    cornerRadius = CornerRadius(radius),
                )
                drawRoundRect(
                    color = muted,
                    topLeft = Offset(size.width * 0.09f, size.height * 0.42f),
                    size = Size(size.width * 0.68f, size.height * 0.055f),
                    cornerRadius = CornerRadius(radius),
                )
                drawRoundRect(
                    color = muted.copy(alpha = 0.7f),
                    topLeft = Offset(size.width * 0.09f, size.height * 0.52f),
                    size = Size(size.width * 0.48f, size.height * 0.055f),
                    cornerRadius = CornerRadius(radius),
                )
                drawRoundRect(
                    color = bright,
                    topLeft = Offset(size.width * 0.09f, size.height * 0.63f),
                    size = Size(size.width * 0.76f, size.height * 0.055f),
                    cornerRadius = CornerRadius(radius),
                )
                drawRect(
                    color = container,
                    topLeft = Offset(0f, size.height * 0.78f),
                    size = Size(size.width, size.height * 0.22f),
                )
                drawCircle(
                    color = bright,
                    radius = size.width * 0.11f,
                    center = Offset(size.width * 0.19f, size.height * 0.89f),
                )
                drawRoundRect(
                    color = muted,
                    topLeft = Offset(size.width * 0.38f, size.height * 0.83f),
                    size = Size(size.width * 0.5f, size.height * 0.12f),
                    cornerRadius = CornerRadius(size.height * 0.06f),
                )
            }

            when (mode) {
                AppThemeMode.LIGHT -> drawFace(dark = false)
                AppThemeMode.DARK -> drawFace(dark = true)
                AppThemeMode.SYSTEM -> {
                    drawFace(dark = false)
                    val darkHalf = Path().apply {
                        moveTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    clipPath(darkHalf) { drawFace(dark = true) }
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(
                text = mode.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun DynamicColorRow(
    checked: Boolean,
    supported: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = supported) { onChanged(!checked) }
            .alpha(if (supported) 1f else 0.5f)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "动态色彩",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
            )
            Text(
                modifier = Modifier.padding(top = 5.dp),
                text = if (supported) {
                    "使用系统强调色"
                } else {
                    "需要 Android 12 或更高版本"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = supported,
            onCheckedChange = onChanged,
        )
    }
}

@Composable
private fun PaletteCard(
    modifier: Modifier,
    palette: AppThemePalette,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val outline = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .border(3.dp, outline, shape)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {}
        Canvas(Modifier.size(50.dp).clip(CircleShape)) {
            drawArc(Color(palette.primaryContainer.toInt()), -90f, 90f, true)
            drawArc(Color(palette.secondaryContainer.toInt()), 0f, 90f, true)
            drawArc(Color(palette.primary.toInt()), 90f, 90f, true)
            drawArc(Color(palette.tertiaryContainer.toInt()), 180f, 90f, true)
        }
        if (selected) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "✓",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private const val PALETTE_COLUMNS = 4
