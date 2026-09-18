package dev.amenhancer.module.config

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDirectBufferSettingsTest {
    @Test
    fun `USB Direct buffer settings have compatibility defaults`() {
        val decoded = ModuleSettingsSchema.decode(emptyMap<String, Any?>())

        assertEquals(73, decoded.usbDirectPcmBufferMs)
        assertEquals(0, decoded.usbDirectTransferBufferMs)
    }

    @Test
    fun `USB Direct buffer presets round trip through ordinary settings`() {
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(
                usbDirectPcmBufferMs = 73,
                usbDirectTransferBufferMs = 8,
            ),
        )

        assertEquals(73, encoded["usb_direct_pcm_buffer_ms"])
        assertEquals(8, encoded["usb_direct_transfer_buffer_ms"])

        val decoded = ModuleSettingsSchema.decode(encoded)
        assertEquals(100, decoded.usbDirectPcmBufferMs)
        assertEquals(8, decoded.usbDirectTransferBufferMs)
    }

    @Test
    fun `PCM buffer is clamped to the 10 through 100 millisecond range`() {
        assertEquals(
            10,
            ModuleSettingsSchema.decode(
                mapOf("usb_direct_pcm_buffer_ms" to 9),
            ).usbDirectPcmBufferMs,
        )
        assertEquals(
            10,
            ModuleSettingsSchema.decode(
                mapOf("usb_direct_pcm_buffer_ms" to 10),
            ).usbDirectPcmBufferMs,
        )
        assertEquals(
            73,
            ModuleSettingsSchema.decode(
                mapOf("usb_direct_pcm_buffer_ms" to 73),
            ).usbDirectPcmBufferMs,
        )
        assertEquals(
            100,
            ModuleSettingsSchema.decode(
                mapOf("usb_direct_pcm_buffer_ms" to 100),
            ).usbDirectPcmBufferMs,
        )
        assertEquals(
            100,
            ModuleSettingsSchema.decode(
                mapOf("usb_direct_pcm_buffer_ms" to 101),
            ).usbDirectPcmBufferMs,
        )
    }

    @Test
    fun `unsupported USB transfer buffer values still fall back to auto`() {
        val decoded = ModuleSettingsSchema.decode(
            mapOf("usb_direct_transfer_buffer_ms" to 3),
        )

        assertEquals(0, decoded.usbDirectTransferBufferMs)
    }

    @Test
    fun `standalone USB settings synchronization carries buffer presets`() {
        val values = ModuleSettingsSchema.usbDirectSettingsValues(
            mapOf(
                "usb_bit_perfect_enabled" to true,
                "usb_direct_uac_enabled" to true,
                "usb_direct_pcm_buffer_ms" to 37,
                "usb_direct_transfer_buffer_ms" to 4,
            ),
        )

        assertEquals(37, values["usb_direct_pcm_buffer_ms"])
        assertEquals(4, values["usb_direct_transfer_buffer_ms"])
    }
}
