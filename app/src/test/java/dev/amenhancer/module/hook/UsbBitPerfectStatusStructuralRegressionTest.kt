package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBitPerfectStatusStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `live status requires actual playing USB route and verified preferred mixer`() {
        val hook = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbBitPerfectFeature.kt",
        )

        assertTrue(hook.contains("track.playState"))
        assertTrue(hook.contains("AudioTrack.PLAYSTATE_PLAYING"))
        assertTrue(hook.contains("track.routedDevice"))
        assertTrue(hook.contains("manager.getPreferredMixerAttributes(attributes, routed)"))
        assertTrue(hook.contains("AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT"))
        assertTrue(hook.contains("preferred.format.matchesExactly(trackFormat)"))
        assertTrue(hook.contains("STATE_ACTIVE"))
        assertFalse(hook.contains("SharedPreferences"))
    }

    @Test
    fun `status query is package scoped and signature protected`() {
        val requester = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectStatusRequester.kt",
        )
        val hook = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/UsbBitPerfectFeature.kt",
        )
        val protocol = projectFile(
            "app/src/main/java/dev/amenhancer/module/UsbBitPerfectStatusProtocol.kt",
        )
        val manifest = projectFile("app/src/main/AndroidManifest.xml")

        assertTrue(requester.contains("setPackage(ModuleConstants.TARGET_PACKAGE)"))
        assertTrue(requester.contains("ResultReceiver"))
        assertTrue(requester.contains("TIMEOUT_MILLIS"))
        assertTrue(hook.contains("UsbBitPerfectStatusProtocol.REQUEST_PERMISSION"))
        assertTrue(protocol.contains("REQUEST_USB_BIT_PERFECT_STATUS"))
        assertTrue(manifest.contains("REQUEST_USB_BIT_PERFECT_STATUS"))
        assertTrue(manifest.contains("android:protectionLevel=\"signature\""))
    }

    @Test
    fun `settings distinguishes enabled configuration from live activation`() {
        val ui = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsInjector.kt",
        )

        assertTrue(ui.contains("text = \"运行状态\""))
        assertTrue(ui.contains("text = \"刷新状态\""))
        assertTrue(ui.contains("STATE_ACTIVE -> \"已激活\""))
        assertTrue(ui.contains("STATE_FORMAT_UNSUPPORTED -> \"格式不匹配\""))
        assertTrue(ui.contains("STATE_REQUEST_FAILED -> \"请求失败\""))
        assertTrue(ui.contains("USB Mixer："))
        assertTrue(ui.contains("Apple Music："))
        assertTrue(ui.contains("等待重启 Apple Music"))
    }
}
