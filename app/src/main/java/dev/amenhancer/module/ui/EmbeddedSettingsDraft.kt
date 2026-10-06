package dev.amenhancer.module.ui

import dev.amenhancer.module.model.ModuleSettings

/** Page-owned settings remain available for retry when persistence fails. */
internal class EmbeddedSettingsDraft(
    initial: ModuleSettings,
    private val persist: (ModuleSettings) -> Boolean,
) {
    var settings: ModuleSettings = initial
        private set
    var hasUnsavedChanges = false
        private set

    fun update(next: ModuleSettings): Boolean {
        settings = next
        return save()
    }

    fun updateCellularDataEntry(enabled: Boolean): Boolean =
        update(settings.copy(forceCellularDataEntryEnabled = enabled))

    fun save(): Boolean = persist(settings).also { hasUnsavedChanges = !it }

    fun reload(current: ModuleSettings) {
        if (!hasUnsavedChanges) settings = current
    }
}
