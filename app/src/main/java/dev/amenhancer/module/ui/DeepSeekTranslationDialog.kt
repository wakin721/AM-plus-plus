package dev.amenhancer.module.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.lyrics.AppleTtmlTranslationEditor
import dev.amenhancer.module.translation.AiTranslationConfigStore
import dev.amenhancer.module.translation.AiTranslationSettings
import dev.amenhancer.module.translation.DeepSeekModel
import dev.amenhancer.module.translation.DeepSeekTranslationClient
import dev.amenhancer.module.translation.DeepSeekTranslationResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Settings-process UI; DeepSeek is never contacted from the Apple Music hook process. */
internal fun SettingsActivity.showDeepSeekTranslationDialog(ttmlInput: EditText) {
    val rawTtml = ttmlInput.text?.toString().orEmpty()
    if (rawTtml.isBlank()) {
        ttmlInput.error = "请先导入或输入 TTML"
        return
    }
    val lines = AppleTtmlTranslationEditor.extractLines(rawTtml)
    if (lines.isEmpty()) {
        Toast.makeText(this, "没有找到可安全对齐的歌词行", Toast.LENGTH_SHORT).show()
        return
    }

    val store = AiTranslationConfigStore(this)
    val saved = store.settings()
    val apiKey = EditText(this).apply {
        hint = "DeepSeek API Key"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(store.apiKey())
        isSingleLine = true
    }
    val modelGroup = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
    }
    DeepSeekModel.entries.forEach { model ->
        modelGroup.addView(RadioButton(this).apply {
            id = model.ordinal + 1
            text = model.displayName
            isChecked = model == saved.model
        })
    }
    val thinking = Switch(this).apply {
        text = "思考模式"
        isChecked = saved.thinkingEnabled
        showText = false
    }
    val targetLanguage = EditText(this).apply {
        hint = "目标语言，例如 zh-Hans"
        setText(saved.targetLanguage)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
    }
    val form = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpForDeepSeek(24), dpForDeepSeek(8), dpForDeepSeek(24), 0)
        addView(TextView(this@showDeepSeekTranslationDialog).apply {
            text = "API Key 仅保存在 AM++ 私有存储并由 Android Keystore 加密，不会写入 Apple Music 的远程配置。"
            textSize = 13f
            setPadding(0, 0, 0, dpForDeepSeek(10))
        })
        addView(apiKey)
        addView(TextView(this@showDeepSeekTranslationDialog).apply {
            text = "模型"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpForDeepSeek(12), 0, 0)
        })
        addView(modelGroup)
        addView(thinking)
        addView(targetLanguage)
        addView(TextView(this@showDeepSeekTranslationDialog).apply {
            text = "将发送 ${lines.size} 行歌词。翻译只修改 TTML 翻译轨，不修改时间轴和逐字高亮。"
            textSize = 13f
            setPadding(0, dpForDeepSeek(10), 0, 0)
        })
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle("DeepSeek AI 翻译")
        .setView(form)
        .setNegativeButton("取消", null)
        .setPositiveButton("翻译", null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val key = apiKey.text?.toString().orEmpty().trim()
            if (key.isEmpty()) {
                apiKey.error = "请输入 API Key"
                return@setOnClickListener
            }
            val selected = DeepSeekModel.entries.getOrNull(modelGroup.checkedRadioButtonId - 1)
                ?: DeepSeekModel.V4_FLASH
            val settings = AiTranslationSettings(
                model = selected,
                thinkingEnabled = thinking.isChecked,
                targetLanguage = targetLanguage.text?.toString().orEmpty().trim(),
            )
            if (!store.saveApiKey(key)) {
                Toast.makeText(this, "API Key 安全存储失败", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.saveSettings(settings)
            dialog.dismiss()
            translateTtmlWithDeepSeek(ttmlInput, rawTtml, key, lines, settings)
        }
    }
    dialog.show()
}

private fun SettingsActivity.translateTtmlWithDeepSeek(
    ttmlInput: EditText,
    originalTtml: String,
    apiKey: String,
    lines: List<dev.amenhancer.module.translation.TranslationLine>,
    settings: AiTranslationSettings,
) {
    val progress = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpForDeepSeek(24), dpForDeepSeek(12), dpForDeepSeek(24), dpForDeepSeek(12))
        addView(ProgressBar(this@translateTtmlWithDeepSeek))
        addView(TextView(this@translateTtmlWithDeepSeek).apply {
            text = "正在翻译 ${lines.size} 行歌词…"
            setPadding(dpForDeepSeek(16), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    val progressDialog = AlertDialog.Builder(this)
        .setTitle("DeepSeek AI 翻译")
        .setView(progress)
        .setCancelable(false)
        .create()
    progressDialog.show()

    DeepSeekTranslationExecutor.executor.execute {
        val result = DeepSeekTranslationClient().translate(apiKey, lines, settings)
        val translatedTtml = if (result is DeepSeekTranslationResult.Success) {
            AppleTtmlTranslationEditor.withTranslations(
                originalTtml,
                result.translations,
                settings.targetLanguage,
            )
        } else {
            null
        }
        runOnUiThread {
            if (progressDialog.isShowing) progressDialog.dismiss()
            if (isFinishing || isDestroyed) return@runOnUiThread
            when (result) {
                is DeepSeekTranslationResult.Success -> {
                    if (translatedTtml == null) {
                        Toast.makeText(this, "翻译成功，但无法安全写入当前 TTML", Toast.LENGTH_SHORT).show()
                    } else {
                        ttmlInput.setText(translatedTtml)
                        Toast.makeText(this, "AI 翻译已写入，请确认后保存", Toast.LENGTH_SHORT).show()
                    }
                }
                is DeepSeekTranslationResult.Failed -> {
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private object DeepSeekTranslationExecutor {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
}

private fun SettingsActivity.dpForDeepSeek(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
