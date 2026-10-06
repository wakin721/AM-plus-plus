package dev.amenhancer.module.host

import org.junit.Assert.*
import org.junit.Test

class OwnedHostClippingTest {
    private class Window {
        var visibility = 4
        var alpha = 0.5f
        var width = 600
        var clipChildren = true
        var clipPadding = true
        val clipping = OwnedHostClipping(
            { clipChildren }, { clipChildren = it },
            { clipPadding }, { clipPadding = it },
        )
    }

    @Test fun settingsExitAfterResumeKeepsNativeWindowVisible() {
        val window = Window()
        window.clipping.allowOverflow()
        // Resume changes native window properties before another module frame.
        window.visibility = 0
        window.alpha = 1f
        window.width = 1080
        window.clipping.allowOverflow()
        window.clipping.close()
        assertEquals(0, window.visibility)
        assertEquals(1f, window.alpha, 0f)
        assertEquals(1080, window.width)
        assertTrue(window.clipChildren)
        assertTrue(window.clipPadding)
    }

    @Test fun releasePreservesLaterNativeClippingAndRepeatedCloseIsInert() {
        val window = Window()
        window.clipPadding = false
        window.clipping.allowOverflow()
        window.clipChildren = true
        window.clipping.close()
        assertTrue(window.clipChildren)
        assertFalse(window.clipPadding)
        window.clipChildren = false
        window.clipping.close()
        assertFalse(window.clipChildren)
    }
}
