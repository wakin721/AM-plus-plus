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
    fun `settings distinguishes bit perfect and experimental exclusive activation`() {
        val ui = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsActivity.kt",
        )

        val togglePosition = ui.indexOf("addView(toggleCard())")
        val pathPosition = ui.indexOf("addView(audioPathCard())")
        assertTrue(togglePosition >= 0)
        assertTrue(pathPosition > togglePosition)
        assertTrue(ui.contains("text = \"启用 USB Bit-Perfect\""))
        assertTrue(ui.contains("text = \"实验性 AAudio 独占输出\""))
        assertTrue(ui.contains("text = \"音频链路\""))
        assertTrue(ui.contains("pathNode(\"Apple Music AudioTrack\""))
        assertTrue(ui.contains("pathNode(\"Android 输出路径\""))
        assertTrue(ui.contains("pathNode(\"USB DAC\""))
        assertTrue(ui.contains("text = \"刷新状态\""))
        assertTrue(ui.contains("STATE_ACTIVE -> \"Bit-Perfect 已激活\""))
        assertTrue(ui.contains("STATE_EXCLUSIVE_ACTIVE -> \"AAudio 独占已激活\""))
        assertTrue(ui.contains("STATE_EXCLUSIVE_FALLBACK -> \"独占失败，已回退\""))
        assertTrue(ui.contains("STATE_FORMAT_UNSUPPORTED -> \"格式不匹配\""))
        assertTrue(ui.contains("STATE_REQUEST_FAILED -> \"请求失败\""))
        assertTrue(ui.contains("AAUDIO EXCLUSIVE"))
        assertTrue(ui.contains("BIT_PERFECT 已核验"))
        assertTrue(ui.contains("等待重启 Apple Music"))
    }
}
