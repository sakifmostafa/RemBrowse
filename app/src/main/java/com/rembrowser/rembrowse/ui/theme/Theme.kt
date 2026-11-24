package com.rembrowser.rembrowse.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rembrowser.rembrowse.model.AccentColor

/**
 * RemBrowse design tokens.
 *
 * The brand palette is `#14161B` (chrome), `#571C2B`, `#6C2038` (maroon) and `#AD7587` (rose).
 * Components read colours through [MaterialTheme.colorScheme] roles only:
 *
 * - `background`: window chrome (tab rail and toolbar)
 * - `surface`: raised elements on the chrome (address pill, menus, dialogs, cards)
 * - `surfaceContainerLowest`: the page container behind web content and the start page
 * - `surfaceContainerHigh` / `Highest`: hover and selected fills
 * - `primary`: accent for text, icons, focus and progress
 * - `primaryContainer` / `onPrimaryContainer`: deep accent fill for selected/active items
 * - `tertiaryContainer`: soft accent tint used for the start page backdrop
 * - `outline`: control boundaries (3:1), `outlineVariant`: decorative hairlines
 *
 * With system colours (Material You) the wallpaper's scheme is re-mapped onto the same roles by
 * [remDynamicScheme].
 */
data class AccentTones(
    /** Accent for text and icons on dark surfaces. */
    val darkPrimary: Color,
    val darkContainer: Color,
    val darkOnContainer: Color,
    /** Accent for text and icons on light surfaces. */
    val lightPrimary: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
    /** The swatch shown in Settings. */
    val swatch: Color
)

fun AccentColor.tones(): AccentTones = when (this) {
    AccentColor.MAROON -> AccentTones(
        darkPrimary = Color(0xFFD9A3B3),
        darkContainer = Color(0xFF6C2038),
        darkOnContainer = Color(0xFFFFE9EE),
        lightPrimary = Color(0xFF8A2846),
        lightContainer = Color(0xFFF6DCE3),
        lightOnContainer = Color(0xFF4A1526),
        swatch = Color(0xFF6C2038)
    )
    AccentColor.ROSE -> AccentTones(
        darkPrimary = Color(0xFFF0A8BE),
        darkContainer = Color(0xFF8C3D58),
        darkOnContainer = Color(0xFFFFE7EE),
        lightPrimary = Color(0xFFA63A5E),
        lightContainer = Color(0xFFFBDDE6),
        lightOnContainer = Color(0xFF4D1629),
        swatch = Color(0xFFAD7587)
    )
    AccentColor.PURPLE -> AccentTones(
        darkPrimary = Color(0xFFC9B6F2),
        darkContainer = Color(0xFF4B3A78),
        darkOnContainer = Color(0xFFEFE7FF),
        lightPrimary = Color(0xFF5B45A0),
        lightContainer = Color(0xFFE7DFFB),
        lightOnContainer = Color(0xFF26174F),
        swatch = Color(0xFF6A5596)
    )
    AccentColor.BLUE -> AccentTones(
        darkPrimary = Color(0xFFA9C7F5),
        darkContainer = Color(0xFF2C4C7E),
        darkOnContainer = Color(0xFFE1ECFF),
        lightPrimary = Color(0xFF2F5AA8),
        lightContainer = Color(0xFFDCE8FB),
        lightOnContainer = Color(0xFF10284F),
        swatch = Color(0xFF4A6FA5)
    )
    AccentColor.TEAL -> AccentTones(
        darkPrimary = Color(0xFF8FD3CB),
        darkContainer = Color(0xFF1F5B55),
        darkOnContainer = Color(0xFFD9F5F1),
        lightPrimary = Color(0xFF17695F),
        lightContainer = Color(0xFFD5F0EC),
        lightOnContainer = Color(0xFF06302B),
        swatch = Color(0xFF3F7F7A)
    )
    AccentColor.GREEN -> AccentTones(
        darkPrimary = Color(0xFFA5D6A9),
        darkContainer = Color(0xFF2E6238),
        darkOnContainer = Color(0xFFE0F5E2),
        lightPrimary = Color(0xFF2B6B37),
        lightContainer = Color(0xFFDBF1DE),
        lightOnContainer = Color(0xFF0E3015),
        swatch = Color(0xFF4F8F5A)
    )
}

