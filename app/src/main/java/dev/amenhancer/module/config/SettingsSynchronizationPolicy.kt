package dev.amenhancer.module.config

import dev.amenhancer.module.model.ModuleSettings

internal object SettingsSynchronizationPolicy {
    const val INITIALIZED_KEY = "shared_settings_initialized_v1"
    val allowedKeys: Set<String> = (ModuleSettingsSchema.encode(ModuleSettings()).keys +
        ModuleSettingsSchema.encodeIndexPointer(CustomLyricsIndexPointer("index_placeholder", 0L, "0".repeat(64), 0L)).keys +
        ModuleSettingsSchema.obsoleteKeys + INITIALIZED_KEY + SettingsAppearancePolicy.KEY).toSet()

    fun ordinaryPatch(baseline: ModuleSettings, next: ModuleSettings): Map<String, Any> {
        val before = ModuleSettingsSchema.encodeOrdinarySettings(baseline)
        return ModuleSettingsSchema.encodeOrdinarySettings(next).filter { (key, value) -> before[key] != value }
    }

    /** Preserve the currently effective host configuration once, then use one shared store. */
    fun initializeFromHost(
        host: EmbeddedConfigurationStorage,
        shared: EmbeddedConfigurationStorage,
    ): EmbeddedConfigurationMigrationResult {
        if (shared.values()[INITIALIZED_KEY] == true) return EmbeddedConfigurationMigrationResult.SkippedAlreadyComplete
        val appearance = (host.values()[SettingsAppearancePolicy.KEY] ?: shared.values()[SettingsAppearancePolicy.KEY])
            ?.let { mapOf(SettingsAppearancePolicy.KEY to it) }.orEmpty()
        val staging = object : EmbeddedConfigurationStorage by shared {
            override fun values(): Map<String, *> = emptyMap<String, Any>()
            override fun hasAnyFiles() = false
            override fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean {
                if (values[EmbeddedConfigurationMigration.MIGRATION_MARKER_KEY] != EmbeddedConfigurationMigration.MIGRATION_COMPLETE) return true
                return shared.writeValues(values.filterKeys { it != EmbeddedConfigurationMigration.MIGRATION_MARKER_KEY } +
                    (INITIALIZED_KEY to true) + appearance, synchronous = true)
            }
        }
        val result = EmbeddedConfigurationMigration.migrate(host.values(), host::openFile, staging)
        if (result == EmbeddedConfigurationMigrationResult.SkippedNoRemoteConfiguration) {
            return if (shared.writeValues(mapOf(INITIALIZED_KEY to true), true)) result
            else EmbeddedConfigurationMigrationResult.Failed("无法初始化共享设置")
        }
        return result
    }
}
