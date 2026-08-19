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
    fun `native engine uses usbfs isochronous URBs rather than AAudio`() {
        val native = projectFile("app/src/main/cpp/UsbDirectUac.cpp")
        val cmake = projectFile("app/src/main/cpp/CMakeLists.txt")

        assertTrue(native.contains("USBDEVFS_URB_TYPE_ISO"))
        assertTrue(native.contains("USBDEVFS_URB_ISO_ASAP"))
        assertTrue(native.contains("USBDEVFS_SUBMITURB"))
        assertTrue(native.contains("USBDEVFS_REAPURB"))
        assertTrue(native.contains("USBDEVFS_DISCARDURB"))
        assertTrue(native.contains("dup(fd)"))
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

        val directIntercept = hook.indexOf("UsbDirectUacController.interceptWrite")
        val aaudioIntercept = hook.indexOf("UsbExclusiveAaudioController.interceptWrite")
        assertTrue(directIntercept >= 0)
        assertTrue(aaudioIntercept > directIntercept)
        assertTrue(hook.contains("UsbDirectUacController.allowsAaudioFallback(track)"))
        assertTrue(controller.contains("if (active.hasWrittenPcm)"))
        assertTrue(controller.contains("STATE_DIRECT_ACTIVE"))
        assertTrue(controller.contains("UsbDirectDeviceClient.release(context)"))
        assertTrue(protocol.contains("STATE_DIRECT_PERMISSION_REQUIRED"))
        assertTrue(protocol.contains("STATE_DIRECT_UNSUPPORTED_DEVICE"))
    }
}
