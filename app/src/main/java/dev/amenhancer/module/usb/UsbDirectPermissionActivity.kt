package dev.amenhancer.module.usb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Small transparent activity used for the platform USB Host permission flow.
 *
 * The component is disabled unless the user explicitly enables USB Direct UAC.
 * This keeps the wildcard attach filter from claiming unrelated USB devices in
 * the normal AM++ configuration.
 */
class UsbDirectPermissionActivity : Activity() {
    private var receiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = intent.usbDevice()
            Toast.makeText(
                this@UsbDirectPermissionActivity,
                if (granted) {
                    "已授权 ${device?.productName ?: "USB 设备"}，重启 Apple Music 后可尝试 USB 直通。"
                } else {
                    "USB 设备访问被拒绝。"
                },
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The Xposed code that will bind the broker runs under Apple Music's
        // package identity. Granting this one empty provider URI makes AM++
        // visible to that process on Android 11+ before bindService() occurs.
        UsbDirectVisibilityGrant.grantToAppleMusic(this)

        val manager = getSystemService(UsbManager::class.java)
        if (manager == null) {
            finish()
            return
        }

        val attached = intent.usbDevice()
        val device = attached ?: findPermissionCandidate(manager)
        if (device == null) {
            Toast.makeText(this, "未检测到可请求权限的 USB 设备。", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (manager.hasPermission(device)) {
            if (isAudioDevice(device)) {
                Toast.makeText(
                    this,
                    "${device.productName ?: "USB DAC"} 已获得 USB Host 权限。",
                    Toast.LENGTH_LONG,
                ).show()
            }
            finish()
            return
        }

        registerPermissionReceiver()
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        manager.requestPermission(device, permissionIntent)
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(permissionReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerPermissionReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun findPermissionCandidate(manager: UsbManager): UsbDevice? {
        val devices = manager.deviceList.values.toList()
        devices.firstOrNull { it.deviceClass == UsbConstants.USB_CLASS_AUDIO }?.let { return it }
        // Composite USB Audio devices often advertise device class 0. Without
        // permission Android may hide interface descriptors, so only auto-pick a
        // composite candidate when it is the sole attached USB device.
        return devices.singleOrNull()
    }

    private fun isAudioDevice(device: UsbDevice): Boolean = runCatching {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return@runCatching true
        (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO
        }
    }.getOrDefault(false)

    private fun Intent.usbDevice(): UsbDevice? {
        @Suppress("DEPRECATION")
        return getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
    }

    companion object {
        const val ACTION_REQUEST_PERMISSION = "dev.amenhancer.module.action.REQUEST_USB_DIRECT_PERMISSION"
        private const val ACTION_USB_PERMISSION = "dev.amenhancer.module.action.USB_DIRECT_PERMISSION"

        fun setAttachHandlingEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, UsbDirectPermissionActivity::class.java),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }

        fun requestCurrentDevice(context: Context) {
            context.startActivity(
                Intent(context, UsbDirectPermissionActivity::class.java)
                    .setAction(ACTION_REQUEST_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
