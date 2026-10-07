package dev.amenhancer.module.config

/** Appearance belongs to the shared settings, without changing the host's theme. */
internal object SettingsAppearancePolicy {
    const val KEY = "settings_ui_theme_mode_v1"
    val modes = setOf("SYSTEM", "LIGHT", "DARK")

    fun legacyPatch(shared: Map<String, *>, legacy: Map<String, *>): Map<String, String> {
        if (KEY in shared) return emptyMap()
        val mode = legacy["theme_mode"] as? String ?: return emptyMap()
        return if (mode in modes) mapOf(KEY to mode) else emptyMap()
    }
}