fun remColorScheme(darkTheme: Boolean, accent: AccentColor): ColorScheme {
    val tones = accent.tones()
    return if (darkTheme) {
        darkColorScheme(
            primary = tones.darkPrimary,
            onPrimary = Color(0xFF14161B),
            primaryContainer = tones.darkContainer,
            onPrimaryContainer = tones.darkOnContainer,
            secondary = Color(0xFFC6CAD3),
            onSecondary = Color(0xFF14161B),
            secondaryContainer = Color(0xFF2D323D),
            onSecondaryContainer = Color(0xFFF2F3F5),
            tertiary = tones.darkPrimary,
            onTertiary = Color(0xFF14161B),
            tertiaryContainer = tones.darkContainer,
            onTertiaryContainer = tones.darkOnContainer,
            background = Color(0xFF14161B),
            onBackground = Color(0xFFF2F3F5),
            surface = Color(0xFF1C1F26),
            onSurface = Color(0xFFF2F3F5),
            surfaceVariant = Color(0xFF2D323D),
            onSurfaceVariant = Color(0xFFA7ADB8),
            surfaceTint = tones.darkPrimary,
            surfaceBright = Color(0xFF2D323D),
            surfaceDim = Color(0xFF0F1115),
            surfaceContainerLowest = Color(0xFF0F1115),
            surfaceContainerLow = Color(0xFF181B21),
            surfaceContainer = Color(0xFF1C1F26),
            surfaceContainerHigh = Color(0xFF242832),
            surfaceContainerHighest = Color(0xFF2D323D),
            inverseSurface = Color(0xFFF2F3F5),
            inverseOnSurface = Color(0xFF17181C),
            inversePrimary = tones.lightPrimary,
            outline = Color(0xFF7B8291),
            outlineVariant = Color(0xFF2E323C),
            error = Color(0xFFFF8A8A),
            onError = Color(0xFF4A0A0A),
            errorContainer = Color(0xFF7A1F1F),
            onErrorContainer = Color(0xFFFFE1E1),
            scrim = Color(0xFF000000)
        )
    } else {
        lightColorScheme(
            primary = tones.lightPrimary,
            onPrimary = Color(0xFFFFFFFF),
            // Selected and active fills use the same deep accent in both themes, so they read
            // clearly against white as well as against the dark chrome.
            primaryContainer = tones.darkContainer,
            onPrimaryContainer = tones.darkOnContainer,
            secondary = Color(0xFF555962),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE3E1E3),
            onSecondaryContainer = Color(0xFF17181C),
            tertiary = tones.lightPrimary,
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = tones.lightContainer,
            onTertiaryContainer = tones.lightOnContainer,
            background = Color(0xFFF4F2F3),
            onBackground = Color(0xFF17181C),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF17181C),
            surfaceVariant = Color(0xFFE3E1E3),
            onSurfaceVariant = Color(0xFF555962),
            surfaceTint = tones.lightPrimary,
            surfaceBright = Color(0xFFFFFFFF),
            surfaceDim = Color(0xFFE3E1E3),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFAF9F9),
            surfaceContainer = Color(0xFFF4F2F3),
            surfaceContainerHigh = Color(0xFFECEAEB),
            surfaceContainerHighest = Color(0xFFE3E1E3),
            inverseSurface = Color(0xFF1C1F26),
            inverseOnSurface = Color(0xFFF2F3F5),
            inversePrimary = tones.darkPrimary,
            outline = Color(0xFF747985),
            outlineVariant = Color(0xFFE0DEE0),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            scrim = Color(0xFF000000)
        )
    }
}

