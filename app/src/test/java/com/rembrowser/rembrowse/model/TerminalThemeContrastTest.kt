package com.rembrowser.rembrowse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG contrast of every terminal palette: ordinary text, suggestions (ghost text) and the greys programs use. */
class TerminalThemeContrastTest {

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [foreground] drawn at [alpha] (0-255) over the opaque [background], as the view paints a suggestion. */
    private fun composite(foreground: Long, alpha: Int, background: Long): Long {
        fun mix(shift: Int): Long {
            val f = ((foreground shr shift) and 0xFF).toInt()
            val b = ((background shr shift) and 0xFF).toInt()
            return Math.round((f * alpha + b * (255 - alpha)) / 255.0)
        }
        return 0xFF000000 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun ratio(value: Double) = "%.2f".format(value)

    @Test
    fun everyPalette_hasSixteenColors_andAGhostAlphaInRange() {
        for (theme in TerminalThemePreset.entries) {
            assertEquals("${theme.name} ansi entries", 16, theme.ansi.size)
            assertTrue("${theme.name} ghostAlpha 0x${theme.ghostAlpha.toString(16)}", theme.ghostAlpha in 0x40..0xE0)
        }
    }

    @Test
    fun ordinaryText_isReadableOnEveryBackground() {
        for (theme in TerminalThemePreset.entries) {
            val r = contrast(theme.foreground, theme.background)
            assertTrue("${theme.name} foreground is ${ratio(r)}:1, needs 4.5:1", r >= 4.5)
        }
    }

    @Test
    fun suggestions_areReadableButStillClearlyGhosted() {
        for (theme in TerminalThemePreset.entries) {
            val ghost = composite(theme.foreground, theme.ghostAlpha, theme.background)
            val r = contrast(ghost, theme.background)
            assertTrue("${theme.name} suggestion is ${ratio(r)}:1, needs at least 3.0:1", r >= 3.0)
            assertTrue("${theme.name} suggestion is ${ratio(r)}:1, needs at most 7.0:1 to look suggested", r <= 7.0)
        }
    }

    @Test
    fun brightBlack_isReadableOnTheDarkThemes() {
        // Solarized Dark is published as is: its bright black (base01) is the palette's own comment grey, 2.79:1.
        val asPublished = setOf(TerminalThemePreset.SOLARIZED_DARK)
        for (theme in TerminalThemePreset.entries.filter { !it.isLight && it !in asPublished }) {
            val r = contrast(theme.ansi[8], theme.background)
            assertTrue("${theme.name} ansi[8] is ${ratio(r)}:1, needs 3.0:1", r >= 3.0)
        }
    }

    @Test
    fun onTheLightThemes_everyColorUsedForText_isReadable() {
        for (theme in TerminalThemePreset.entries.filter { it.isLight }) {
            for (index in (1..7) + (9..15)) {
                val r = contrast(theme.ansi[index], theme.background)
                assertTrue("${theme.name} ansi[$index] is ${ratio(r)}:1, needs 3.0:1", r >= 3.0)
            }
        }
    }
}
