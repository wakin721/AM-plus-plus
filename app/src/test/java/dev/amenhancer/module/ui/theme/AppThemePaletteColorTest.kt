package dev.amenhancer.module.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppThemePaletteColorTest {
    @Test
    fun `manual palettes produce distinct tinted light backgrounds`() {
        val sage = AppThemePalette.SAGE.staticBackgroundColor(dark = false)
        val blue = AppThemePalette.BLUE.staticBackgroundColor(dark = false)
        val coral = AppThemePalette.CORAL.staticBackgroundColor(dark = false)

        assertNotEquals(Color.White, sage)
        assertNotEquals(sage, blue)
        assertNotEquals(blue, coral)
    }

    @Test
    fun `manual palettes produce distinct tinted dark backgrounds`() {
        val sage = AppThemePalette.SAGE.staticBackgroundColor(dark = true)
        val violet = AppThemePalette.VIOLET.staticBackgroundColor(dark = true)

        assertNotEquals(Color.Black, sage)
        assertNotEquals(sage, violet)
    }
}
