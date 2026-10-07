package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UsbBitPerfectSettingsUiStructuralRegressionTest {
    private fun source(name: String): String = sequenceOf(File("app/src/main/java/dev/amenhancer/module/$name"),
        File("../app/src/main/java/dev/amenhancer/module/$name")).first(File::isFile).readText()

    @Test fun `USB controls live in the host navigation instead of a module activity`() {
        val main = source("ui/EmbeddedSettingsPages.kt")
        val screen = source("ui/EmbeddedSettingsScreen.kt")
        assertTrue(main.contains("USB 音频输出"))
        assertTrue(screen.contains("navigation.openUsb()"))
        assertTrue(screen.contains("renderEmbeddedUsbPage("))
        assertFalse(screen.contains("startActivity"))
    }

    @Test fun `USB options use shared settings and the controller authorization facade`() {
        val page = source("ui/EmbeddedUsbSettingsPage.kt")
        assertTrue(page.contains("usbBitPerfectEnabled = it"))
        assertTrue(page.contains("usbDirectUacEnabled = enabled"))
        assertTrue(page.contains("controller.requestUsbPermission()"))
        assertTrue(page.contains("settings.usbBitPerfectEnabled && settings.usbDirectUacEnabled"))
        assertTrue(page.contains("controller.usbStatus()"))
        assertFalse(page.contains("UsbBitPerfectStatusRequester"))
        assertFalse(page.contains("AAudio"))
    }

    @Test fun `buffer settings retain the supported range and transfer presets`() {
        val page = source("ui/EmbeddedUsbSettingsPage.kt")
        assertTrue(page.contains("ModuleSettings.MIN_USB_DIRECT_PCM_BUFFER_MS"))
        assertTrue(page.contains("ModuleSettings.MAX_USB_DIRECT_PCM_BUFFER_MS"))
        assertTrue(page.contains("ModuleSettings.USB_DIRECT_PCM_BUFFER_STEP_MS"))
        assertTrue(page.contains("ModuleSettings.USB_DIRECT_TRANSFER_BUFFER_PRESETS_MS"))
        assertTrue(page.contains("usbDirectPcmBufferMs = pcmValues[index]"))
        assertTrue(page.contains("usbDirectTransferBufferMs = transferValues[index]"))
    }

    @Test fun `copy explains compatible devices feedback and system fallback`() {
        val page = source("ui/EmbeddedUsbSettingsPage.kt")
        assertTrue(page.contains("UAC1/UAC2"))
        assertTrue(page.contains("隐式或显式反馈"))
        assertTrue(page.contains("保留 Android 系统输出"))
        assertTrue(page.contains("更改缓冲参数后需重启 Apple Music"))
    }
}
