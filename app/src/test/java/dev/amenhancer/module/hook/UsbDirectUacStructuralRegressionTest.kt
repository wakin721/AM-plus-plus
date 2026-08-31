package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDirectUacStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `USB Host permission broker claims AudioStreaming and validates Apple Music uid`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val permission = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectPermissionActivity.kt",
        )
        val broker = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt",
        )

        assertTrue(manifest.contains("android.hardware.usb.host"))
        assertTrue(manifest.contains("android.hardware.usb.action.USB_DEVICE_ATTACHED"))
        assertTrue(manifest.contains("@xml/usb_direct_device_filter"))
        assertTrue(manifest.contains("android:name=\".usb.UsbDirectPermissionActivity\""))
        assertTrue(manifest.contains("android:enabled=\"false\""))
        assertTrue(manifest.contains("android:name=\".usb.UsbDirectDeviceBrokerService\""))
        assertTrue(permission.contains("manager.requestPermission(device, permissionIntent)"))
        assertTrue(permission.contains("PendingIntent.FLAG_MUTABLE"))
        assertTrue(permission.contains("setComponentEnabledSetting"))
        assertTrue(broker.contains("message.sendingUid"))
        assertTrue(broker.contains("ModuleConstants.TARGET_PACKAGE"))
        assertTrue(broker.contains("connection.claimInterface(usbInterface, true)"))
        assertTrue(broker.contains("connection.setInterface(usbInterface)"))
        assertTrue(broker.contains("ParcelFileDescriptor.fromFd(connection.fileDescriptor)"))
        assertTrue(broker.contains("USB_RECIPIENT_INTERFACE = 0x01"))
        assertTrue(broker.contains("USB_RECIPIENT_ENDPOINT = 0x02"))
        assertTrue(broker.contains("UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIPIENT_INTERFACE"))
        assertTrue(broker.contains("UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIPIENT_ENDPOINT"))
        assertFalse(broker.contains("UsbConstants.USB_RECIP_INTERFACE"))
        assertFalse(broker.contains("UsbConstants.USB_RECIP_ENDPOINT"))
    }

    @Test
    fun `broker client follows the actual debug or release application id`() {
        val client = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt",
        )
        val gradle = projectFile("app/build.gradle.kts")

        assertTrue(gradle.contains("applicationIdSuffix = \".debug\""))
        assertTrue(gradle.contains("buildConfig = true"))
        assertTrue(client.contains("import dev.amenhancer.module.BuildConfig"))
        assertTrue(client.contains("ComponentName(BuildConfig.APPLICATION_ID, UsbDirectIpc.SERVICE_CLASS)"))
        assertFalse(client.contains("ComponentName(ModuleConstants.MODULE_PACKAGE"))
    }

    @Test
    fun `broker visibility is granted to the injected Apple Music process`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val provider = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectVisibilityProvider.kt",
        )
        val application = projectFile(
            "app/src/main/java/dev/amenhancer/module/ModuleApplication.kt",
        )
        val permission = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectPermissionActivity.kt",
        )
        val client = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt",
        )

        assertTrue(manifest.contains("<package android:name=\"com.apple.android.music\" />"))
        assertTrue(manifest.contains("android:name=\".usb.UsbDirectVisibilityProvider\""))
        assertTrue(manifest.contains("android:authorities=\"\${applicationId}.usb-direct-visibility\""))
        assertTrue(manifest.contains("android:grantUriPermissions=\"true\""))
        assertTrue(manifest.contains("<grant-uri-permission android:path=\"/bridge\" />"))
        assertTrue(provider.contains("application.grantUriPermission("))
        assertTrue(provider.contains("ModuleConstants.TARGET_PACKAGE"))
        assertTrue(provider.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(application.contains("UsbDirectVisibilityGrant.grantToAppleMusic(this)"))
        assertTrue(permission.contains("UsbDirectVisibilityGrant.grantToAppleMusic(this)"))
        assertTrue(client.contains("resolveService(intent"))
        assertTrue(client.contains("not visible/resolvable"))
    }

    @Test
    fun `native engine uses usbfs isochronous URBs rather than AAudio`() {
        val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")
        val cmake = projectFile("app/src/main/cpp/CMakeLists.txt")

        assertTrue(native.contains("USBDEVFS_URB_TYPE_ISO"))
        assertTrue(native.contains("USBDEVFS_URB_ISO_ASAP"))
        assertTrue(native.contains("USBDEVFS_SUBMITURB"))
        assertTrue(native.contains("USBDEVFS_REAPURB"))
        assertTrue(native.contains("USBDEVFS_DISCARDURB"))
        assertTrue(native.contains("dup(fd)"))
        assertTrue(native.contains("attenuateIntegerSample"))
        assertTrue(native.contains("gainForSample"))
        assertTrue(native.contains("gainLeft"))
        assertTrue(native.contains("gainRight"))
        assertTrue(native.contains("std::unordered_map<jlong, std::shared_ptr<Session>> gSessions"))
        assertTrue(native.contains("findSession(handle)"))
        assertTrue(native.contains("takeSession(handle)"))
        assertFalse(native.contains("delete session"))
        assertTrue(cmake.contains("UsbDirectUac.cpp"))
    }

    @Test
    fun `direct takeover has priority but remains fail open to AAudio`() {
        val hook = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbBitPerfectFeature.kt",
        )
        val controller = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacController.kt",
        )
        val protocol = projectFile(
            "app/src/main/java/dev/amenhancer/module/UsbBitPerfectStatusProtocol.kt",
        )
        val bridge = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt",
        )

        val directIntercept = hook.indexOf("UsbDirectUacController.interceptWrite")
        val aaudioIntercept = hook.indexOf("UsbExclusiveAaudioController.interceptWrite")
        assertTrue(directIntercept >= 0)
        assertTrue(aaudioIntercept > directIntercept)
        assertTrue(hook.contains("UsbDirectUacController.allowsAaudioFallback(track)"))
        assertTrue(hook.contains("UsbDirectUacController.onSystemMediaVolumeChanged(index)"))
        assertTrue(hook.contains("UsbDirectUacController.afterVolumeChange"))
        assertTrue(controller.contains("if (active.hasWrittenPcm)"))
        assertTrue(controller.contains("STATE_DIRECT_ACTIVE"))
        assertTrue(controller.contains("UsbDirectDeviceClient.release(context)"))
        assertTrue(controller.contains("UsbExclusiveVolumePolicy.streamGain"))
        assertTrue(controller.contains("streamGainCache.refresh"))
        assertTrue(controller.contains("streamGainCache.effectiveGain"))
        assertTrue(controller.contains("it === expectedSession"))
        assertTrue(controller.contains("shouldResumeOriginalTrack(closedOwnedSession)"))
        val hotPath = controller.substringAfter("private fun effectiveGains")
            .substringBefore("private fun")
        assertFalse(hotPath.contains("getStreamVolume"))
        assertFalse(hotPath.contains("getStreamMaxVolume"))
        assertFalse(hotPath.contains("getStreamVolumeDb"))
        assertTrue(controller.contains("gainLeft = gains.left"))
        assertTrue(controller.contains("gainRight = gains.right"))
        assertTrue(bridge.contains("gainLeft: Float"))
        assertTrue(bridge.contains("gainRight: Float"))
        assertTrue(protocol.contains("STATE_DIRECT_PERMISSION_REQUIRED"))
        assertTrue(protocol.contains("STATE_DIRECT_UNSUPPORTED_DEVICE"))
    }

    @Test
    fun `USB Direct reports descriptor bit resolution separately from Apple Music float PCM`() {
        val controller = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacController.kt",
        )
        val ui = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsActivity.kt",
        )

        assertTrue(controller.contains("lease.bitResolution <= 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED"))
        assertTrue(controller.contains("mixerEncoding = lease?.let(::usbEncoding) ?: 0"))
        assertTrue(ui.contains("AudioFormat.ENCODING_PCM_FLOAT -> \"PCM Float\""))
        assertTrue(ui.contains("AudioFormat.ENCODING_PCM_24BIT_PACKED -> \"PCM 24-bit\""))
        assertTrue(ui.contains("\$mixerFormat · USB DIRECT · usbfs ISO PCM"))
    }

    @Test
    fun `explicit feedback endpoint survives broker IPC lease and JNI boundary`() {
        val ipc = projectFile(
            "app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt",
        )
        val broker = projectFile(
            "app/src/main/java/dev/amenhancer/module/usb/UsbDirectDeviceBrokerService.kt",
        )
        val client = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectDeviceClient.kt",
        )
        val bridge = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbDirectUacBridge.kt",
        )
        val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")

        assertTrue(ipc.contains("KEY_FEEDBACK_ENDPOINT_ADDRESS"))
        assertTrue(ipc.contains("KEY_FEEDBACK_MAX_PACKET_SIZE"))
        assertTrue(ipc.contains("KEY_FEEDBACK_INTERVAL"))
        assertTrue(broker.contains("alternative.feedbackEndpointAddress"))
        assertTrue(broker.contains("alternative.feedbackMaxPacketSize"))
        assertTrue(broker.contains("alternative.feedbackInterval"))
        assertTrue(client.contains("val feedbackEndpointAddress: Int"))
        assertTrue(client.contains("val feedbackMaxPacketSize: Int"))
        assertTrue(client.contains("val feedbackInterval: Int"))
        assertTrue(bridge.contains("lease.feedbackEndpointAddress"))
        assertTrue(bridge.contains("lease.feedbackMaxPacketSize"))
        assertTrue(bridge.contains("lease.feedbackInterval"))
        assertTrue(native.contains("jint feedbackEndpointAddress"))
        assertTrue(native.contains("jint feedbackMaxPacketSize"))
        assertTrue(native.contains("jint feedbackInterval"))
    }
}
