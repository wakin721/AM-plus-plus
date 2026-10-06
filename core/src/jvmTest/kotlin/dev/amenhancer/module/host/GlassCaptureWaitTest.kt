package dev.amenhancer.module.host

import org.junit.Assert.*
import org.junit.Test

class GlassCaptureWaitTest {
    @Test fun missingResumeCaptureTimesOutAndDoesNotRestartEveryFrame() {
        val wait = GlassCaptureWait()
        assertFalse(wait.timedOut(1000, true))
        assertFalse(wait.timedOut(1749, true))
        assertTrue(wait.timedOut(1750, true))
        assertTrue(wait.timedOut(1766, true))
    }

    @Test fun completedCaptureAndHiddenGlassAllowASeparateLaterResume() {
        val wait = GlassCaptureWait()
        assertFalse(wait.timedOut(1000, true))
        assertFalse(wait.timedOut(1016, false))
        assertFalse(wait.timedOut(100000, true))
        assertFalse(wait.timedOut(100016, false))
        assertFalse(wait.timedOut(200000, true))
        assertTrue(wait.timedOut(200750, true))
    }
}
