package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBitPerfectSettingsUiStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `registers an opt-in audio card without changing Apple Music UI`() {
        val application = projectFile(
            "app/src/main/java/dev/amenhancer/module/ModuleApplication.kt",
        )
        val injector = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsInjector.kt",
        )

        assertTrue(application.contains("UsbBitPerfectSettingsInjector.register(this)"))
        assertTrue(injector.contains("text = \"音频\""))
        assertTrue(injector.contains("text = \"USB Bit-Perfect\""))
        assertTrue(injector.contains("store.settings().usbBitPerfectEnabled"))
        assertTrue(injector.contains("usbBitPerfectEnabled = enabled"))
        assertTrue(injector.contains("Android 14+ · USB DAC"))
    }
}
