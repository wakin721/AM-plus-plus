package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBitPerfectSettingsUiStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `main settings keeps a compact navigation entry`() {
        val application = projectFile(
            "app/src/main/java/dev/amenhancer/module/ModuleApplication.kt",
        )
        val injector = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsInjector.kt",
        )

        assertTrue(application.contains("UsbBitPerfectSettingsInjector.register(this)"))
        assertTrue(injector.contains("text = \"音频\""))
        assertTrue(injector.contains("text = \"USB Bit-Perfect\""))
        assertTrue(injector.contains("UsbBitPerfectSettingsActivity::class.java"))
        assertTrue(injector.contains("AudioTrack → Mixer → USB DAC"))
        assertFalse(injector.contains("UsbBitPerfectStatusRequester"))
        assertFalse(injector.contains("Switch("))
    }

    @Test
    fun `detail page places USB Direct and fallback switches above the live path`() {
        val activity = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsActivity.kt",
        )
        val manifest = projectFile("app/src/main/AndroidManifest.xml")

        val togglePosition = activity.indexOf("addView(toggleCard())")
        val pathPosition = activity.indexOf("addView(audioPathCard())")
        assertTrue(togglePosition >= 0)
        assertTrue(pathPosition > togglePosition)
        assertTrue(activity.contains("text = \"启用 USB 音频增强\""))
        assertTrue(activity.contains("text = \"实验性 USB 直通独占\""))
        assertTrue(activity.contains("usbDirectUacEnabled = enabled"))
        assertTrue(activity.contains("UsbDirectPermissionActivity.requestCurrentDevice"))
        assertTrue(activity.contains("text = \"实验性 AAudio 独占回退\""))
        assertTrue(activity.contains("usbExclusiveAaudioEnabled = enabled"))
        assertTrue(activity.contains("text = \"音频链路\""))
        assertTrue(activity.contains("pathNode(\"Apple Music AudioTrack\""))
        assertTrue(activity.contains("pathNode(\"输出引擎\""))
        assertTrue(activity.contains("pathNode(\"USB DAC\""))
        assertTrue(activity.contains("UsbBitPerfectStatusRequester(this)"))
        assertTrue(activity.contains("优先 USB Direct UAC"))
        assertTrue(manifest.contains(".ui.UsbBitPerfectSettingsActivity"))
        assertTrue(manifest.contains(".usb.UsbDirectPermissionActivity"))
        assertTrue(manifest.contains("android.hardware.usb.action.USB_DEVICE_ATTACHED"))
    }
}
