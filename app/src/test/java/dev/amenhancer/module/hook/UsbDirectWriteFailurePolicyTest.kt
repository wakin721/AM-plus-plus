package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDirectWriteFailurePolicyTest {
    @Test
    fun `active session failure resumes original AudioTrack`() {
        assertTrue(UsbDirectWriteFailurePolicy.shouldResumeOriginalTrack(true))
    }

    @Test
    fun `transport cancellation never resumes a paused AudioTrack`() {
        assertFalse(UsbDirectWriteFailurePolicy.shouldResumeOriginalTrack(false))
    }
}
