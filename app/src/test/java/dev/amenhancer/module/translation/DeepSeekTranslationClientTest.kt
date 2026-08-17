package dev.amenhancer.module.translation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekTranslationClientTest {
    @Test
    fun requestCarriesSelectedModelThinkingAndStableLineIds() {
        val client = DeepSeekTranslationClient { _, _ -> null }
        val request = JSONObject(
            client.buildRequest(
                listOf(
                    TranslationLine("L1", "Hello"),
                    TranslationLine("L2", "world"),
                ),
                AiTranslationSettings(
                    model = DeepSeekModel.V4_PRO,
                    thinkingEnabled = true,
                    targetLanguage = "zh-Hans",
                ),
            ),
        )

        assertEquals("deepseek-v4-pro", request.getString("model"))
        assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
        val userContent = request.getJSONArray("messages").getJSONObject(1).getString("content")
        val payload = JSONObject(userContent)
        assertEquals("zh-Hans", payload.getString("target_language"))
        assertEquals("L1", payload.getJSONArray("lines").getJSONObject(0).getString("id"))
        assertEquals("L2", payload.getJSONArray("lines").getJSONObject(1).getString("id"))
    }

    @Test
    fun translateRejectsIncompleteResponseInsteadOfShiftingLines() {
        val response = JSONObject()
            .put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "translations",
                                org.json.JSONArray().put(
                                    JSONObject().put("id", "L1").put("text", "你好"),
                                ),
                            ).toString(),
                        ),
                    ),
                ),
            ).toString()
        val client = DeepSeekTranslationClient { _, _ -> response }

        val result = client.translate(
            apiKey = "test-key",
            lines = listOf(TranslationLine("L1", "Hello"), TranslationLine("L2", "world")),
            settings = AiTranslationSettings(),
        )

        assertTrue(result is DeepSeekTranslationResult.Failed)
    }

    @Test
    fun translateReturnsMapOnlyWhenEveryRequestedIdIsPresentExactlyOnce() {
        val content = JSONObject().put(
            "translations",
            org.json.JSONArray()
                .put(JSONObject().put("id", "L1").put("text", "你好"))
                .put(JSONObject().put("id", "L2").put("text", "世界")),
        ).toString()
        val response = JSONObject().put(
            "choices",
            org.json.JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", content)),
            ),
        ).toString()
        val client = DeepSeekTranslationClient { _, _ -> response }

        val result = client.translate(
            apiKey = "test-key",
            lines = listOf(TranslationLine("L1", "Hello"), TranslationLine("L2", "world")),
            settings = AiTranslationSettings(model = DeepSeekModel.V4_FLASH),
        )

        assertTrue(result is DeepSeekTranslationResult.Success)
        result as DeepSeekTranslationResult.Success
        assertEquals(mapOf("L1" to "你好", "L2" to "世界"), result.translations)
    }
}
