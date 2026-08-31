package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbExclusiveVolumePolicyTest {
    @Test
    fun `maximum media volume preserves full-scale PCM`() {
        assertEquals(1f, UsbExclusiveVolumePolicy.streamGain(15, 15, false, 0f), 0f)
    }

    @Test
    fun `mute and zero volume silence exclusive PCM`() {
        assertEquals(0f, UsbExclusiveVolumePolicy.streamGain(10, 15, true, -4f), 0f)
        assertEquals(0f, UsbExclusiveVolumePolicy.streamGain(0, 15, false, Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun `android decibel curve becomes linear PCM gain`() {
        assertEquals(0.1f, UsbExclusiveVolumePolicy.streamGain(5, 15, false, -20f), 0.0001f)
    }

    @Test
    fun `zero-decibel USB HAL falls back to the visible volume step`() {
        assertEquals(0.5f, UsbExclusiveVolumePolicy.streamGain(5, 10, false, 0f), 0.0001f)
    }

    @Test
    fun `track fade and media volume are both applied`() {
        assertEquals(0.125f, UsbExclusiveVolumePolicy.effectiveGain(0.5f, 0.25f), 0.0001f)
    }

    @Test
    fun `hot path reads cached gain without querying system volume`() {
        var refreshes = 0
        val cache = UsbExclusiveVolumeCache(1f)

        cache.refresh {
            refreshes += 1
            0.4f
        }

        repeat(100) {
            assertEquals(0.2f, cache.effectiveGain(0.5f), 0.0001f)
        }
        assertEquals(1, refreshes)
    }

    @Test
    fun `volume notification replaces the cached stream gain`() {
        val cache = UsbExclusiveVolumeCache(0.8f)

        cache.refresh { 0.25f }

        assertEquals(0.125f, cache.effectiveGain(0.5f), 0.0001f)
    }
}
