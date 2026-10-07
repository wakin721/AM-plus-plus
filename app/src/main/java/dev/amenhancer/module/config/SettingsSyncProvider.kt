package dev.amenhancer.module.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.ModuleConstants
import java.io.FileNotFoundException

/** Only the module and its Apple Music host may access the shared settings/files. */
class SettingsSyncProvider : ContentProvider() {
    override fun onCreate() = true

    private fun authorize() {
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && context?.packageManager?.getPackagesForUid(uid)
            ?.contains(ModuleConstants.TARGET_PACKAGE) != true) throw SecurityException("Settings caller denied")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize()
        if (method == "ai-read" || method == "ai-save") {
            val store = dev.amenhancer.module.translation.AiTranslationConfigStore(requireNotNull(context))
            return synchronized(WRITE_LOCK) {
                if (method == "ai-read") {
                    val settings = store.settings()
                    Bundle().apply {
                        putString("model", settings.model.apiName)
                        putBoolean("thinking", settings.thinkingEnabled)
                        putString("language", settings.targetLanguage)
                        putString("api-key", store.apiKey())
                    }
                } else Bundle().apply {
                    val values = extras ?: Bundle()
                    val keySaved = !values.containsKey("api-key") || store.saveApiKey(values.getString("api-key").orEmpty())
                    val settingsSaved = !values.containsKey("model") || store.saveSettings(dev.amenhancer.module.translation.AiTranslationSettings(
                        dev.amenhancer.module.translation.DeepSeekModel.fromApiName(values.getString("model")),
                        values.getBoolean("thinking"), values.getString("language") ?: "zh-Hans"))
                    putBoolean("success", keySaved && settingsSaved)
                }
            }
        }
        val snapshot = ModuleApplication.serviceSnapshot
        val preferences = snapshot.preferences ?: return Bundle()
        return synchronized(WRITE_LOCK) {
            when (method) {
                "enable-usb-permission" -> Bundle().apply {
                    val settings = ModuleSettingsSchema.decode(preferences.all)
                    val enabled = settings.usbBitPerfectEnabled && settings.usbDirectUacEnabled
                    dev.amenhancer.module.usb.UsbDirectPermissionActivity.setAttachHandlingEnabled(requireNotNull(context), enabled)
                    putBoolean("success", enabled)
                }
                "read" -> SettingsSyncWire.encode(preferences.all).apply { putBoolean("available", true) }
                "write", "remove" -> {
                    val values = SettingsSyncWire.decode(extras ?: Bundle())
                    val allowed = SettingsSynchronizationPolicy.allowedKeys
                    require(values.keys.all { it in allowed }) { "Unknown settings key" }
                    val editor = preferences.edit()
                    // Initial promotion publishes the entire host configuration. Remove
                    // old pointers too, so an empty host library cannot inherit stale files.
                    if (method == "write" && values[SettingsSynchronizationPolicy.INITIALIZED_KEY] == true &&
                        preferences.all[SettingsSynchronizationPolicy.INITIALIZED_KEY] != true && values.size > 1) {
                        allowed.forEach(editor::remove)
                    }
                    values.forEach { (key, value) ->
                        if (method == "remove") editor.remove(key) else when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is String -> editor.putString(key, value)
                        }
                    }
                    val success = editor.commit()
                    SettingsSyncWire.encode(preferences.all).apply {
                        putBoolean("success", success)
                        putBoolean("available", true)
                    }
                }
                "delete-file" -> Bundle().apply {
                    require(arg != null && FILE_NAME.matches(arg))
                    putBoolean("success", snapshot.deleteRemoteFile(arg))
                }
                else -> error("Unknown settings operation")
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        require(uri.pathSegments.size == 2 && uri.pathSegments.first() == "files" && FILE_NAME.matches(name))
        require(mode == "r" || mode == "w")
        return ModuleApplication.serviceSnapshot.openRemoteFile(name) ?: throw FileNotFoundException(name)
    }

    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        val WRITE_LOCK = Any()
        val FILE_NAME = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

internal object SettingsSyncWire {
    fun encode(values: Map<String, *>): Bundle = Bundle().apply {
        values.forEach { (key, value) -> when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is String -> putString(key, value)
        } }
    }

    @Suppress("DEPRECATION")
    fun decode(bundle: Bundle): Map<String, Any> = bundle.keySet().associateWith { bundle.get(it) }
        .filterValues { it is Boolean || it is Int || it is Long || it is String }
        .mapValues { checkNotNull(it.value) }
}
