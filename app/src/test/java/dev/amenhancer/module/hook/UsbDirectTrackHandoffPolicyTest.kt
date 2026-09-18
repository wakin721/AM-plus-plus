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
}
