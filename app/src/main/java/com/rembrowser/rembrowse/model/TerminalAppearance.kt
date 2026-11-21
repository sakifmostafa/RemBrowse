package com.rembrowser.rembrowse.model

/**
 * A terminal colour theme: the background, text, cursor and the 16 colours programs use
 * (black, red, green, yellow, blue, magenta, cyan, white, then their bright versions).
 *
 * The named schemes are the open-source palettes of the same names, as most terminal apps
 * offer them. [cursor] null means RemBrowse's accent colour.
 */
enum class TerminalThemePreset(
    val label: String,
    val background: Long,
    val foreground: Long,
    val cursor: Long?,
    val ansi: LongArray,
    /** A light background: the app shows dark text around it. */
    val isLight: Boolean = false
) {
    REMBROWSE(
        "RemBrowse", 0xFF0F1115, 0xFFE6E8EB, null,
        longArrayOf(
            0xFF1C1F26, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFC8CCD4,
            0xFF5C6370, 0xFFFF7A85, 0xFFB5E890, 0xFFFFD68A, 0xFF82C4FF, 0xFFDB9AF0, 0xFF7AD3DD, 0xFFF2F3F5
        )
    ),
    MIDNIGHT_GREEN(
        "Midnight Green", 0xFF131726, 0xFF35C980, 0xFF35C980,
        longArrayOf(
            0xFF1E2336, 0xFFE5727A, 0xFF35C980, 0xFFE3C26E, 0xFF5AA9E6, 0xFFC792EA, 0xFF3DD9C1, 0xFFB8C4D6,
            0xFF4A5470, 0xFFFF8A92, 0xFF5BE3A0, 0xFFF2D58A, 0xFF7FC0F2, 0xFFD9AEF5, 0xFF66EBD6, 0xFFE6EDF7
        )
    ),
    DRACULA(
        "Dracula", 0xFF282A36, 0xFFF8F8F2, 0xFFF8F8F2,
        longArrayOf(
            0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C, 0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
            0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5, 0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF
        )
    ),
    NORD(
        "Nord", 0xFF2E3440, 0xFFD8DEE9, 0xFFD8DEE9,
        longArrayOf(
            0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
            0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4
        )
    ),
    SOLARIZED_DARK(
        "Solarized Dark", 0xFF002B36, 0xFF93A1A1, 0xFF93A1A1,
        longArrayOf(
            0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
            0xFF586E75, 0xFFCB4B16, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFF6C71C4, 0xFF2AA198, 0xFFFDF6E3
        )
    ),
    GRUVBOX_DARK(
        "Gruvbox Dark", 0xFF282828, 0xFFEBDBB2, 0xFFEBDBB2,
        longArrayOf(
            0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921, 0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
            0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F, 0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2
        )
    ),
    PAPER(
        "Paper", 0xFFFAF8F5, 0xFF2B2D31, null,
        longArrayOf(
            0xFF2B2D31, 0xFFC4373B, 0xFF2F7D3E, 0xFF946300, 0xFF2F5DA8, 0xFF8A3FA0, 0xFF1F7A82, 0xFF8C8C8C,
            0xFF5F6168, 0xFFD9443F, 0xFF3D9450, 0xFFAE7700, 0xFF3B73CC, 0xFFA653BD, 0xFF26939C, 0xFFB8B8B8
        ),
        isLight = true
    );

    companion object {
        val DEFAULT = REMBROWSE

        fun fromNameOrDefault(name: String?): TerminalThemePreset = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The terminal's typeface. Both bundled fonts are under the SIL Open Font License. */
enum class TerminalFont(val label: String) {
    JETBRAINS_MONO("JetBrains Mono"),
    FIRA_MONO("Fira Mono"),
    SYSTEM("System monospace");

    companion object {
        val DEFAULT = JETBRAINS_MONO

        fun fromNameOrDefault(name: String?): TerminalFont = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The cursor's shape. A program can still change it while it runs (vim's insert mode, say). */
enum class TerminalCursorStyle(val label: String, val emulatorStyle: Int) {
    BLOCK("Block", 0),
    BAR("Bar", 2),
    UNDERLINE("Underline", 1);

    companion object {
        val DEFAULT = BLOCK

        fun fromNameOrDefault(name: String?): TerminalCursorStyle = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
