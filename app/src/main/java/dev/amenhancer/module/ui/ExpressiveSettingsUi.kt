package dev.amenhancer.module.ui

import android.media.AudioFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.R
import dev.amenhancer.module.UsbBitPerfectStatusDetails
import dev.amenhancer.module.UsbBitPerfectStatusProtocol
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.model.ModuleSettings

internal class AmppSettingsActions(
    val saveSettings: (ModuleSettings) -> Unit,
    val showTitleCorrectionMode: () -> Unit,
    val openCustomLyrics: () -> Unit,
    val chooseFont: () -> Unit,
    val restoreFont: () -> Unit,
    val openUsbAudio: () -> Unit,
    val openAppearance: () -> Unit,
    val setLauncherHidden: (Boolean) -> Unit,
    val showHelp: () -> Unit,
    val backToMain: () -> Unit,
    val setCustomLyricsQuery: (String) -> Unit,
    val addCustomLyrics: () -> Unit,
    val updateCustomLyrics: () -> Unit,
    val backupCustomLyrics: () -> Unit,
    val restoreCustomLyrics: () -> Unit,
    val setCustomLyricsEnabled: (List<Long>, Boolean) -> Unit,
    val editCustomLyrics: (CustomLyricsUiGroup) -> Unit,
    val deleteCustomLyrics: (CustomLyricsUiGroup) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AmppSettingsScreen(
    settings: ModuleSettings,
    snapshot: XposedServiceSnapshot,
    launcherHidden: Boolean,
    customLyricsPage: Boolean,
    customLyricsQuery: String,
    actions: AmppSettingsActions,
    dialogState: ExpressiveSettingsDialog?,
    dialogActions: ExpressiveSettingsDialogActions,
) {
    if (customLyricsPage) BackHandler(onBack = actions.backToMain)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (customLyricsPage) "自定义歌词" else "AM++",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    if (customLyricsPage) {
                        IconButton(onClick = actions.backToMain) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            )
        },
    ) { innerPadding ->
        if (customLyricsPage) {
            CustomLyricsPage(
                modifier = Modifier.padding(innerPadding),
                settings = settings,
                snapshot = snapshot,
                query = customLyricsQuery,
                actions = actions,
            )
        } else {
            MainSettingsPage(
                modifier = Modifier.padding(innerPadding),
                settings = settings,
                snapshot = snapshot,
                launcherHidden = launcherHidden,
                actions = actions,
            )
        }
    }
    ExpressiveSettingsDialogHost(dialogState, dialogActions)
}