/**
 * A wallpaper (Material You) scheme re-mapped to RemBrowse's roles (see the list at the top of
 * this file), so it is laid out like the fixed themes: the chrome a step off the page colour,
 * raised elements a step lighter (white in the light theme), a soft accent tint behind the start
 * page and, in the light theme too, deep selected fills. Only roles already in [base] are moved,
 * so Material's tone distances between text and surface roles hold.
 */
fun remDynamicScheme(base: ColorScheme, dark: Boolean): ColorScheme = if (dark) {
    base.copy(
        // Chrome between the lowest and the low container; raised elements one step up
        background = base.surface,
        surface = base.surfaceContainer,
        // Near-black on the accent, as in the fixed dark themes: the screenshot outline pairs the
        // two, and Material's lighter tone 20 left mid-grey pages where neither stood out
        onPrimary = base.onPrimaryFixed,
        tertiary = base.primary,
        onTertiary = base.onPrimaryFixed,
        tertiaryContainer = base.primaryContainer,
        onTertiaryContainer = base.onPrimaryContainer
    )
} else {
    base.copy(
        // The light chrome is a soft grey, raised elements white
        background = base.surfaceContainer,
        surface = base.surfaceContainerLowest,
        tertiary = base.primary,
        onTertiary = base.onPrimary,
        tertiaryContainer = base.primaryContainer,
        onTertiaryContainer = base.onPrimaryContainer,
        // Selected and active fills stay deep, as in the fixed light themes
        primaryContainer = base.primary,
        onPrimaryContainer = base.onPrimary
    )
}

/** Whether this device can give the app its wallpaper colours (Android 12+). */
val systemColorsAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The browser's colours: the wallpaper's when [systemColors] is on and the device has them,
 * otherwise the fixed theme for [accent].
 */
fun remColorScheme(context: Context, darkTheme: Boolean, accent: AccentColor, systemColors: Boolean): ColorScheme =
    if (systemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        remDynamicScheme(
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
            darkTheme
        )
    } else {
        remColorScheme(darkTheme, accent)
    }

/**
 * The terminal's cursor. Terminal themes are mostly dark, so it is the accent for dark
 * surfaces, from the wallpaper when system colours are on.
 */
fun terminalCursorColor(context: Context, accent: AccentColor, systemColors: Boolean): Color =
    if (systemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context).primary
    } else {
        accent.tones().darkPrimary
    }

/** System font throughout; only the start page clock departs from the Material type scale. */
val RemTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontSize = 96.sp,
            lineHeight = 104.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-2).sp
        ),
        displayMedium = base.displayMedium.copy(
            fontSize = 64.sp,
            lineHeight = 72.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-1).sp
        ),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    )
}

val RemShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Sizes shared by the browser chrome. Spacing elsewhere follows a 4/8dp rhythm. */
object RemDimens {
    val RailWidth = 56.dp
    val ToolbarHeight = 52.dp
    val ControlSize = 40.dp

    /** The toolbar on a phone-sized window: a touch screen, so the address bar is a full 48dp target. */
    val CompactToolbarHeight = 56.dp

    /** The address bar's height on a phone-sized window (desktop density elsewhere). */
    val CompactControlSize = 48.dp
    val IconSize = 20.dp
    val PaneGap = 8.dp
    val PaneRadius = 12.dp
    /** Below this window width the tab rail gives way to a tab-count button. */
    val CompactWidth = 600.dp
}

@Composable
fun RemBrowseTheme(
    darkTheme: Boolean = true,
    accent: AccentColor = AccentColor.MAROON,
    /** The wallpaper's colours instead of [accent] (Android 12+; ignored before). */
    systemColors: Boolean = false,
    content: @Composable () -> Unit
) {
    RemBrowseTheme(remColorScheme(LocalContext.current, darkTheme, accent, systemColors), content)
}

/** The browser's type and shapes over [colorScheme], worked out already (see resolveTheme). */
@Composable
fun RemBrowseTheme(colorScheme: ColorScheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = RemTypography,
        shapes = RemShapes,
        content = content
    )
}
