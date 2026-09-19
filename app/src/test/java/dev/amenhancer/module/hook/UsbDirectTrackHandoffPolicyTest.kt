package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDirectTrackHandoffPolicyTest {
    @Test
    fun `pause suspends flush only clears buffers and stop or release close`() {
        assertEquals(
            UsbDirectTrackHandoffAction.SUSPEND,
            UsbDirectTrackHandoffPolicy.actionFor("pause"),
        )
        assertEquals(
            UsbDirectTrackHandoffAction.FLUSH,
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
    fun `fresh owner is protected during startup grace before its first direct PCM write`() {
        assertFalse(
            UsbDirectTrackHandoffPolicy.isIdleOwner(
                ownerStartedRealtimeNanos = 4_000_000_000L,
                lastWriteRealtimeNanos = 0L,
                nowRealtimeNanos = 5_000_000_000L,
                idleThresholdNanos = 1_000_000_000L,
                startupGraceNanos = 2_000_000_000L,
            ),
        )
        assertTrue(
            UsbDirectTrackHandoffPolicy.isIdleOwner(
                ownerStartedRealtimeNanos = 3_000_000_000L,
                lastWriteRealtimeNanos = 0L,
                nowRealtimeNanos = 5_000_000_000L,
                idleThresholdNanos = 1_000_000_000L,
                startupGraceNanos = 2_000_000_000L,
            ),
        )
    }

    @Test
    fun `owner with direct PCM becomes idle only after the normal threshold`() {
        assertFalse(
            UsbDirectTrackHandoffPolicy.isIdleOwner(
                ownerStartedRealtimeNanos = 1_000_000_000L,
                lastWriteRealtimeNanos = 4_500_000_000L,
                nowRealtimeNanos = 5_000_000_000L,
                idleThresholdNanos = 1_000_000_000L,
                startupGraceNanos = 2_000_000_000L,
            ),
        )
        assertTrue(
            UsbDirectTrackHandoffPolicy.isIdleOwner(
                ownerStartedRealtimeNanos = 1_000_000_000L,
                lastWriteRealtimeNanos = 4_000_000_000L,
                nowRealtimeNanos = 5_000_000_000L,
                idleThresholdNanos = 1_000_000_000L,
                startupGraceNanos = 2_000_000_000L,
            ),
        )
    }

}