@Composable
private fun MainSettingsPage(
    modifier: Modifier,
    settings: ModuleSettings,
    snapshot: XposedServiceSnapshot,
    launcherHidden: Boolean,
    actions: AmppSettingsActions,
) {
    val writable = snapshot.isRemoteAvailable
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ConnectionHero(snapshot)
        }
        item {
            SettingsGroup(title = "功能") {
                ExpressiveSwitchRow(
                    title = "平板双栏播放器",
                    summary = "仅在 Apple Music 判定为平板且横屏时启用",
                    checked = settings.dualPaneEnabled,
                    enabled = writable,
                ) { actions.saveSettings(settings.copy(dualPaneEnabled = it)) }
                GroupDivider()
                ExpressiveSwitchRow(
                    title = "平板底栏补偿",
                    summary = "如果底栏显示异常开启该选项",
                    checked = settings.navigationCompensationEnabled,
                    enabled = writable,
                ) { actions.saveSettings(settings.copy(navigationCompensationEnabled = it)) }
                GroupDivider()
                ExpressiveSwitchRow(
                    title = "平板禁用动态视频",
                    summary = "平板横屏时禁用 Editorial Video",
                    checked = settings.disableEditorialVideoOnTablet,
                    enabled = writable,
                ) { actions.saveSettings(settings.copy(disableEditorialVideoOnTablet = it)) }
                GroupDivider()
                ExpressiveSwitchRow(
                    title = "手机液态玻璃底栏",
                    summary = "仅手机启用 · 修改后需重启 Apple Music",
                    checked = settings.phoneLiquidGlassEnabled,
                    enabled = writable,
                    badge = "WIP",
                ) { actions.saveSettings(settings.copy(phoneLiquidGlassEnabled = it)) }
                GroupDivider()
                ExpressiveSwitchRow(
                    title = "双向歌词模糊",
                    summary = "Android 12+ · 手动滚动停止 1 秒后恢复",
                    checked = settings.futureBlurEnabled,
                    enabled = writable,
                ) { actions.saveSettings(settings.copy(futureBlurEnabled = it)) }
                GroupDivider()
                BlurRadiusRow(settings, writable, actions)
                GroupDivider()
                ExpressiveSwitchRow(
                    title = "歌曲名显示修正",
                    summary = "按目标语言回填 Catalog 标题 · 修改后需重启",
                    checked = settings.titleCorrectionEnabled,
                    enabled = writable,
                ) { actions.saveSettings(settings.copy(titleCorrectionEnabled = it)) }
                GroupDivider()
                ExpressiveActionRow(
                    title = "歌曲名修正模式",
                    summary = settings.titleCorrectionMode.displayName,
                    enabled = writable,
                    onClick = actions.showTitleCorrectionMode,
                )
                GroupDivider()
                ExpressiveActionRow(
                    title = "自定义歌词",
                    summary = if (settings.customLyricsManifest.entries.isEmpty()) {
                        "添加和管理 Apple Music ID 歌词映射"
                    } else {
                        "已配置 ${settings.customLyricsManifest.entries.size} 首歌词"
                    },
                    onClick = actions.openCustomLyrics,
                )
            }
        }
        item {
            FontSettingsCard(settings, snapshot, actions)
        }
        item {
            SettingsGroup(title = "音频") {
                ExpressiveActionRow(
                    title = "USB 音频输出",
                    summary = "Bit-Perfect、USB Direct、AAudio 独占与实时链路",
                    onClick = actions.openUsbAudio,
                )
            }
        }
        item {
            SettingsGroup(title = "应用") {
                ExpressiveSwitchRow(
                    title = "隐藏启动器图标",
                    summary = "隐藏后可从 LSPosed 模块详情重新打开设置",
                    checked = launcherHidden,
                    enabled = true,
                    onChanged = actions.setLauncherHidden,
                )
                GroupDivider()
                ExpressiveActionRow(
                    title = "外观与主题",
                    summary = "原有主题、MD3 调色板、动态颜色与显示模式",
                    onClick = actions.openAppearance,
                )
            }
        }
        item {
            SettingsGroup(title = "帮助") {
                ExpressiveActionRow(
                    title = "LSPosed 配置提示",
                    summary = "作用域、重启与故障排查说明",
                    onClick = actions.showHelp,
                )
            }
        }
    }
}

@Composable
private fun ConnectionHero(snapshot: XposedServiceSnapshot) {
    val connected = snapshot.isRemoteAvailable
    val container = if (connected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val foreground = if (connected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        contentColor = foreground,
        shape = RoundedCornerShape(32.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = CircleShape, color = foreground) {
                Icon(
                    modifier = Modifier.padding(10.dp).size(24.dp),
                    painter = painterResource(R.drawable.ic_status_check),
                    tint = container,
                    contentDescription = null,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (connected) "模块服务已连接" else "配置暂时只读",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    modifier = Modifier.padding(top = 3.dp),
                    text = snapshot.status,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Text(
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 6.dp),
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            content()
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ExpressiveSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    badge: String? = null,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChanged(!checked) }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (badge != null) {
                    AssistChip(
                        modifier = Modifier.padding(start = 8.dp),
                        onClick = {},
                        enabled = false,
                        label = { Text(badge) },
                    )
                }
            }
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChanged)
    }
}

@Composable
private fun ExpressiveActionRow(
    title: String,
    summary: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BlurRadiusRow(
    settings: ModuleSettings,
    enabled: Boolean,
    actions: AmppSettingsActions,
) {
    var value by remember(settings.lyricBlurRadiusOffsetPx) {
        mutableFloatStateOf(settings.lyricBlurRadiusOffsetPx.toFloat())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = "歌词模糊半径偏移",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (value > 0f) "+${value.toInt()}px" else "${value.toInt()}px",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = "统一调整非高亮歌词 · 修改后需重启 Apple Music",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            enabled = enabled,
            valueRange = ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX.toFloat()..
                ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX.toFloat(),
            steps = ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX -
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX - 1,
            onValueChange = { value = it },
            onValueChangeFinished = {
                actions.saveSettings(settings.copy(lyricBlurRadiusOffsetPx = value.toInt()))
            },
        )
    }
}

