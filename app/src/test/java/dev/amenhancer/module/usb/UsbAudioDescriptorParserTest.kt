package dev.amenhancer.module.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioDescriptorParserTest {
    @Test
    fun `parses and selects UAC1 adaptive 24 bit 48k output`() {
        val raw = byteArrayOf(
            // Interface 1 alt 1: AudioStreaming, UAC1, one endpoint.
            9, 0x04, 1, 1, 1, 0x01, 0x02, 0x00, 0,
            // AS_GENERAL, PCM terminal link 1.
            7, 0x24, 0x01, 1, 1, 0x01, 0x00,
            // FORMAT_TYPE_I: stereo, 3-byte subslot, 24-bit, one rate 48000.
            11, 0x24, 0x02, 0x01, 2, 3, 24, 1, 0x80.toByte(), 0xbb.toByte(), 0x00,
            // ISO OUT endpoint, adaptive, 288 bytes per 1ms service interval.
            9, 0x05, 0x01, 0x09, 0x20, 0x01, 1, 0, 0,
        )

        val alternatives = UsbAudioDescriptorParser.parse(raw)
        assertEquals(1, alternatives.size)
        val selected = UsbAudioDescriptorParser.select(
            alternatives,
            sampleRate = 48_000,
            channels = 2,
            preferredBits = 24,
        )
        requireNotNull(selected)
        assertEquals(1, selected.interfaceNumber)
        assertEquals(1, selected.alternateSetting)
        assertEquals(24, selected.bitResolution)
        assertEquals(3, selected.subslotBytes)
        assertEquals(0x01, selected.endpointAddress)
        assertEquals(288, selected.maxPacketSize)
        assertEquals(UsbAudioDescriptorParser.SYNC_ADAPTIVE, selected.synchronizationType)
        assertTrue(selected.supportsSampleRate(48_000))
    }

    @Test
    fun `rejects asynchronous endpoint until feedback pacing is implemented`() {
        val raw = byteArrayOf(
            9, 0x04, 1, 1, 1, 0x01, 0x02, 0x00, 0,
            7, 0x24, 0x01, 1, 1, 0x01, 0x00,
            11, 0x24, 0x02, 0x01, 2, 3, 24, 1, 0x80.toByte(), 0xbb.toByte(), 0x00,
            // ISO OUT endpoint, asynchronous synchronization type.
            9, 0x05, 0x01, 0x05, 0x20, 0x01, 1, 0, 0,
        )

        val alternatives = UsbAudioDescriptorParser.parse(raw)
        assertEquals(1, alternatives.size)
        assertTrue(alternatives.single().requiresExplicitFeedback)
        assertNull(
            UsbAudioDescriptorParser.select(
                alternatives,
                sampleRate = 48_000,
                channels = 2,
                preferredBits = 24,
            ),
        )
    }

    @Test
    fun `does not select a discrete UAC1 rate that the alternate setting lacks`() {
        val raw = byteArrayOf(
            9, 0x04, 2, 1, 1, 0x01, 0x02, 0x00, 0,
            7, 0x24, 0x01, 1, 1, 0x01, 0x00,
            11, 0x24, 0x02, 0x01, 2, 2, 16, 1, 0x44, 0xac.toByte(), 0x00,
            9, 0x05, 0x02, 0x0d, 0xc0.toByte(), 0x00, 1, 0, 0,
        )

        val alternatives = UsbAudioDescriptorParser.parse(raw)
        assertTrue(alternatives.single().supportsSampleRate(44_100))
        assertNull(
            UsbAudioDescriptorParser.select(
                alternatives,
                sampleRate = 48_000,
                channels = 2,
                preferredBits = 16,
            ),
        )
    }
}
