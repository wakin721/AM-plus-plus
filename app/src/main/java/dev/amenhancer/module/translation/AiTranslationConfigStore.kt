package dev.amenhancer.module.translation

import android.content.Context
import android.net.Uri
import android.os.Bundle
import dev.amenhancer.module.BuildConfig
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI configuration stays encrypted in the module's private storage. The explicit
 * in-host editor accesses it through the UID-checked bridge, never remote preferences.
 */
class AiTranslationConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val useBridge = appContext.packageName != BuildConfig.APPLICATION_ID
    private fun bridge(method: String, extras: Bundle? = null): Bundle? = runCatching {
        appContext.contentResolver.call(Uri.parse("content://${BuildConfig.APPLICATION_ID}.settings-sync"),
            method, null, extras)
    }.getOrNull()
    private val preferences by lazy {
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun settings(): AiTranslationSettings {
        if (useBridge) {
            val values = bridge("ai-read") ?: Bundle()
            return AiTranslationSettings(DeepSeekModel.fromApiName(values.getString("model")),
                values.getBoolean("thinking"), values.getString("language") ?: DEFAULT_TARGET_LANGUAGE)
        }
        return AiTranslationSettings(
            model = DeepSeekModel.fromApiName(preferences.getString(KEY_MODEL, null)),
            thinkingEnabled = preferences.getBoolean(KEY_THINKING, false),
            targetLanguage = preferences.getString(KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_TARGET_LANGUAGE,
        )
    }

    fun saveSettings(settings: AiTranslationSettings): Boolean {
        if (useBridge) {
            return bridge("ai-save", Bundle().apply {
                putString("model", settings.model.apiName)
                putBoolean("thinking", settings.thinkingEnabled)
                putString("language", settings.targetLanguage)
            })?.getBoolean("success") == true
        }
        return preferences.edit()
            .putString(KEY_MODEL, settings.model.apiName)
            .putBoolean(KEY_THINKING, settings.thinkingEnabled)
            .putString(KEY_TARGET_LANGUAGE, settings.targetLanguage.trim())
            .commit()
    }

    fun apiKey(): String {
        if (useBridge) return bridge("ai-read")?.getString("api-key").orEmpty()
        val cipherText = preferences.getString(KEY_API_KEY_CIPHER, null) ?: return ""
        val iv = preferences.getString(KEY_API_KEY_IV, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun saveApiKey(value: String): Boolean {
        if (useBridge) return bridge("ai-save", Bundle().apply { putString("api-key", value) })
            ?.getBoolean("success") == true
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            preferences.edit().remove(KEY_API_KEY_CIPHER).remove(KEY_API_KEY_IV).apply()
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            preferences.edit()
                .putString(KEY_API_KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_API_KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
        }.getOrDefault(false)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "ai-translation-private"
        const val KEY_MODEL = "deepseek_model"
        const val KEY_THINKING = "deepseek_thinking"
        const val KEY_TARGET_LANGUAGE = "translation_target_language"
        const val KEY_API_KEY_CIPHER = "deepseek_api_key_cipher"
        const val KEY_API_KEY_IV = "deepseek_api_key_iv"
        const val DEFAULT_TARGET_LANGUAGE = "zh-Hans"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "ampp.deepseek.api-key"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