@Composable
private fun FontSettingsCard(
    settings: ModuleSettings,
    snapshot: XposedServiceSnapshot,
    actions: AmppSettingsActions,
) {
    val manifest = settings.fontManifest
    val writable = snapshot.isRemoteFileAvailable
    SettingsGroup(title = "歌词字体") {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(
                text = if (manifest.enabled) manifest.displayName else "原字体",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = when {
                    !writable -> "需要 libxposed API 102 remote file 服务"
                    manifest.enabled -> "仅覆盖播放器歌词 · 重启 Apple Music 后生效"
                    else -> "导入 TTF/OTF · 重启 Apple Music 后生效"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    enabled = writable,
                    onClick = actions.chooseFont,
                ) { Text("选择字体") }
                TextButton(
                    modifier = Modifier.weight(1f),
                    enabled = writable && manifest.enabled,
                    onClick = actions.restoreFont,
                ) { Text("恢复原字体") }
            }
        }
    }
}

@Composable
private fun CustomLyricsPage(
    modifier: Modifier,
    settings: ModuleSettings,
    snapshot: XposedServiceSnapshot,
    query: String,
    actions: AmppSettingsActions,
) {
    val writable = snapshot.isRemoteAvailable
    val fileWritable = snapshot.isRemoteFileAvailable
    val groups = remember(settings.customLyricsManifest.entries, query) {
        val needle = query.trim().lowercase()
        groupCustomLyricsEntries(settings.customLyricsManifest.entries).filter { group ->
            needle.isEmpty() || group.entries.any { entry ->
                entry.displayName.lowercase().contains(needle) ||
                    entry.appleMusicId.toString().contains(needle)
            }
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsGroup(title = "替换开关") {
                ExpressiveSwitchRow(
                    title = "自定义歌词替换",
                    summary = "按 Apple Music ID 注入 · 修改后重启生效",
                    checked = settings.customLyricsEnabled,
                    enabled = writable,
                ) { actions.saveSettings(settings.copy(customLyricsEnabled = it)) }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "歌词资料库",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = when {
                            !fileWritable -> "需要 libxposed API 102 remote file 服务"
                            settings.customLyricsManifest.entries.isEmpty() ->
                                "按 Apple Music ID 手动添加 TTML；不会在播放时联网识歌"
                            else -> "已配置 ${settings.customLyricsManifest.entries.size} 首；修改后重启生效"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = fileWritable,
                            onClick = actions.addCustomLyrics,
                        ) { Text("添加歌词") }
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            enabled = fileWritable,
                            onClick = actions.updateCustomLyrics,
                        ) { Text("更新全部") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            enabled = fileWritable,
                            onClick = actions.backupCustomLyrics,
                        ) { Text("备份歌词") }
                        TextButton(
                            modifier = Modifier.weight(1f),
                            enabled = fileWritable,
                            onClick = actions.restoreCustomLyrics,
                        ) { Text("恢复备份") }
                    }
                }
            }
        }
        if (settings.customLyricsManifest.entries.isNotEmpty()) {
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    enabled = fileWritable,
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    label = { Text("搜索名称或 Apple Music ID") },
                    onValueChange = actions.setCustomLyricsQuery,
                )
            }
            if (groups.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("没有匹配的歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(groups, key = { it.appleMusicIds.joinToString(",") }) { group ->
                CustomLyricsGroupCard(group, fileWritable, actions)
            }
        }
    }
}

@Composable
private fun CustomLyricsGroupCard(
    group: CustomLyricsUiGroup,
    enabled: Boolean,
    actions: AmppSettingsActions,
) {
    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = group.primary.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = "主 ID：${group.primary.appleMusicId} · 共 ${group.entries.size} 个 ID",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = group.allEnabled,
                    enabled = enabled,
                    onCheckedChange = { actions.setCustomLyricsEnabled(group.appleMusicIds, it) },
                )
            }
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = { actions.editCustomLyrics(group) },
                ) { Text("编辑") }
                TextButton(
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    onClick = { actions.deleteCustomLyrics(group) },
                ) { Text("删除") }
            }
        }
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

