package dev.amenhancer.module.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytes(vararg values: Int): ByteArray =
    ByteArray(values.size) { index -> values[index].toByte() }

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
    fun `selects UAC1 asynchronous output when explicit feedback is linked`() {
        val raw = bytes(
            9, 0x04, 1, 1, 2, 0x01, 0x02, 0x00, 0,
            7, 0x24, 0x01, 1, 1, 0x01, 0x00,
            11, 0x24, 0x02, 0x01, 2, 3, 24, 1, 0x80, 0xbb, 0x00,
            9, 0x05, 0x01, 0x05, 0x20, 0x01, 1, 0, 0x81,
            9, 0x05, 0x81, 0x01, 3, 0, 1, 4, 0,
        )

        val alternative = UsbAudioDescriptorParser.parse(raw).single()

        assertTrue(alternative.requiresExplicitFeedback)
        assertTrue(alternative.hasExplicitFeedback)
        assertEquals(0x81, alternative.feedbackEndpointAddress)
        assertEquals(3, alternative.feedbackMaxPacketSize)
        assertEquals(1, alternative.feedbackInterval)
        val selected = UsbAudioDescriptorParser.select(
            listOf(alternative),
            sampleRate = 48_000,
            channels = 2,
            preferredBits = 24,
        )
        assertEquals(alternative, selected)
    }

    @Test
    fun `parses UAC2 feedback usage in the same alternate setting`() {
        val raw = bytes(
            9, 0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0,
            17, 0x24, 0x02, 1, 0x01, 0x01, 0, 10, 2, 0x03, 0, 0, 0, 0, 0, 0, 0,
            9, 0x04, 1, 1, 2, 0x01, 0x02, 0x20, 0,
            16, 0x24, 0x01, 1, 0, 0x01, 0, 0, 0, 0, 2, 0x03, 0, 0, 0, 0,
            6, 0x24, 0x02, 0x01, 4, 32,
            7, 0x05, 0x01, 0x05, 0x80, 0x01, 1,
            7, 0x05, 0x81, 0x11, 4, 0, 4,
        )

        val alternative = UsbAudioDescriptorParser.parse(raw).single()

        assertEquals(0x20, alternative.protocol)
        assertTrue(alternative.hasExplicitFeedback)
        assertEquals(0x81, alternative.feedbackEndpointAddress)
        assertEquals(4, alternative.feedbackMaxPacketSize)
        assertEquals(4, alternative.feedbackInterval)
        val selected = UsbAudioDescriptorParser.select(
            listOf(alternative),
            sampleRate = 48_000,
            channels = 2,
            preferredBits = 32,
        )
        assertEquals(alternative, selected)
    }

    @Test
    fun `does not pair a UAC1 feedback endpoint with a different address`() {
        val raw = bytes(
            9, 0x04, 1, 1, 2, 0x01, 0x02, 0x00, 0,
            7, 0x24, 0x01, 1, 1, 0x01, 0x00,
            11, 0x24, 0x02, 0x01, 2, 3, 24, 1, 0x80, 0xbb, 0x00,
            9, 0x05, 0x01, 0x05, 0x20, 0x01, 1, 0, 0x82,
            9, 0x05, 0x81, 0x01, 3, 0, 1, 4, 0,
        )

        val alternative = UsbAudioDescriptorParser.parse(raw).single()

        assertTrue(alternative.requiresExplicitFeedback)
        assertFalse(alternative.hasExplicitFeedback)
        assertEquals(0, alternative.feedbackEndpointAddress)
    }

    @Test
    fun `does not pair UAC2 feedback from another alternate setting`() {
        val raw = bytes(
            9, 0x04, 1, 1, 1, 0x01, 0x02, 0x20, 0,
            16, 0x24, 0x01, 1, 0, 0x01, 0, 0, 0, 0, 2, 0x03, 0, 0, 0, 0,
            6, 0x24, 0x02, 0x01, 4, 32,
            7, 0x05, 0x01, 0x05, 0x80, 0x01, 1,
            9, 0x04, 1, 2, 1, 0x01, 0x02, 0x20, 0,
            7, 0x05, 0x81, 0x11, 4, 0, 4,
        )

        val first = UsbAudioDescriptorParser.parse(raw)
            .single { it.alternateSetting == 1 }

        assertFalse(first.hasExplicitFeedback)
    }

    @Test
    fun `implicit feedback usage is not treated as an audio OUT data endpoint`() {
        val raw = bytes(
            9, 0x04, 1, 1, 1, 0x01, 0x02, 0x20, 0,
            16, 0x24, 0x01, 1, 0, 0x01, 0, 0, 0, 0, 2, 0x03, 0, 0, 0, 0,
            6, 0x24, 0x02, 0x01, 4, 32,
            7, 0x05, 0x01, 0x25, 0x80, 0x01, 1,
        )

        assertTrue(UsbAudioDescriptorParser.parse(raw).isEmpty())
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
