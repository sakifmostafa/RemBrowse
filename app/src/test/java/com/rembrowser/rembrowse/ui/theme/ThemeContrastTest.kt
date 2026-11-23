package com.rembrowser.rembrowse.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.ui.components.CaptureScrimAlpha
import com.rembrowser.rembrowse.ui.components.pinnedShelfColors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks every theme (light and dark, all six accents) against WCAG 2.x contrast minimums:
 * 4.5:1 for text and 3:1 for meaningful non-text marks such as control outlines.
 */
class ThemeContrastTest {

    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.luminance().toDouble()
        val b = background.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrast(foreground, background)
        assertTrue("$label is ${"%.2f".format(ratio)}:1, needs $minimum:1", ratio >= minimum)
    }

    private fun forEveryTheme(block: (name: String, scheme: ColorScheme) -> Unit) {
        for (dark in listOf(true, false)) {
            for (accent in AccentColor.entries) {
                block("${if (dark) "dark" else "light"}/${accent.name}", remColorScheme(dark, accent))
            }
        }
    }

    @Test
    fun primaryAndSecondaryText_meetAaOnEverySurface() = forEveryTheme { name, c ->
        val surfaces = mapOf(
            "background" to c.background,
            "surface" to c.surface,
            "surfaceContainerLowest" to c.surfaceContainerLowest,
            "surfaceContainerLow" to c.surfaceContainerLow,
            "surfaceContainerHigh" to c.surfaceContainerHigh,
            "surfaceContainerHighest" to c.surfaceContainerHighest
        )
        surfaces.forEach { (surfaceName, surface) ->
            assertContrast("$name onSurface on $surfaceName", c.onSurface, surface, 4.5)
            assertContrast("$name onSurfaceVariant on $surfaceName", c.onSurfaceVariant, surface, 4.5)
        }
    }

    @Test
    fun accentTextAndIcons_meetAaOnChromeAndRaisedSurfaces() = forEveryTheme { name, c ->
        assertContrast("$name primary on background", c.primary, c.background, 4.5)
        assertContrast("$name primary on surface", c.primary, c.surface, 4.5)
        assertContrast("$name primary on surfaceContainerLowest", c.primary, c.surfaceContainerLowest, 4.5)
        assertContrast("$name primary on surfaceContainerHigh", c.primary, c.surfaceContainerHigh, 4.5)
    }

    @Test
    fun filledAccentAndErrorColours_keepTheirLabelsReadable() = forEveryTheme { name, c ->
        assertContrast("$name onPrimaryContainer on primaryContainer", c.onPrimaryContainer, c.primaryContainer, 4.5)
        assertContrast("$name onPrimary on primary", c.onPrimary, c.primary, 4.5)
        assertContrast("$name error on surface", c.error, c.surface, 4.5)
        assertContrast("$name error on background", c.error, c.background, 4.5)
        assertContrast("$name onErrorContainer on errorContainer", c.onErrorContainer, c.errorContainer, 4.5)
    }

    @Test
    fun controlOutlinesAndFocusIndicators_meetNonTextContrast() = forEveryTheme { name, c ->
        assertContrast("$name outline on background", c.outline, c.background, 3.0)
        assertContrast("$name outline on surface", c.outline, c.surface, 3.0)
        // A selected row is a primaryContainer fill with a primary outline. One of the two must
        // stand out from the surface around it, so the selection never rests on a faint fill.
        for ((surfaceName, surface) in listOf("background" to c.background, "surface" to c.surface)) {
            val strongest = maxOf(contrast(c.primaryContainer, surface), contrast(c.primary, surface))
            assertTrue(
                "$name selection on $surfaceName is ${"%.2f".format(strongest)}:1, needs 3.0:1",
                strongest >= 3.0
            )
        }
    }

    @Test
    fun pinnedShelf_iconsAndOpenDotStayClearOfTheShelf() = forEveryTheme { name, c ->
        val shelf = pinnedShelfColors(c)
        // A site without a favicon is its letter, so text contrast applies
        assertContrast("$name pinned site icon on the shelf", shelf.icon, shelf.container, 4.5)
        assertContrast("$name open dot on the shelf", shelf.openDot, shelf.container, 4.5)
    }

    @Test
    fun startPageClock_staysReadableOverTheStrongestPartOfTheBackdrop() = forEveryTheme { name, c ->
        // The glow is tertiaryContainer at up to 60% over the page colour
        val glow = c.tertiaryContainer
        val base = c.surfaceContainerLowest
        val blended = Color(
            red = glow.red * 0.6f + base.red * 0.4f,
            green = glow.green * 0.6f + base.green * 0.4f,
            blue = glow.blue * 0.6f + base.blue * 0.4f
        )
        assertContrast("$name clock on backdrop", c.onSurface, blended, 4.5)
        assertContrast("$name date hint on backdrop", c.onSurfaceVariant, blended, 4.5)
    }

    @Test
    fun screenshotSelectionOutline_standsOutFromAnyDimmedPage() = forEveryTheme { name, c ->
        // The outline is the accent with a hairline of its own contrast colour beside it, drawn on
        // the page dimmed by the overlay's scrim. Whatever the page, one of the two must stand out.
        val pages = (0..255 step 15).map { Color(it, it, it) } +
            listOf(Color(0xFF2F5AA8), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFFEB3B), Color(0xFF6C2038))
        for (page in pages) {
            val keep = 1f - CaptureScrimAlpha
            val dimmed = Color(red = page.red * keep, green = page.green * keep, blue = page.blue * keep)
            val strongest = maxOf(contrast(c.primary, dimmed), contrast(c.onPrimary, dimmed))
            assertTrue(
                "$name outline on a dimmed $page is ${"%.2f".format(strongest)}:1, needs 3.0:1",
                strongest >= 3.0
            )
        }
    }
}
