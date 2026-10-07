package dev.amenhancer.module.config

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.amenhancer.module.BuildConfig
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Cached reads stay in the host; authorized writes run in the module process. */
internal class SharedEmbeddedConfigurationStorage(
    context: Context,
    private val host: EmbeddedConfigurationStorage,
    private val remote: SharedPreferences,
    private val remoteFile: (String) -> ParcelFileDescriptor?,
    private val onChanged: () -> Unit,
) : EmbeddedConfigurationStorage {
    private val resolver = context.applicationContext.contentResolver
    private val authority = "${BuildConfig.APPLICATION_ID}.settings-sync"
    private val uri = Uri.parse("content://$authority")
    private val migrationLock = Any()
    @Volatile private var cachedValues: Map<String, *>? = null
    @Volatile private var ready = remote.all[SettingsSynchronizationPolicy.INITIALIZED_KEY] == true
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, _ ->
        cachedValues = preferences.all
        if (cachedValues?.get(SettingsSynchronizationPolicy.INITIALIZED_KEY) == true) ready = true
        onChanged()
    }

    init { remote.registerOnSharedPreferenceChangeListener(listener) }

    private val shared = object : EmbeddedConfigurationStorage {
        override fun values(): Map<String, *> {
            val result = resolver.call(uri, "read", null, null)
            check(result?.getBoolean("available") == true) { "共享设置服务尚未连接" }
            return SettingsSyncWire.decode(result).filterKeys { it != "available" }
        }
        override fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean =
            mutate("write", values)
        override fun removeValues(keys: Set<String>, synchronous: Boolean): Boolean =
            mutate("remove", keys.associateWith { true })
        override fun openFileDescriptor(name: String): ParcelFileDescriptor? =
            runCatching { remoteFile(name) }.getOrNull()
        override fun openFile(name: String): InputStream? =
            openFileDescriptor(name)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        override fun writeFile(name: String, bytes: ByteArray): Boolean = runCatching {
            val descriptor = resolver.openFileDescriptor(fileUri(name), "w") ?: return@runCatching false
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
                stream.channel.truncate(0)
                stream.write(bytes)
                stream.flush()
            }
            true
        }.getOrDefault(false)
        override fun deleteFile(name: String): Boolean = runCatching {
            resolver.call(uri, "delete-file", name, null)?.getBoolean("success") == true
        }.getOrDefault(false)
    }

    /** Retry a cold module/service connection without blocking Application.onCreate. */
    fun startSynchronization() {
        val worker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ampp-settings-sync").apply { isDaemon = true }
        }
        worker.scheduleWithFixedDelay({
            val completed = synchronized(migrationLock) {
                runCatching {
                    // Also warm the module after an upgrade with an already
                    // initialized store, so private appearance is migrated.
                    val published = shared.values()
                    if (published[SettingsSynchronizationPolicy.INITIALIZED_KEY] == true) {
                        cachedValues = published
                        ready = true
                        return@runCatching true
                    }
                    val result = SettingsSynchronizationPolicy.initializeFromHost(host, shared)
                    if (result is EmbeddedConfigurationMigrationResult.Failed) false else {
                        cachedValues = shared.values()
                        ready = cachedValues?.get(SettingsSynchronizationPolicy.INITIALIZED_KEY) == true
                        ready
                    }
                }.getOrDefault(false)
            }
            if (completed) {
                worker.shutdown()
                onChanged()
            }
        }, 0, 5, TimeUnit.SECONDS)
    }

    override fun values(): Map<String, *> = if (ready) cachedValues ?: remote.all else host.values()
    override fun openFileDescriptor(name: String): ParcelFileDescriptor? =
        if (ready) shared.openFileDescriptor(name) else host.openFileDescriptor(name)
    override fun openFile(name: String): InputStream? =
        if (ready) shared.openFile(name) else host.openFile(name)

    // Failed writes keep the UI draft; never create another private settings fork.
    override fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean =
        ready && shared.writeValues(values, synchronous)
    override fun removeValues(keys: Set<String>, synchronous: Boolean): Boolean =
        ready && shared.removeValues(keys, synchronous)
    override fun writeFile(name: String, bytes: ByteArray): Boolean = ready && shared.writeFile(name, bytes)
    override fun deleteFile(name: String): Boolean = ready && shared.deleteFile(name)

    private fun fileUri(name: String): Uri {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(name))
        return uri.buildUpon().appendPath("files").appendPath(name).build()
    }

    private fun mutate(method: String, values: Map<String, Any>): Boolean = runCatching {
        // The Uri overload is API 11; the authority String overload requires API 29.
        val result = resolver.call(uri, method, null, SettingsSyncWire.encode(values))
        if (result?.getBoolean("success") != true) return@runCatching false
        cachedValues = SettingsSyncWire.decode(result).filterKeys { it != "success" && it != "available" }
        true
    }.getOrDefault(false)
}
