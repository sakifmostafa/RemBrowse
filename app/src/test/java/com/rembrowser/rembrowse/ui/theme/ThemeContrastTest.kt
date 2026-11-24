package com.rembrowser.rembrowse.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.ui.components.CaptureScrimAlpha
import com.rembrowser.rembrowse.ui.components.RailScrimAlpha
import com.rembrowser.rembrowse.ui.components.floatingRailEdge
import com.rembrowser.rembrowse.ui.components.railScrimAlpha
import com.rembrowser.rembrowse.ui.components.noticeFocusRing
import com.rembrowser.rembrowse.ui.components.outlinedButtonBorderColor
import com.rembrowser.rembrowse.ui.components.outlinedButtonContentColor
import com.rembrowser.rembrowse.ui.components.pinnedShelfColors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks every theme (light and dark, all six accents) against WCAG 2.x contrast minimums:
 * 4.5:1 for text and 3:1 for meaningful non-text marks such as control outlines. The system
 * colours mapping ([remDynamicScheme]) is checked too, over Material's baseline schemes here and
 * over the device's wallpaper schemes in [DynamicSchemeContrastTest].
 */
open class ThemeContrastTest {

    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.luminance().toDouble()
        val b = background.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrast(foreground, background)
        assertTrue("$label is ${"%.2f".format(ratio)}:1, needs $minimum:1", ratio >= minimum)
    }

    /** The schemes every test below checks. */
    protected open fun schemes(): List<Pair<String, ColorScheme>> {
        val fixed = listOf(true, false).flatMap { dark ->
            AccentColor.entries.map { accent ->
                "${if (dark) "dark" else "light"}/${accent.name}" to remColorScheme(dark, accent)
            }
        }
        // System colours, without a device: the mapping over Material's own baseline schemes
        val systemColors = listOf(
            "system colors/baseline light" to remDynamicScheme(lightColorScheme(), dark = false),
            "system colors/baseline dark" to remDynamicScheme(darkColorScheme(), dark = true)
        )
        return fixed + systemColors
    }

    private fun forEveryTheme(block: (name: String, scheme: ColorScheme) -> Unit) {
        schemes().forEach { (name, scheme) -> block(name, scheme) }
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
    fun notices_textActionAndFocusRingReadOnTheInverseSurface() = forEveryTheme { name, c ->
        // NoticeSnackbar: text, icons and the close button in inverseOnSurface, the action and the
        // keyboard focus ring in inversePrimary, all on inverseSurface. An error is told by its
        // icon and words, so no error colour is needed there.
        assertContrast("$name notice text on inverseSurface", c.inverseOnSurface, c.inverseSurface, 4.5)
        assertContrast("$name notice action on inverseSurface", c.inversePrimary, c.inverseSurface, 4.5)
        // A notice sits over any page; its edge must show against the chrome and page colours
        for ((surfaceName, surface) in listOf("background" to c.background, "surfaceContainerLowest" to c.surfaceContainerLowest)) {
            assertContrast("$name notice on $surfaceName", c.inverseSurface, surface, 3.0)
        }
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
    fun focusRings_oneOfTheirTwoLinesStandsOutOnEveryFill() = forEveryTheme { name, c ->
        // Material's inset ring: an outer and an inner line, drawn over the control's own fill
        val ring = focusRingConfiguration(c).focus as RippleConfiguration.Focus.InsetRing
        val fills = mapOf(
            "background" to c.background,
            "surface" to c.surface,
            "surfaceContainerLowest" to c.surfaceContainerLowest,
            "surfaceContainerLow" to c.surfaceContainerLow,
            "surfaceContainerHigh" to c.surfaceContainerHigh,
            "surfaceContainerHighest" to c.surfaceContainerHighest,
            "primaryContainer" to c.primaryContainer,
            "tertiaryContainer" to c.tertiaryContainer,
            "secondaryContainer" to c.secondaryContainer
        )
        for ((fillName, fill) in fills) {
            val strongest = maxOf(contrast(ring.outerStrokeColor, fill), contrast(ring.innerStrokeColor, fill))
            assertTrue(
                "$name focus ring on $fillName is ${"%.2f".format(strongest)}:1, needs 3.0:1",
                strongest >= 3.0
            )
        }
        // On a notice the ring is one line in the inverse accent
        val notice = noticeFocusRing(c).focus as RippleConfiguration.Focus.InsetRing
        assertContrast("$name notice focus ring on inverseSurface", notice.outerStrokeColor, c.inverseSurface, 3.0)
    }

    @Test
    fun chosenMenuRows_keepTheirTextAndTickReadable() = forEveryTheme { name, c ->
        // Material's selectable and checkable menu items: tertiaryContainer behind the chosen row,
        // its text, icon and tick in onTertiaryContainer
        assertContrast("$name onTertiaryContainer on tertiaryContainer", c.onTertiaryContainer, c.tertiaryContainer, 4.5)
    }

    @Test
    fun buttonGroups_bothStatesKeepTheirLabelsReadable() = forEveryTheme { name, c ->
        // Material's toggle buttons in a connected group: off, onSurfaceVariant on surfaceContainer;
        // on, onPrimary on primary
        assertContrast("$name toggle off: onSurfaceVariant on surfaceContainer", c.onSurfaceVariant, c.surfaceContainer, 4.5)
        assertContrast("$name toggle on: onPrimary on primary", c.onPrimary, c.primary, 4.5)
    }

    @Test
    fun settingsRows_textAndControlsReadOnTheirContainers() = forEveryTheme { name, c ->
        // Material's segmented list items on the settings page: surfaceContainer rows, the chosen
        // radio row in secondaryContainer with onSecondaryContainer for its title and description
        assertContrast("$name row title on surfaceContainer", c.onSurface, c.surfaceContainer, 4.5)
        assertContrast("$name row description on surfaceContainer", c.onSurfaceVariant, c.surfaceContainer, 4.5)
        assertContrast("$name switch outline on surfaceContainer", c.outline, c.surfaceContainer, 3.0)
        assertContrast("$name chosen row text on secondaryContainer", c.onSecondaryContainer, c.secondaryContainer, 4.5)
        assertContrast("$name radio button on secondaryContainer", c.primary, c.secondaryContainer, 3.0)
    }

    @Test
    fun progressAndLoading_standOutFromTheirTrackAndTile() = forEveryTheme { name, c ->
        // The wavy download and update lines: primary on their surfaceContainerHighest track, on
        // a download card (surfaceContainerLow) and on the settings page
        assertContrast("$name progress on its track", c.primary, c.surfaceContainerHighest, 3.0)
        assertContrast("$name progress on a download card", c.primary, c.surfaceContainerLow, 3.0)
        assertContrast("$name progress on the settings page", c.primary, c.surfaceContainerLowest, 3.0)
        // The loading indicator in a rail tile (tab tile, highlighted or active)
        for ((tileName, tile) in listOf(
            "surfaceContainerLow" to c.surfaceContainerLow,
            "surfaceContainerHigh" to c.surfaceContainerHigh,
            "surfaceContainerHighest" to c.surfaceContainerHighest
        )) {
            assertContrast("$name loading indicator on a $tileName rail tile", c.primary, tile, 3.0)
        }
    }

    @Test
    fun outlinedButtons_textAndBorderStandOutWhereTheButtonsSit() = forEveryTheme { name, c ->
        // RemOutlinedButton sits on Settings (surfaceContainerLowest), the print panel and the
        // terminal page's cards (surface) and dialogs (surfaceContainerHigh)
        val surfaces = mapOf(
            "background" to c.background,
            "surface" to c.surface,
            "surfaceContainerLowest" to c.surfaceContainerLowest,
            "surfaceContainerHigh" to c.surfaceContainerHigh
        )
        surfaces.forEach { (surfaceName, surface) ->
            assertContrast("$name outlined button border on $surfaceName", outlinedButtonBorderColor(c), surface, 3.0)
            assertContrast("$name outlined button text on $surfaceName", outlinedButtonContentColor(c), surface, 4.5)
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

    @Test
    fun railOverThePage_itsEdgeStandsOutFromTheDimmedToolbarAndPage() = forEveryTheme { name, c ->
        val edge = floatingRailEdge(c)
        val keep = 1f - railScrimAlpha(c)
        fun dimmed(page: Color) = Color(red = page.red * keep, green = page.green * keep, blue = page.blue * keep)
        if (c.background.luminance() >= 0.5f) {
            // The light theme: its shadow and the dimmed toolbar mark it, under Material's scrim
            assertTrue("$name: no edge line, Material's 32% scrim", edge == null && railScrimAlpha(c) == RailScrimAlpha)
            return@forEveryTheme
        }
        // The dark theme: the rail is the toolbar's colour and its shadow doesn't show, so the line
        // marks it, seen against the rail on one side and the dimmed toolbar on the other
        assertTrue("$name: a stronger scrim than Material's 32%", railScrimAlpha(c) > RailScrimAlpha)
        assertContrast("$name rail edge on the rail", edge!!, c.background, 3.0)
        assertContrast("$name rail edge on the dimmed toolbar", edge, dimmed(c.background), 3.0)
        // Beside a page, the line or the rail itself stands out: the theme's own pages (the start
        // page, Settings) and the web's commonest, white and black
        val pages = listOf(
            "surface" to c.surface,
            "surfaceContainerLowest" to c.surfaceContainerLowest,
            "surfaceContainer" to c.surfaceContainer,
            "a white page" to Color.White,
            "a black page" to Color.Black
        )
        for ((pageName, page) in pages) {
            val strongest = maxOf(contrast(edge, dimmed(page)), contrast(c.background, dimmed(page)))
            assertTrue(
                "$name rail edge beside $pageName, dimmed, is ${"%.2f".format(strongest)}:1, needs 3.0:1",
                strongest >= 3.0
            )
        }
    }
}
