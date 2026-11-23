package com.rembrowser.rembrowse.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a page shown behind the window (Direct page rendering) is put under a copy of the window:
 * the window's own pixels stay on top, its hole shows the page.
 */
class ScreenshotPixelsTest {

    private val chrome = 0xFF14161B.toInt()
    private val findBar = 0xFF242832.toInt()
    private val pagePixel = 0xFFF7F5F2.toInt()
    private val paneBackground = 0xFF0F1115.toInt()

    @Test
    fun theHole_isTransparent_orOpaqueBlackFromACopyWithoutAlpha() {
        assertTrue(ScreenshotPixels.isHole(0x00000000))
        assertTrue("Any fully transparent pixel", ScreenshotPixels.isHole(0x00FFFFFF))
        assertTrue(ScreenshotPixels.isHole(0xFF000000.toInt()))
        assertFalse("The chrome's darkest colour", ScreenshotPixels.isHole(paneBackground))
        assertFalse(ScreenshotPixels.isHole(chrome))
        assertFalse("Half see-through is blended, not a hole", ScreenshotPixels.isHole(0x80FFFFFF.toInt()))
    }

    @Test
    fun windowPixels_stayOnTop_andTheHoleShowsThePage() {
        // The find bar over the page, the hole, and a rounded corner painted over in the chrome's colour
        val row = intArrayOf(findBar, 0x00000000, 0x00000000, chrome)
        val page = IntArray(4) { pagePixel }
        ScreenshotPixels.mergeRow(row, page, paneBackground)
        assertArrayEquals(intArrayOf(findBar, pagePixel, pagePixel, chrome), row)
    }

    @Test
    fun anOpaqueBlackHole_showsThePageToo() {
        val row = intArrayOf(0xFF000000.toInt(), findBar)
        ScreenshotPixels.mergeRow(row, intArrayOf(pagePixel, pagePixel), paneBackground)
        assertArrayEquals(intArrayOf(pagePixel, findBar), row)
    }

    @Test
    fun aPageThatCouldNotBeCaptured_showsThePanesOwnBackground() {
        val row = intArrayOf(0x00000000, findBar)
        ScreenshotPixels.mergeRow(row, null, paneBackground)
        assertArrayEquals(intArrayOf(paneBackground, findBar), row)
    }

    @Test
    fun onlyThePagesSpanOfTheRowIsTouched() {
        val row = intArrayOf(0x00000000, 0x00000000, 0x00000000)
        ScreenshotPixels.mergeRow(row, IntArray(3) { pagePixel }, paneBackground, from = 1, until = 2)
        assertArrayEquals(intArrayOf(0x00000000, pagePixel, 0x00000000), row)
    }

    @Test
    fun aRoundedCornersSoftEdge_isBlendedOverThePage() {
        // Half of white over black is mid grey, opaque
        assertEquals(0xFF808080.toInt(), ScreenshotPixels.over(0x80FFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals(chrome, ScreenshotPixels.over(chrome, pagePixel))
        assertEquals(pagePixel, ScreenshotPixels.over(0x00123456, pagePixel))
        // A page pixel is always taken as opaque
        val row = intArrayOf(0x00000000)
        ScreenshotPixels.mergeRow(row, intArrayOf(0x00F7F5F2), paneBackground)
        assertArrayEquals(intArrayOf(pagePixel), row)
    }

    @Test
    fun whateverIsLeftSeeThrough_isMadeOpaque() {
        val row = intArrayOf(0x00000000, chrome, 0x80FFFFFF.toInt())
        ScreenshotPixels.flattenRow(row, 0xFF000000.toInt())
        assertArrayEquals(intArrayOf(0xFF000000.toInt(), chrome, 0xFF808080.toInt()), row)
    }
}
