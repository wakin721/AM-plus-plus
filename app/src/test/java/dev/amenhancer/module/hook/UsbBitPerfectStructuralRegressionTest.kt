package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBitPerfectStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `uses Android 14 preferred mixer attributes only for exact USB media output`() {
        val source = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbBitPerfectFeature.kt",
        )

        assertTrue(source.contains("Build.VERSION_CODES.UPSIDE_DOWN_CAKE"))
        assertTrue(source.contains("AudioAttributes.USAGE_MEDIA"))
        assertTrue(source.contains("track.routedDevice"))
        assertTrue(source.contains("track.preferredDevice"))
        assertTrue(source.contains("AudioManager.GET_DEVICES_OUTPUTS"))
        assertTrue(source.contains("AudioDeviceInfo.TYPE_USB_DEVICE"))
        assertTrue(source.contains("AudioDeviceInfo.TYPE_USB_HEADSET"))
        assertTrue(source.contains("AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT"))
        assertTrue(source.contains("candidate.format.matchesExactly(trackFormat)"))
        assertTrue(source.contains("manager.setPreferredMixerAttributes(attributes, device, selected)"))
        assertTrue(source.contains("manager.clearPreferredMixerAttributes(attributes, device)"))
        assertFalse(source.contains("UsbDeviceConnection"))
        assertFalse(source.contains("UsbRequest"))
    }

    @Test
    fun `feature remains explicitly gated and all invasive modes default off`() {
        val feature = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbBitPerfectFeature.kt",
        )
        val model = projectFile(
            "app/src/main/java/dev/amenhancer/module/model/ModuleModels.kt",
        )
        val schema = projectFile(
            "app/src/main/java/dev/amenhancer/module/config/ModuleSettingsSchema.kt",
        )

        assertTrue(feature.contains("val settings = context.config.settings()"))
        assertTrue(feature.contains("if (!settings.usbBitPerfectEnabled)"))
        assertTrue(feature.contains("UsbDirectUacController.configure(settings.usbDirectUacEnabled)"))
        assertTrue(feature.contains("UsbExclusiveAaudioController.configure(settings.usbExclusiveAaudioEnabled)"))
        assertTrue(model.contains("val usbBitPerfectEnabled: Boolean = false"))
        assertTrue(model.contains("val usbExclusiveAaudioEnabled: Boolean = false"))
        assertTrue(model.contains("val usbDirectUacEnabled: Boolean = false"))
        assertTrue(schema.contains("usb_bit_perfect_enabled"))
        assertTrue(schema.contains("usb_exclusive_aaudio_enabled"))
        assertTrue(schema.contains("usb_direct_uac_enabled"))
    }
}