internal class UsbAudioSettingsActions(
    val navigateBack: () -> Unit,
    val setEnabled: (Boolean) -> Unit,
    val setDirectEnabled: (Boolean) -> Unit,
    val setExclusiveEnabled: (Boolean) -> Unit,
    val refresh: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsbAudioSettingsScreen(
    settings: ModuleSettings,
    snapshot: XposedServiceSnapshot,
    status: UsbBitPerfectStatusDetails?,
    checking: Boolean,
    actions: UsbAudioSettingsActions,
) {
    BackHandler(onBack = actions.navigateBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("USB 音频输出", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = actions.navigateBack) {
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsGroup(title = "输出模式") {
                    ExpressiveSwitchRow(
                        title = "启用 USB 音频增强",
                        summary = "Android 14+ · USB DAC · 修改后需重启 Apple Music",
                        checked = settings.usbBitPerfectEnabled,
                        enabled = snapshot.isRemoteAvailable,
                        onChanged = actions.setEnabled,
                    )
                    GroupDivider()
                    ExpressiveSwitchRow(
                        title = "实验性 USB 直通独占",
                        summary = "USB Host 授权并直接写入 UAC endpoint",
                        checked = settings.usbDirectUacEnabled,
                        enabled = snapshot.isRemoteAvailable && settings.usbBitPerfectEnabled,
                        onChanged = actions.setDirectEnabled,
                    )
                    GroupDivider()
                    ExpressiveSwitchRow(
                        title = "实验性 AAudio 独占回退",
                        summary = "USB Direct 未建立时请求 AAudio EXCLUSIVE",
                        checked = settings.usbExclusiveAaudioEnabled,
                        enabled = snapshot.isRemoteAvailable && settings.usbBitPerfectEnabled,
                        onChanged = actions.setExclusiveEnabled,
                    )
                }
            }
            item {
                UsbAudioPathCard(settings.usbBitPerfectEnabled, status, checking, actions.refresh)
            }
        }
    }
}

@Composable
private fun UsbAudioPathCard(
    enabled: Boolean,
    status: UsbBitPerfectStatusDetails?,
    checking: Boolean,
    onRefresh: () -> Unit,
) {
    val presentation = usbStatusPresentation(enabled, status, checking)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("音频链路", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = "优先 USB Direct UAC；失败后回退 AAudio/Android mixer。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier.padding(top = 14.dp, bottom = 12.dp),
                text = presentation.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            AudioPathNode("Apple Music AudioTrack", presentation.appleMusic)
            PathArrow()
            AudioPathNode("输出引擎", presentation.engine)
            PathArrow()
            AudioPathNode("USB DAC", presentation.usb)
            Text(
                modifier = Modifier.padding(top = 14.dp),
                text = presentation.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                modifier = Modifier.align(Alignment.End).padding(top = 10.dp),
                enabled = !checking,
                onClick = onRefresh,
            ) { Text(if (checking) "正在检查…" else "刷新状态") }
        }
    }
}

