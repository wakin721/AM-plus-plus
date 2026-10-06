package dev.amenhancer.module.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSettingsNavigationTest {
    @Test
    fun `Back from lyrics returns to AM and the next Back returns to native settings`() {
        val navigation = EmbeddedSettingsNavigation()
        navigation.openLyrics()
        assertEquals(EmbeddedSettingsPage.CUSTOM_LYRICS, navigation.page)
        assertTrue(navigation.back())
        assertEquals(EmbeddedSettingsPage.MAIN, navigation.page)
        assertFalse(navigation.back())
        navigation.openLyrics()
        assertTrue(navigation.back())
    }
}
