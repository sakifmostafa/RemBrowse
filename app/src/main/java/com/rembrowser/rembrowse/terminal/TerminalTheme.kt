package com.rembrowser.rembrowse.terminal

import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.termux.terminal.TerminalColors
import java.util.Locale
import java.util.Properties

/**
 * The colours every terminal starts from: the chosen [TerminalThemePreset], with RemBrowse's
 * accent for the cursor when the theme has no cursor colour of its own.
 *
 * The emulator keeps one scheme for all terminals ([TerminalColors.COLOR_SCHEME]), and each
 * terminal copies it when made or reset. [generation] goes up with every change, so a terminal
 * can tell it has an old copy (see TerminalConnection.applyTheme).
 */
object TerminalTheme {
    val BACKGROUND: Int = TerminalThemePreset.DEFAULT.background.toInt()
    val FOREGROUND: Int = TerminalThemePreset.DEFAULT.foreground.toInt()

    /** The accent RemBrowse starts with (maroon's light tone); the screen passes the chosen one. */
    const val DEFAULT_CURSOR = 0xFFD9A3B3.toInt()

    @Volatile
    var current: TerminalThemePreset = TerminalThemePreset.DEFAULT
        private set

    @Volatile
    var generation = 0
        private set

    private var appliedCursor: Int? = null

    /** Makes sure the colours are in place before the first terminal is created. */
    fun ensureApplied() {
        if (appliedCursor == null) apply(TerminalThemePreset.DEFAULT, DEFAULT_CURSOR)
    }

    /** Makes [preset] (with [accentCursor] where it has no cursor colour) the colours terminals use. */
    @Synchronized
    fun apply(preset: TerminalThemePreset, accentCursor: Int) {
        val cursor = preset.cursor?.toInt() ?: accentCursor
        if (preset == current && appliedCursor == cursor) return
        current = preset
        appliedCursor = cursor
        val props = Properties()
        props["foreground"] = hex(preset.foreground.toInt())
        props["background"] = hex(preset.background.toInt())
        props["cursor"] = hex(cursor)
        preset.ansi.forEachIndexed { index, color -> props["color$index"] = hex(color.toInt()) }
        TerminalColors.COLOR_SCHEME.updateWith(props)
        generation++
    }

    private fun hex(color: Int): String = String.format(Locale.ROOT, "#%06x", color and 0xFFFFFF)
}
