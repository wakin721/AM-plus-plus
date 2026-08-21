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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

@OptIn(ExperimentalMaterial3Api::class)
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "外观与主题",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppearanceCard("界面主题") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemeStyleCard(
                        modifier = Modifier.weight(1f),
                        title = "原有主题",
                        summary = "保留经典 AM++ 设置界面",
                        selected = appearance.style == AppUiStyle.ORIGINAL,
                        onClick = {
                            updateAppearance(appearance.copy(style = AppUiStyle.ORIGINAL))
                        },
                    )
                    ThemeStyleCard(
                        modifier = Modifier.weight(1f),
                        title = "MD3",
                        summary = "Material 3 Expressive",
                        selected = appearance.style == AppUiStyle.MATERIAL3,
                        onClick = {
                            updateAppearance(appearance.copy(style = AppUiStyle.MATERIAL3))
                        },
                    )
                }
            }

            AppearanceCard("显示模式") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = appearance.mode == mode,
                            onClick = { updateAppearance(appearance.copy(mode = mode)) },
                            label = {
                                Text(
                                    modifier = Modifier.fillMaxWidth(),
                                    text = mode.displayName,
                                    textAlign = TextAlign.Center,
                                )
                            },
                        )
                    }
                }
            }

            if (appearance.style == AppUiStyle.MATERIAL3) {
                AppearanceCard("MD3 配色") {
                    DynamicColorRow(
                        checked = appearance.dynamicColor && dynamicColorSupported,
                        supported = dynamicColorSupported,
                        onChanged = { enabled ->
                            updateAppearance(appearance.copy(dynamicColor = enabled))
                        },
                    )
                    val paletteEnabled = !appearance.dynamicColor || !dynamicColorSupported
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (paletteEnabled) 1f else 0.38f)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text(
                            modifier = Modifier.padding(horizontal = 6.dp, bottom = 10.dp),
                            text = if (paletteEnabled) "选择预设颜色" else "关闭动态颜色后可选择调色板",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyVerticalGrid(
                            modifier = Modifier.fillMaxWidth().height(276.dp),
                            columns = GridCells.Fixed(5),
                            userScrollEnabled = false,
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(AppThemePalette.entries, key = AppThemePalette::name) { palette ->
                                PaletteCard(
                                    palette = palette,
                                    selected = appearance.palette == palette,
                                    enabled = paletteEnabled,
                                    onClick = {
                                        updateAppearance(appearance.copy(palette = palette))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Text(
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp),
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun ThemeStyleCard(
    modifier: Modifier,
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(
        modifier = modifier
            .border(2.dp, borderColor, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                modifier = Modifier.padding(top = 5.dp),
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (selected) "已选择" else "选择",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
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
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("动态颜色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = if (supported) {
                    "使用系统壁纸生成的颜色；开启后禁用调色板"
                } else {
                    "需要 Android 12 或更高版本"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = supported, onCheckedChange = onChanged)
    }
}

@Composable
private fun PaletteCard(
    palette: AppThemePalette,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .border(3.dp, outline, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {}
        Canvas(Modifier.size(56.dp).clip(CircleShape)) {
            drawArc(Color(palette.primaryContainer.toInt()), -90f, 90f, true)
            drawArc(Color(palette.secondaryContainer.toInt()), 0f, 90f, true)
            drawArc(Color(palette.primary.toInt()), 90f, 90f, true)
            drawArc(Color(palette.tertiaryContainer.toInt()), 180f, 90f, true)
        }
        if (selected) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
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
