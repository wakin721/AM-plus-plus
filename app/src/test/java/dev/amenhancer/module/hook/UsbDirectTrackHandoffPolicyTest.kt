package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDirectTrackHandoffPolicyTest {
    @Test
    fun `pause and flush suspend while stop and release close`() {
        assertEquals(
            UsbDirectTrackHandoffAction.SUSPEND,
            UsbDirectTrackHandoffPolicy.actionFor("pause"),
        )
        assertEquals(
            UsbDirectTrackHandoffAction.SUSPEND,
            UsbDirectTrackHandoffPolicy.actionFor("flush"),
        )
        assertEquals(
            UsbDirectTrackHandoffAction.CLOSE,
            UsbDirectTrackHandoffPolicy.actionFor("stop"),
        )
        assertEquals(
            UsbDirectTrackHandoffAction.CLOSE,
            UsbDirectTrackHandoffPolicy.actionFor("release"),
        )
    }

    @Test
    fun `new track replaces only a suspended or orphaned session`() {
        assertFalse(
            UsbDirectTrackHandoffPolicy.shouldHandoff(
                sameTrack = true,
                suspended = true,
                existingTrackAlive = true,
                existingTrackIdle = false,
            ),
        )
        assertFalse(
            UsbDirectTrackHandoffPolicy.shouldHandoff(
                sameTrack = false,
                suspended = false,
                existingTrackAlive = true,
                existingTrackIdle = false,
            ),
        )
        assertTrue(
            UsbDirectTrackHandoffPolicy.shouldHandoff(
                sameTrack = false,
                suspended = true,
                existingTrackAlive = true,
                existingTrackIdle = false,
            ),
        )
        assertTrue(
            UsbDirectTrackHandoffPolicy.shouldHandoff(
                sameTrack = false,
                suspended = false,
                existingTrackAlive = false,
                existingTrackIdle = false,
            ),
        )
        assertTrue(
            UsbDirectTrackHandoffPolicy.shouldHandoff(
                sameTrack = false,
                suspended = false,
                existingTrackAlive = true,
                existingTrackIdle = true,
            ),
        )
    }
    @Test
    fun `fresh owner is never considered idle before its first direct PCM write`() {
        assertFalse(
            UsbDirectTrackHandoffPolicy.isIdleOwner(
                lastWriteRealtimeNanos = 0L,
                nowRealtimeNanos = 5_000_000_000L,
                idleThresholdNanos = 1_000_000_000L,
            ),
        )
    }

    @Test
    fun `owner becomes idle only after a real direct PCM write ages past threshold`() {
        assertFalse(
            UsbDirectTrackHandoffPolicy.isIdleOwner(
                lastWriteRealtimeNanos = 4_500_000_000L,
                nowRealtimeNanos = 5_000_000_000L,
                idleThresholdNanos = 1_000_000_000L,
            ),
        )
        assertTrue(
            UsbDirectTrackHandoffPolicy.isIdleOwner(
                lastWriteRealtimeNanos = 4_000_000_000L,
                nowRealtimeNanos = 5_000_000_000L,
                idleThresholdNanos = 1_000_000_000L,
            ),
        )
    }

}
