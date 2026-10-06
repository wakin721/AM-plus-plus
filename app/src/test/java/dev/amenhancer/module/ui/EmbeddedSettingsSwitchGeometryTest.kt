package dev.amenhancer.module.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSettingsSwitchGeometryTest {
    @Test
    fun `animated thumb remains inside the track throughout forward and reverse motion`() {
        for (density in listOf(1f, 2.625f, 4f)) {
            for ((width, height) in listOf(56f to 44f, 48f to 44f, 56f to 30f)) {
                for (step in (0..100) + (100 downTo 0)) {
                    val progress = step / 100f
                    val ltr = embeddedSwitchGeometry(width * density, height * density, density, progress, false)
                    val rtl = embeddedSwitchGeometry(width * density, height * density, density, progress, true)
                    assertTrue(ltr.thumbX - ltr.thumbRadius > ltr.left)
                    assertTrue(ltr.thumbX + ltr.thumbRadius < ltr.right)
                    assertTrue(ltr.thumbY - ltr.thumbRadius > ltr.top)
                    assertTrue(ltr.thumbY + ltr.thumbRadius < ltr.bottom)
                    assertEquals(width * density, ltr.thumbX + rtl.thumbX, 0.001f)
                    assertEquals(ltr.thumbRadius, rtl.thumbRadius, 0.001f)
                }
            }
        }
    }

    @Test
    fun `out of range animation values clamp to the checked and unchecked endpoints`() {
        assertEquals(embeddedSwitchGeometry(56f, 44f, 1f, false, false),
            embeddedSwitchGeometry(56f, 44f, 1f, -0.1f, false))
        assertEquals(embeddedSwitchGeometry(56f, 44f, 1f, true, true),
            embeddedSwitchGeometry(56f, 44f, 1f, 1.1f, true))
    }

    @Test
    fun `thumb stays circular and inset at both ends across display densities`() {
        for (density in listOf(1f, 1.5f, 2.625f, 3f, 4f)) {
            for (checked in listOf(false, true)) {
                val shape = embeddedSwitchGeometry(56f * density, 44f * density, density, checked, false)
                val radius = if (checked) 13f else 8.5f
                val inset = 17f - radius
                assertEquals(radius * density, shape.thumbRadius, 0.001f)
                assertEquals(inset * density, shape.thumbY - shape.thumbRadius - shape.top, 0.001f)
                assertEquals(inset * density, shape.bottom - shape.thumbY - shape.thumbRadius, 0.001f)
                val endInset = if (checked) {
                    shape.right - shape.thumbX - shape.thumbRadius
                } else {
                    shape.thumbX - shape.thumbRadius - shape.left
                }
                assertEquals(inset * density, endInset, 0.001f)
            }
        }
    }

    @Test
    fun `RTL mirrors the checked position without changing the track`() {
        for (checked in listOf(false, true)) {
            val ltr = embeddedSwitchGeometry(56f, 44f, 1f, checked, false)
            val rtl = embeddedSwitchGeometry(56f, 44f, 1f, checked, true)
            assertEquals(56f, ltr.thumbX + rtl.thumbX, 0.001f)
            assertEquals(ltr.thumbRadius, rtl.thumbRadius, 0.001f)
            assertEquals(ltr.top, rtl.top, 0.001f)
            assertEquals(ltr.bottom, rtl.bottom, 0.001f)
        }
    }

    @Test
    fun `extra height or constrained width does not stretch the thumb or clip the track`() {
        for ((width, height) in listOf(56f to 88f, 48f to 44f, 112f to 44f, 56f to 30f)) {
            for (checked in listOf(false, true)) {
                val shape = embeddedSwitchGeometry(width, height, 1f, checked, false)
                assertTrue(shape.left >= 0f && shape.right <= width)
                assertTrue(shape.top >= 0f && shape.bottom <= height)
                assertTrue(shape.thumbX - shape.thumbRadius > shape.left)
                assertTrue(shape.thumbX + shape.thumbRadius < shape.right)
                assertTrue(shape.thumbY - shape.thumbRadius > shape.top)
                assertTrue(shape.thumbY + shape.thumbRadius < shape.bottom)
                val radius = if (checked) 13f else 8.5f
                assertEquals((shape.bottom - shape.top) * radius / 34f, shape.thumbRadius, 0.001f)
            }
        }
    }
}
