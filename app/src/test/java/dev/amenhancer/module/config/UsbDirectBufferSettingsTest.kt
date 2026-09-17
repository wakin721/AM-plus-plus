package dev.amenhancer.module.config

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDirectBufferSettingsTest {
    @Test
    fun `USB Direct buffer settings have compatibility defaults`() {
        val decoded = ModuleSettingsSchema.decode(emptyMap<String, Any?>())

        assertEquals(500, decoded.usbDirectPcmBufferMs)
        assertEquals(0, decoded.usbDirectTransferBufferMs)
    }

    @Test
    fun `USB Direct buffer presets round trip through ordinary settings`() {
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(
                usbDirectPcmBufferMs = 100,
                usbDirectTransferBufferMs = 8,
            ),
        )

        assertEquals(100, encoded["usb_direct_pcm_buffer_ms"])
        assertEquals(8, encoded["usb_direct_transfer_buffer_ms"])

        val decoded = ModuleSettingsSchema.decode(encoded)
        assertEquals(100, decoded.usbDirectPcmBufferMs)
        assertEquals(8, decoded.usbDirectTransferBufferMs)
    }

    @Test
    fun `unsupported USB Direct buffer values fall back to safe defaults`() {
        val decoded = ModuleSettingsSchema.decode(
            mapOf(
                "usb_direct_pcm_buffer_ms" to 73,
                "usb_direct_transfer_buffer_ms" to 3,
            ),
        )

        assertEquals(500, decoded.usbDirectPcmBufferMs)
        assertEquals(0, decoded.usbDirectTransferBufferMs)
    }

    @Test
    fun `standalone USB settings synchronization carries buffer presets`() {
        val values = ModuleSettingsSchema.usbDirectSettingsValues(
            mapOf(
                "usb_bit_perfect_enabled" to true,
                "usb_direct_uac_enabled" to true,
                "usb_direct_pcm_buffer_ms" to 250,
                "usb_direct_transfer_buffer_ms" to 4,
            ),
        )

        assertEquals(250, values["usb_direct_pcm_buffer_ms"])
        assertEquals(4, values["usb_direct_transfer_buffer_ms"])
    }
}
