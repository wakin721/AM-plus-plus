package dev.amenhancer.module.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.translation.DeepSeekModel

internal data class LyricsEditorDraft(
    val appleMusicIds: String = "",
    val displayName: String = "",
    val ttml: String = "",
    val source: String = CustomLyricsSources.MANUAL,
)

internal sealed interface ExpressiveSettingsDialog {
    data object Help : ExpressiveSettingsDialog

    data class Progress(
        val operation: Operation,
        val title: String,
        val message: String,
        val cancelLabel: String? = null,
    ) : ExpressiveSettingsDialog {
        enum class Operation {
            LYRICS_UPDATE,
        }
    }

    data class RestoreLyrics(
        val uri: Uri,
    ) : ExpressiveSettingsDialog

    data class DeleteLyrics(
        val group: CustomLyricsUiGroup,
    ) : ExpressiveSettingsDialog

    data class LyricsEditor(
        val draft: LyricsEditorDraft,
        val replacingAppleMusicIds: List<Long> = emptyList(),
        val enabled: Boolean = true,
        val title: String,
        val busyMessage: String? = null,
        val appleMusicIdError: String? = null,
        val ttmlError: String? = null,
    ) : ExpressiveSettingsDialog

    data class DeepSeek(
        val editor: LyricsEditor,
        val apiKey: String,
        val model: DeepSeekModel,
        val thinkingEnabled: Boolean,
        val targetLanguage: String,
        val lineCount: Int,
        val apiKeyError: String? = null,
    ) : ExpressiveSettingsDialog

    data class DeepSeekProgress(
        val editor: LyricsEditor,
        val lineCount: Int,
    ) : ExpressiveSettingsDialog
}

internal class ExpressiveSettingsDialogActions(
    val dismiss: () -> Unit,
    val cancelProgress: () -> Unit,
    val restoreLyrics: (CustomLyricsRestorePolicy) -> Unit,
    val deleteLyrics: () -> Unit,
    val updateLyricsDraft: (LyricsEditorDraft) -> Unit,
    val chooseTtml: () -> Unit,
    val requestCurrentSong: () -> Unit,
    val importAmll: () -> Unit,
    val importLunabeat: () -> Unit,
    val importAmLyrics: () -> Unit,
    val openDeepSeek: () -> Unit,
    val saveLyrics: () -> Unit,
    val updateDeepSeek: (ExpressiveSettingsDialog.DeepSeek) -> Unit,
    val translateDeepSeek: () -> Unit,
)

@Composable
internal fun ExpressiveSettingsDialogHost(
    state: ExpressiveSettingsDialog?,
    actions: ExpressiveSettingsDialogActions,
) {
    when (state) {
        null -> Unit
        ExpressiveSettingsDialog.Help -> HelpDialog(actions)
        is ExpressiveSettingsDialog.Progress -> ProgressDialog(state, actions)
        is ExpressiveSettingsDialog.RestoreLyrics -> RestoreLyricsDialog(actions)
        is ExpressiveSettingsDialog.DeleteLyrics -> DeleteLyricsDialog(state, actions)
        is ExpressiveSettingsDialog.LyricsEditor -> LyricsEditorDialog(state, actions)
        is ExpressiveSettingsDialog.DeepSeek -> DeepSeekDialog(state, actions)
        is ExpressiveSettingsDialog.DeepSeekProgress -> DeepSeekProgressDialog(state)
    }
}

@Composable
private fun HelpDialog(actions: ExpressiveSettingsDialogActions) {
    AlertDialog(
        onDismissRequest = actions.dismiss,
        shape = RoundedCornerShape(32.dp),
        title = { DialogTitle("LSPosed 配置提示") },
        text = {
            Text(
                "在 LSPosed 中启用 AM++，并仅选择 Apple Music（com.apple.android.music）作为作用域。" +
                    "修改功能后，请强制停止并重新打开 Apple Music。",
            )
        },
        confirmButton = { TextButton(onClick = actions.dismiss) { Text("知道了") } },
    )
}

@Composable
private fun ProgressDialog(
    state: ExpressiveSettingsDialog.Progress,
    actions: ExpressiveSettingsDialogActions,
) {
    AlertDialog(
        onDismissRequest = { if (state.cancelLabel != null) actions.cancelProgress() },
        shape = RoundedCornerShape(32.dp),
        title = { DialogTitle(state.title) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            state.cancelLabel?.let { label ->
                TextButton(onClick = actions.cancelProgress) { Text(label) }
            }
        },
    )
}

@Composable
private fun RestoreLyricsDialog(actions: ExpressiveSettingsDialogActions) {
    AlertDialog(
        onDismissRequest = actions.dismiss,
        shape = RoundedCornerShape(32.dp),
        title = { DialogTitle("恢复歌词备份") },
        text = { Text("覆盖会让冲突歌词使用备份版本；不覆盖会保留当前版本。") },
        confirmButton = {
            Button(onClick = { actions.restoreLyrics(CustomLyricsRestorePolicy.OVERWRITE) }) {
                Text("覆盖")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = actions.dismiss) { Text("取消") }
                TextButton(onClick = { actions.restoreLyrics(CustomLyricsRestorePolicy.KEEP_EXISTING) }) {
                    Text("不覆盖")
                }
            }
        },
    )
}