@Composable
private fun AudioPathNode(title: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = value,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun PathArrow() {
    Text(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        text = "↓",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private data class UsbStatusPresentation(
    val title: String,
    val appleMusic: String,
    val engine: String,
    val usb: String,
    val message: String,
)

private fun usbStatusPresentation(
    enabled: Boolean,
    status: UsbBitPerfectStatusDetails?,
    checking: Boolean,
): UsbStatusPresentation {
    if (checking) return UsbStatusPresentation(
        title = "正在检查…",
        appleMusic = "正在读取播放状态",
        engine = "正在读取输出引擎",
        usb = "正在读取 USB 设备",
        message = "正在向 Apple Music 进程读取实时音频链路。",
    )
    if (status == null) return UsbStatusPresentation(
        title = if (enabled) "无法查询实时状态" else "已关闭",
        appleMusic = "等待 Apple Music",
        engine = if (enabled) "等待重启并开始播放" else "功能已关闭",
        usb = "未取得实时设备状态",
        message = if (enabled) "启动或重启 Apple Music 并播放后再次刷新。" else "开启后重启 Apple Music。",
    )
    val stateTitle = when (status.state) {
        UsbBitPerfectStatusProtocol.STATE_DIRECT_ARMED -> "USB 直通待命"
        UsbBitPerfectStatusProtocol.STATE_DIRECT_PERMISSION_REQUIRED -> "需要 USB 授权"
        UsbBitPerfectStatusProtocol.STATE_DIRECT_ACQUIRING -> "正在取得 USB 独占"
        UsbBitPerfectStatusProtocol.STATE_DIRECT_CONFIGURED -> "USB 直通已建立"
        UsbBitPerfectStatusProtocol.STATE_DIRECT_ACTIVE -> "USB 直通独占已激活"
        UsbBitPerfectStatusProtocol.STATE_DIRECT_FALLBACK -> "USB 直通失败，已回退"
        UsbBitPerfectStatusProtocol.STATE_DIRECT_UNSUPPORTED_DEVICE -> "当前 DAC 暂不支持直通"
        UsbBitPerfectStatusProtocol.STATE_ACTIVE -> "Bit-Perfect 已激活"
        UsbBitPerfectStatusProtocol.STATE_CONFIGURED -> "已配置，等待路由"
        UsbBitPerfectStatusProtocol.STATE_WAITING_PLAYBACK -> "等待播放"
        UsbBitPerfectStatusProtocol.STATE_WAITING_ROUTE -> "等待 USB 路由"
        UsbBitPerfectStatusProtocol.STATE_NO_USB_DEVICE -> "未连接 USB DAC"
        UsbBitPerfectStatusProtocol.STATE_NON_USB_ROUTE -> "当前未走 USB"
        UsbBitPerfectStatusProtocol.STATE_FORMAT_UNSUPPORTED -> "格式不匹配"
        UsbBitPerfectStatusProtocol.STATE_REQUEST_FAILED -> "请求失败"
        UsbBitPerfectStatusProtocol.STATE_UNSUPPORTED_ANDROID -> "系统不支持"
        UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ARMED -> "AAudio 独占待命"
        UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_READY -> "已检测可接管 PCM"
        UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_CONFIGURED -> "AAudio 独占流已建立"
        UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ACTIVE -> "AAudio 独占已激活"
        UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_FALLBACK -> "AAudio 独占失败，已回退"
        UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_UNSUPPORTED_PATH -> "无法安全接管"
        else -> "未激活"
    }
    val track = formatAudio(status.trackSampleRate, status.trackEncoding, status.trackChannels)
        ?: "等待媒体 AudioTrack"
    val mixer = formatAudio(status.mixerSampleRate, status.mixerEncoding, status.mixerChannels)
    val engine = when {
        status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_ACTIVE ->
            "${mixer ?: track} · USB DIRECT · usbfs ISO PCM"
        status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_CONFIGURED ->
            "${mixer ?: track} · USB DIRECT · interface 已 claim"
        status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_PERMISSION_REQUIRED -> "等待 USB Host 权限"
        status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_FALLBACK -> "USB Direct 未建立 · 回退 AAudio"
        status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ACTIVE ->
            "${mixer ?: track} · AAUDIO EXCLUSIVE · PCM 已接管"
        status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_CONFIGURED ->
            "${mixer ?: track} · AAUDIO EXCLUSIVE · 等待 PCM"
        status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_READY -> "AAudio EXCLUSIVE · PCM 可接管"
        status.state == UsbBitPerfectStatusProtocol.STATE_ACTIVE -> "${mixer ?: track} · BIT_PERFECT 已核验"
        status.state == UsbBitPerfectStatusProtocol.STATE_CONFIGURED -> "${mixer ?: track} · BIT_PERFECT 已配置"
        mixer != null -> "$mixer · $stateTitle"
        else -> stateTitle
    }
    return UsbStatusPresentation(
        title = stateTitle,
        appleMusic = track,
        engine = engine,
        usb = status.deviceName ?: "未报告 USB 设备名称",
        message = status.message?.takeIf(String::isNotBlank) ?: "当前没有更多运行时说明。",
    )
}

private fun formatAudio(sampleRate: Int, encoding: Int, channels: Int): String? {
    if (sampleRate <= 0 && encoding <= 0 && channels <= 0) return null
    val encodingName = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM Float"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        else -> "encoding $encoding"
    }
    return "$sampleRate Hz / $encodingName / ${channels}ch"
}
