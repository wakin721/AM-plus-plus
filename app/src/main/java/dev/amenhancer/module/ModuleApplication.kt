package dev.amenhancer.module

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.config.ConfigStore
import dev.amenhancer.module.usb.UsbDirectPermissionActivity
import dev.amenhancer.module.usb.UsbDirectVisibilityGrant
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

class ModuleApplication : Application(), XposedServiceHelper.OnServiceListener {
    private var observedPreferences: SharedPreferences? = null
    private val changeHandler = Handler(Looper.getMainLooper())
    private val notifySettingsChanged = Runnable {
        val snapshot = serviceSnapshot
        snapshot.preferences?.let { preferences ->
            val settings = dev.amenhancer.module.config.ModuleSettingsSchema.decode(preferences.all)
            UsbDirectPermissionActivity.setAttachHandlingEnabled(this,
                settings.usbBitPerfectEnabled && settings.usbDirectUacEnabled)
        }
        listeners.forEach { it(snapshot) }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        changeHandler.removeCallbacks(notifySettingsChanged)
        changeHandler.post(notifySettingsChanged)
    }
    override fun onCreate() {
        super.onCreate()
        // Also makes the settings bridge visible to the injected host on Android 11+.
        UsbDirectVisibilityGrant.grantToAppleMusic(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        val supportsRemote = service.apiVersion >= 102 &&
            service.frameworkProperties.and(XposedService.PROP_CAP_REMOTE) != 0L
        if (!supportsRemote) {
            publish(XposedServiceSnapshot.unsupported(service.frameworkName, service.apiVersion))
            return
        }
        val preferences = service.getRemotePreferences(ModuleConstants.REMOTE_PREFERENCES_GROUP)
        ConfigStore.migrateLegacyPreferences(this, preferences)
        observedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        observedPreferences = preferences
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        publish(XposedServiceSnapshot.connected(
            preferences = preferences,
            frameworkName = service.frameworkName,
            apiVersion = service.apiVersion,
            service = service,
        ))

        val settings = ConfigStore(this).settings()
        UsbDirectPermissionActivity.setAttachHandlingEnabled(this,
            settings.usbBitPerfectEnabled && settings.usbDirectUacEnabled)
        if (settings.usbBitPerfectEnabled && settings.usbDirectUacEnabled) {
            UsbDirectVisibilityGrant.grantToAppleMusic(this)
        }
    }

    override fun onServiceDied(service: XposedService) {
        observedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        observedPreferences = null
        publish(XposedServiceSnapshot.disconnected())
    }

    companion object {
        private val serviceSnapshotReference = AtomicReference(XposedServiceSnapshot.waiting())
        internal val serviceSnapshot: XposedServiceSnapshot get() = serviceSnapshotReference.get()

        internal fun isCurrentSnapshot(snapshot: XposedServiceSnapshot): Boolean =
            serviceSnapshotReference.get() === snapshot
        private val listeners = CopyOnWriteArraySet<(XposedServiceSnapshot) -> Unit>()

        internal fun addServiceListener(listener: (XposedServiceSnapshot) -> Unit) {
            listeners += listener
        }

        internal fun removeServiceListener(listener: (XposedServiceSnapshot) -> Unit) {
            listeners -= listener
        }

        private fun publish(snapshot: XposedServiceSnapshot) {
            serviceSnapshotReference.set(snapshot)
            listeners.forEach { it(snapshot) }
        }
    }
}
