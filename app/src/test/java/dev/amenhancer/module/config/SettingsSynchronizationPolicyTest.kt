package dev.amenhancer.module.config

import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.ModuleSettings
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SettingsSynchronizationPolicyTest {
    @Test fun `initial conflicts prefer host and later launches preserve shared changes`() {
        val host = MemoryStorage(ModuleSettingsSchema.encode(ModuleSettings(dualPaneEnabled = false)))
        val shared = MemoryStorage(ModuleSettingsSchema.encode(ModuleSettings(dualPaneEnabled = true)))
        assertTrue(SettingsSynchronizationPolicy.initializeFromHost(host, shared) is EmbeddedConfigurationMigrationResult.Migrated)
        assertFalse(ModuleSettingsSchema.decode(shared.values()).dualPaneEnabled)
        shared.writeValues(ModuleSettingsSchema.encodeOrdinarySettings(ModuleSettings(dualPaneEnabled = true)), true)
        assertEquals(EmbeddedConfigurationMigrationResult.SkippedAlreadyComplete,
            SettingsSynchronizationPolicy.initializeFromHost(host, shared))
        assertTrue(ModuleSettingsSchema.decode(shared.values()).dualPaneEnabled)
    }

    @Test fun `empty host preserves existing module settings`() {
        val shared = MemoryStorage(ModuleSettingsSchema.encode(ModuleSettings(dualPaneEnabled = false)))
        SettingsSynchronizationPolicy.initializeFromHost(MemoryStorage(), shared)
        assertFalse(ModuleSettingsSchema.decode(shared.values()).dualPaneEnabled)
        assertEquals(true, shared.values()[SettingsSynchronizationPolicy.INITIALIZED_KEY])
    }

    @Test fun `font payload is copied and verified before settings are published`() {
        val bytes = byteArrayOf(0, 1, 2, 3)
        val font = LyricsFontManifest(enabled = true, fileId = "font_sync", displayName = "Sync.ttf",
            sizeBytes = bytes.size.toLong(), sha256 = sha256(bytes))
        val host = MemoryStorage(ModuleSettingsSchema.encode(ModuleSettings(fontManifest = font)))
        host.writeFile(font.fileId, bytes)
        val shared = MemoryStorage()
        assertTrue(SettingsSynchronizationPolicy.initializeFromHost(host, shared) is EmbeddedConfigurationMigrationResult.Migrated)
        assertArrayEquals(bytes, shared.openFile(font.fileId)?.readBytes())
        assertEquals(font, ModuleSettingsSchema.decode(shared.values()).fontManifest)
    }

    @Test fun `missing payload leaves prior shared settings and permits retry`() {
        val font = LyricsFontManifest(enabled = true, fileId = "font_missing", displayName = "Missing.ttf",
            sizeBytes = 4L, sha256 = sha256(byteArrayOf(0, 1, 2, 3)))
        val host = MemoryStorage(ModuleSettingsSchema.encode(ModuleSettings(dualPaneEnabled = false, fontManifest = font)))
        val shared = MemoryStorage(ModuleSettingsSchema.encode(ModuleSettings(dualPaneEnabled = true)))
        assertTrue(SettingsSynchronizationPolicy.initializeFromHost(host, shared) is EmbeddedConfigurationMigrationResult.Failed)
        assertTrue(ModuleSettingsSchema.decode(shared.values()).dualPaneEnabled)
        assertNull(shared.values()[SettingsSynchronizationPolicy.INITIALIZED_KEY])
        host.writeFile(font.fileId, byteArrayOf(0, 1, 2, 3))
        assertTrue(SettingsSynchronizationPolicy.initializeFromHost(host, shared) is EmbeddedConfigurationMigrationResult.Migrated)
    }

    @Test fun `stale ordinary draft preserves a different remotely changed option`() {
        val shared = MemoryStorage(ModuleSettingsSchema.encode(ModuleSettings()))
        val session = EmbeddedConfigurationSession(shared)
        val draft = session.settings()
        shared.writeValues(ModuleSettingsSchema.encodeOrdinarySettings(draft.copy(futureBlurEnabled = false)), true)
        assertTrue(session.saveSettings(draft.copy(dualPaneEnabled = false)))
        val saved = ModuleSettingsSchema.decode(shared.values())
        assertFalse(saved.dualPaneEnabled)
        assertFalse(saved.futureBlurEnabled)
    }

    @Test fun `failed save retains baseline so a retry includes every unsaved change`() {
        val shared = MemoryStorage(ModuleSettingsSchema.encode(ModuleSettings()))
        val session = EmbeddedConfigurationSession(shared)
        val draft = session.settings().copy(dualPaneEnabled = false)
        shared.failWrites = true
        assertFalse(session.saveSettings(draft))
        // A remote refresh while the page retains its draft must not replace
        // the baseline used to calculate its pending changes.
        session.settings()
        shared.failWrites = false
        assertTrue(session.saveSettings(draft.copy(futureBlurEnabled = false)))
        assertFalse(ModuleSettingsSchema.decode(shared.values()).dualPaneEnabled)
        assertFalse(ModuleSettingsSchema.decode(shared.values()).futureBlurEnabled)
    }

    private class MemoryStorage(initial: Map<String, Any> = emptyMap()) : EmbeddedConfigurationStorage {
        private val data = initial.toMutableMap()
        private val files = mutableMapOf<String, ByteArray>()
        var failWrites = false
        override fun values(): Map<String, *> = data.toMap()
        override fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean {
            if (failWrites) return false
            data.putAll(values)
            return true
        }
        override fun removeValues(keys: Set<String>, synchronous: Boolean): Boolean {
            keys.forEach(data::remove)
            return true
        }
        override fun openFile(name: String): InputStream? = files[name]?.let(::ByteArrayInputStream)
        override fun writeFile(name: String, bytes: ByteArray): Boolean { files[name] = bytes; return true }
        override fun deleteFile(name: String): Boolean = files.remove(name) != null
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
