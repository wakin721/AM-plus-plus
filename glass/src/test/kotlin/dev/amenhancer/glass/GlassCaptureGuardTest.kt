package dev.amenhancer.glass

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class GlassCaptureGuardTest {
    @Test fun failedNestedCaptureCannotLeaveTheHostOrOuterCaptureBlank() {
        assertFalse(GlassCaptureGuard.active)
        GlassCaptureGuard.capture {
            runCatching { GlassCaptureGuard.capture { error("source draw failed") } }
            assertTrue(GlassCaptureGuard.active)
        }
        assertFalse(GlassCaptureGuard.active)
    }

    @Test fun captureFailureRestoresNormalRenderingForTheNextFrame() {
        runCatching { GlassCaptureGuard.capture { error("hardware bitmap unavailable") } }
        assertFalse(GlassCaptureGuard.active)
        assertEquals("fresh frame", GlassCaptureGuard.capture { "fresh frame" })
        assertFalse(GlassCaptureGuard.active)
    }

    @Test fun anotherThreadDoesNotLoseItsGlassDuringSourceRecording() {
        val other = AtomicBoolean(true)
        GlassCaptureGuard.capture {
            val thread = Thread { other.set(GlassCaptureGuard.active) }
            thread.start(); thread.join()
            assertTrue(GlassCaptureGuard.active)
        }
        assertFalse(other.get())
    }
}
