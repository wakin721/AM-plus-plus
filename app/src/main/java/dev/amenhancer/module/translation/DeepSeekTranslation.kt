package dev.amenhancer.module.translation

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class DeepSeekModel(
    val apiName: String,
    val displayName: String,
) {
    V4_FLASH("deepseek-v4-flash", "DeepSeek V4 Flash"),
    V4_PRO("deepseek-v4-pro", "DeepSeek V4 Pro"),
    ;

    companion object {
        fun fromApiName(value: String?): DeepSeekModel =
            entries.firstOrNull { it.apiName == value } ?: V4_FLASH
    }
}

data class AiTranslationSettings(
    val model: DeepSeekModel = DeepSeekModel.V4_FLASH,
    val thinkingEnabled: Boolean = false,
    val targetLanguage: String = "zh-Hans",
)

data class TranslationLine(
    val id: String,
    val text: String,
)

sealed interface DeepSeekTranslationResult {
    data class Success(val translations: Map<String, String>) : DeepSeekTranslationResult
    data class Failed(val message: String) : DeepSeekTranslationResult
}

internal fun interface DeepSeekTransport {
    fun postJson(apiKey: String, body: String): String?
}

internal class HttpDeepSeekTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxResponseBytes: Int = 2 * 1024 * 1024,
) : DeepSeekTransport {
    override fun postJson(apiKey: String, body: String): String? = runCatching {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode !in 200..299) return null
            val buffer = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(8192)
                while (buffer.size() < maxResponseBytes) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                }
            }
            if (buffer.size() >= maxResponseBytes) return null
            buffer.toByteArray().toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private companion object {
        const val API_URL = "https://api.deepseek.com/chat/completions"
    }
}

class DeepSeekTranslationClient(
    private val transport: DeepSeekTransport = HttpDeepSeekTransport(),
) {
    fun translate(
        apiKey: String,
        lines: List<TranslationLine>,
        settings: AiTranslationSettings,
    ): DeepSeekTranslationResult {
        if (apiKey.isBlank()) return DeepSeekTranslationResult.Failed("请先配置 DeepSeek API Key")
        if (lines.isEmpty()) return DeepSeekTranslationResult.Failed("没有可翻译的歌词行")
        if (lines.size > MAX_LINES) return DeepSeekTranslationResult.Failed("歌词行数过多")
        if (!TARGET_LANGUAGE.matches(settings.targetLanguage.trim())) {
            return DeepSeekTranslationResult.Failed("目标语言格式无效")
        }
        val uniqueIds = lines.map(TranslationLine::id).toSet()
        if (uniqueIds.size != lines.size || lines.any { it.id.isBlank() || it.text.isBlank() }) {
            return DeepSeekTranslationResult.Failed("歌词行 ID 或文本无效")
        }

        val response = transport.postJson(apiKey.trim(), buildRequest(lines, settings))
            ?: return DeepSeekTranslationResult.Failed("DeepSeek 请求失败，请检查网络、API Key 或额度")
        return parseResponse(response, uniqueIds)
    }

    internal fun buildRequest(
        lines: List<TranslationLine>,
        settings: AiTranslationSettings,
    ): String {
        val payloadLines = JSONArray()
        lines.forEach { line ->
            payloadLines.put(JSONObject().put("id", line.id).put("text", line.text))
        }
        val userPayload = JSONObject()
            .put("target_language", settings.targetLanguage.trim())
            .put("lines", payloadLines)

        val systemPrompt = """
            You translate song lyrics. Translate every input line into the requested target language.
            Preserve the exact line id. Never add, remove, merge, split or reorder lines.
            Keep names and repeated refrains consistent. Prefer natural lyric translation over commentary.
            Return JSON only in this exact shape: {"translations":[{"id":"L1","text":"..."}]}.
            Do not include markdown, explanations, source lyrics, timing data, or extra keys.
        """.trimIndent()

        return JSONObject()
            .put("model", settings.model.apiName)
            .put(
                "thinking",
                JSONObject().put("type", if (settings.thinkingEnabled) "enabled" else "disabled"),
            )
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPayload.toString())),
            )
            .toString()
    }

    internal fun parseResponse(
        response: String,
        requiredIds: Set<String>,
    ): DeepSeekTranslationResult = runCatching {
        val root = JSONObject(response)
        val content = root.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
        if (content.isEmpty()) {
            return DeepSeekTranslationResult.Failed("DeepSeek 返回了空翻译")
        }
        val translationsArray = JSONObject(content).getJSONArray("translations")
        val translated = linkedMapOf<String, String>()
        for (index in 0 until translationsArray.length()) {
            val item = translationsArray.getJSONObject(index)
            val id = item.optString("id").trim()
            val text = item.optString("text").trim()
            if (id !in requiredIds || text.isEmpty() || translated.put(id, text) != null) {
                return DeepSeekTranslationResult.Failed("DeepSeek 返回的歌词行无法安全对齐")
            }
        }
        if (translated.keys != requiredIds) {
            return DeepSeekTranslationResult.Failed("DeepSeek 返回的翻译行不完整")
        }
        DeepSeekTranslationResult.Success(translated)
    }.getOrElse {
        DeepSeekTranslationResult.Failed("DeepSeek 返回格式无效")
    }

    companion object {
        const val MAX_LINES = 4096
        private val TARGET_LANGUAGE = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
    }
}