@Composable
private fun DeleteLyricsDialog(
    state: ExpressiveSettingsDialog.DeleteLyrics,
    actions: ExpressiveSettingsDialogActions,
) {
    AlertDialog(
        onDismissRequest = actions.dismiss,
        shape = RoundedCornerShape(32.dp),
        title = { DialogTitle("删除自定义歌词") },
        text = {
            Text("删除“${state.group.primary.displayName}”及其 ${state.group.entries.size} 个 Apple Music ID 的 TTML 映射？")
        },
        confirmButton = { Button(onClick = actions.deleteLyrics) { Text("删除") } },
        dismissButton = { TextButton(onClick = actions.dismiss) { Text("取消") } },
    )
}

@Composable
private fun LyricsEditorDialog(
    state: ExpressiveSettingsDialog.LyricsEditor,
    actions: ExpressiveSettingsDialogActions,
) {
    val enabled = state.busyMessage == null
    Dialog(
        onDismissRequest = { if (enabled) actions.dismiss() },
        properties = DialogProperties(
            dismissOnBackPress = enabled,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
                .imePadding(),
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                DialogTitle(state.title)
                if (state.busyMessage != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text(
                        modifier = Modifier.padding(top = 8.dp),
                        text = state.busyMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.draft.appleMusicIds,
                        enabled = enabled,
                        onValueChange = {
                            actions.updateLyricsDraft(state.draft.copy(appleMusicIds = it))
                        },
                        label = { Text("Apple Music ID，可用逗号分隔") },
                        isError = state.appleMusicIdError != null,
                        supportingText = state.appleMusicIdError?.let { error -> ({ Text(error) }) },
                        shape = RoundedCornerShape(20.dp),
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.draft.displayName,
                        enabled = enabled,
                        onValueChange = {
                            actions.updateLyricsDraft(state.draft.copy(displayName = it))
                        },
                        label = { Text("显示名称（可选）") },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                    )
                    Text(
                        text = "当前来源：${lyricsSourceName(state.draft.source)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            onClick = actions.importAmll,
                        ) { Text("AMLL") }
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            onClick = actions.importLunabeat,
                        ) { Text("Lunabeat") }
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            onClick = actions.importAmLyrics,
                        ) { Text("GitHub") }
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.draft.ttml,
                        enabled = enabled,
                        onValueChange = { actions.updateLyricsDraft(state.draft.copy(ttml = it)) },
                        label = { Text("TTML 内容") },
                        minLines = 8,
                        maxLines = 14,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        isError = state.ttmlError != null,
                        supportingText = state.ttmlError?.let { error -> ({ Text(error) }) },
                        shape = RoundedCornerShape(20.dp),
                    )
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        onClick = actions.openDeepSeek,
                    ) { Text("DeepSeek AI 翻译") }
                }
                HorizontalDivider(Modifier.padding(top = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = enabled, onClick = actions.chooseTtml) { Text("导入 TTML") }
                    TextButton(enabled = enabled, onClick = actions.requestCurrentSong) { Text("获取 ID") }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = enabled, onClick = actions.dismiss) { Text("取消") }
                    Spacer(Modifier.width(4.dp))
                    Button(enabled = enabled, onClick = actions.saveLyrics) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun DeepSeekDialog(
    state: ExpressiveSettingsDialog.DeepSeek,
    actions: ExpressiveSettingsDialogActions,
) {
    AlertDialog(
        onDismissRequest = actions.dismiss,
        shape = RoundedCornerShape(32.dp),
        title = { DialogTitle("DeepSeek AI 翻译") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "API Key 仅保存在 AM++ 私有存储，并由 Android Keystore 加密。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.apiKey,
                    onValueChange = { actions.updateDeepSeek(state.copy(apiKey = it, apiKeyError = null)) },
                    label = { Text("DeepSeek API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = state.apiKeyError != null,
                    supportingText = state.apiKeyError?.let { error -> ({ Text(error) }) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                )
                Text("模型", fontWeight = FontWeight.SemiBold)
                DeepSeekModel.entries.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actions.updateDeepSeek(state.copy(model = model)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.model == model,
                            onClick = { actions.updateDeepSeek(state.copy(model = model)) },
                        )
                        Text(model.displayName)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("思考模式", modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.thinkingEnabled,
                        onCheckedChange = {
                            actions.updateDeepSeek(state.copy(thinkingEnabled = it))
                        },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.targetLanguage,
                    onValueChange = { actions.updateDeepSeek(state.copy(targetLanguage = it)) },
                    label = { Text("目标语言，例如 zh-Hans") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                )
                Text(
                    "将发送 ${state.lineCount} 行歌词；只修改翻译轨，不修改时间轴和逐字高亮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = actions.translateDeepSeek) { Text("翻译") } },
        dismissButton = { TextButton(onClick = actions.dismiss) { Text("取消") } },
    )
}

@Composable
private fun DeepSeekProgressDialog(state: ExpressiveSettingsDialog.DeepSeekProgress) {
    AlertDialog(
        onDismissRequest = {},
        shape = RoundedCornerShape(32.dp),
        title = { DialogTitle("DeepSeek AI 翻译") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                Text("正在翻译 ${state.lineCount} 行歌词…")
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun DialogTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun lyricsSourceName(source: String): String = when (source) {
    CustomLyricsSources.AMLL -> "AMLL"
    CustomLyricsSources.LUNABEAT -> "Lunabeat"
    CustomLyricsSources.AM_LYRICS -> "AM-Lyrics 仓库"
    else -> "手动 TTML"
}
