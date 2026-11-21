package com.rembrowser.rembrowse.terminal

import android.app.Application
import android.content.ClipboardManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeSshConnector
import com.rembrowser.rembrowse.fakes.TestMainQueue
import com.rembrowser.rembrowse.model.TerminalCursorStyle
import com.rembrowser.rembrowse.model.TerminalFont
import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the terminal view sends for keys, and how it selects, scrolls and copies. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class TerminalScreenViewTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val main = TestMainQueue()
    private val connector = FakeSshConnector()
    private val hostKeys = object : HostKeyStore {
        override fun knownKey(hostname: String, port: Int): Pair<String, String>? = null
        override fun remember(check: HostKeyCheck) {}
    }
    private val connection = TerminalConnection("tab", connector, hostKeys, main::post) { it.run() }
    private val view = TerminalScreenView(context).apply {
        connection = this@TerminalScreenViewTest.connection
        measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        layout(0, 0, 800, 480)
    }

    private fun connected(): FakeSshConnector.FakeShell {
        connection.connect(TerminalConnectSpec("server", "server", 22, "me", SshCredentials.Password("secret")))
        main.drain()
        return connector.shells.last()
    }

    /** Where the first column starts: the view keeps a 16dp margin at the sides. */
    private val origin = 16f * context.resources.displayMetrics.density

    private fun key(keyCode: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta)

    private fun mouse(action: Int, x: Float, y: Float, buttons: Int, time: Long = SystemClock.uptimeMillis()): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        return MotionEvent.obtain(time, time, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    @Test
    fun controlAndAlt_becomeWhatTheShellExpects() {
        assertEquals("\u0003", TerminalScreenView.encodeCodePoint('c'.code, ctrl = true, alt = false))
        assertEquals("\u0017", TerminalScreenView.encodeCodePoint('w'.code, ctrl = true, alt = false))
        assertEquals("\u001b", TerminalScreenView.encodeCodePoint('['.code, ctrl = true, alt = false))
        assertEquals("\u0000", TerminalScreenView.encodeCodePoint(' '.code, ctrl = true, alt = false))
        assertEquals("\u007f", TerminalScreenView.encodeCodePoint('?'.code, ctrl = true, alt = false))
        assertEquals("Alt+B moves back a word", "\u001bb", TerminalScreenView.encodeCodePoint('b'.code, ctrl = false, alt = true))
        assertEquals("é", TerminalScreenView.encodeCodePoint('é'.code, ctrl = false, alt = false))
        assertEquals("😀", TerminalScreenView.encodeCodePoint(0x1F600, ctrl = false, alt = false))
    }

    @Test
    fun keysReachTheShell_whileTheBrowsersKeysStayOut() {
        val shell = connected()
        view.onKeyDown(KeyEvent.KEYCODE_L, key(KeyEvent.KEYCODE_L))
        view.onKeyDown(KeyEvent.KEYCODE_S, key(KeyEvent.KEYCODE_S))
        view.onKeyDown(KeyEvent.KEYCODE_ENTER, key(KeyEvent.KEYCODE_ENTER))
        view.onKeyDown(KeyEvent.KEYCODE_W, key(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        view.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, key(KeyEvent.KEYCODE_DPAD_UP))
        view.onKeyDown(KeyEvent.KEYCODE_TAB, key(KeyEvent.KEYCODE_TAB))
        view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, key(KeyEvent.KEYCODE_ESCAPE))
        assertEquals("ls\r\u0017\u001b[A\t\u001b", shell.typed())
    }

    @Test
    fun theViewsSize_isTheTerminalsSize() {
        connected()
        val emulator = connection.emulator
        assertTrue("${emulator.mColumns} columns", emulator.mColumns in 40..200)
        assertTrue("${emulator.mRows} rows", emulator.mRows in 10..60)

        view.layout(0, 0, 400, 480)
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        // onSizeChanged runs on layout with a new size
        assertTrue(connection.emulator.mColumns < 100)
    }

    @Test
    fun dragging_selectsText_whichCopies() {
        val shell = connected()
        shell.print("hello world\r\nsecond line\r\n")
        main.drain()

        val start = view.cellAt(0f, 0f)
        assertEquals(0, start.column)
        // Find where "world" begins: column 6 of the first row
        val cellWidth = 800f / connection.emulator.mColumns
        val y = 12f
        val row = view.cellAt(0f, y).row
        val time = SystemClock.uptimeMillis()
        view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, origin + 6.2f * cellWidthOf(), y, MotionEvent.BUTTON_PRIMARY, time))
        view.onTouchEvent(mouse(MotionEvent.ACTION_MOVE, origin + 10.5f * cellWidthOf(), y, MotionEvent.BUTTON_PRIMARY, time + 50))
        view.onTouchEvent(mouse(MotionEvent.ACTION_UP, origin + 10.5f * cellWidthOf(), y, 0, time + 60))
        assertTrue(cellWidth > 0)

        val selected = view.selectedText()
        assertEquals("Row $row", "world", selected)
        assertTrue(view.copySelection())
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("world", clip.getItemAt(0).text.toString())

        // Typing goes back to the live screen and drops the selection
        view.onKeyDown(KeyEvent.KEYCODE_A, key(KeyEvent.KEYCODE_A))
        assertNull(view.selection)
    }

    @Test
    fun aDoubleClickSelectsAWord_andARightClickOpensTheMenu() {
        val shell = connected()
        shell.print("cd /var/log\r\n")
        main.drain()
        val x = origin + 4.5f * cellWidthOf()
        val y = 12f
        val time = SystemClock.uptimeMillis()
        view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, time))
        view.onTouchEvent(mouse(MotionEvent.ACTION_UP, x, y, 0, time + 30))
        view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, time + 120))
        view.onTouchEvent(mouse(MotionEvent.ACTION_UP, x, y, 0, time + 150))
        assertEquals("/var/log", view.selectedText())

        var menuAt: Pair<Float, Float>? = null
        view.onContextMenu = { mx, my -> menuAt = mx to my }
        view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, 100f, 50f, MotionEvent.BUTTON_SECONDARY, time + 1000))
        assertEquals(100f to 50f, menuAt)
        assertEquals("The selection stays for the menu's Copy", "/var/log", view.selectedText())
    }

    @Test
    fun theWheel_movesThroughTheHistory_andNewOutputKeepsThePlace() {
        val shell = connected()
        shell.print((1..200).joinToString("") { "line $it\r\n" })
        main.drain()
        assertEquals(0, view.topRow)

        view.scrollBy(-10)
        assertEquals(-10, view.topRow)
        shell.print("more\r\n")
        main.drain()
        assertEquals("Still showing the same lines", -11, view.topRow)

        view.scrollBy(1_000)
        assertEquals("Not past the live screen", 0, view.topRow)
        view.scrollBy(-100_000)
        assertEquals(-connection.emulator.screen.activeTranscriptRows, view.topRow)
    }

    @Test
    fun fullScreenPrograms_getArrowKeysForTheWheel() {
        val shell = connected()
        // less, man and the like switch to the alternate screen
        shell.print("\u001b[?1049h")
        main.drain()
        view.scrollBy(2)
        assertTrue(shell.typed(), shell.typed().endsWith("\u001b[B\u001b[B"))
        assertEquals(0, view.topRow)
    }

    @Test
    fun enterOnADisconnectedTerminal_asksToReconnect() {
        val shell = connected()
        shell.end(null)
        main.drain()
        var asked = false
        view.onReconnectRequested = { asked = true }
        view.onKeyDown(KeyEvent.KEYCODE_ENTER, key(KeyEvent.KEYCODE_ENTER))
        assertTrue(asked)
    }

    // --- Suggestions ---

    /** Presses the keys for [text]: letters, digits, spaces and a few symbols. */
    private fun press(text: String) {
        text.forEach { char ->
            val code = when (char) {
                ' ' -> KeyEvent.KEYCODE_SPACE
                '-' -> KeyEvent.KEYCODE_MINUS
                '.' -> KeyEvent.KEYCODE_PERIOD
                '/' -> KeyEvent.KEYCODE_SLASH
                else -> KeyEvent.keyCodeFromString("KEYCODE_" + char.uppercaseChar())
            }
            view.onKeyDown(code, key(code))
        }
    }

    /** Presses the keys for [text], and the shell echoes them back. */
    private fun typeEchoed(shell: FakeSshConnector.FakeShell, text: String) {
        press(text)
        shell.print(text)
        main.drain()
    }

    private fun withSuggestions(vararg history: String) {
        view.suggest = { typed -> CommandSuggestions.suggest(typed, history.toList()) }
    }

    @Test
    fun whatIsTyped_isSuggestedOn_andTabTypesTheRest() {
        val shell = connected()
        withSuggestions("git stash pop")
        shell.print("me@box:~$ ")
        main.drain()

        typeEchoed(shell, "git s")
        assertEquals("From the history first", "git stash pop", view.suggestion)

        view.onKeyDown(KeyEvent.KEYCODE_TAB, key(KeyEvent.KEYCODE_TAB))
        assertEquals("The rest of the command, not a Tab", "git stash pop", shell.typed())
        shell.print("tash pop")
        main.drain()
        assertNull("Nothing more to suggest", view.suggestion)
    }

    @Test
    fun theRightArrow_takesTheSuggestionToo_andWithoutOneMovesTheCursor() {
        val shell = connected()
        withSuggestions()
        shell.print("$ ")
        main.drain()
        typeEchoed(shell, "docker comp")
        assertEquals("A common command", "docker compose up -d", view.suggestion)
        view.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(shell.typed(), shell.typed().endsWith("docker compose up -d"))

        shell.print("ose up -d")
        main.drain()
        view.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(shell.typed().endsWith("\u001b[C"))
    }

    @Test
    fun aPromptThatDoesNotEcho_getsNoSuggestion_andIsNeverRemembered() {
        val shell = connected()
        withSuggestions("hunter2 is not a command")
        val remembered = mutableListOf<String>()
        view.onCommandRun = { remembered += it }
        shell.print("[sudo] password for me: ")
        main.drain()

        press("hunter")
        main.drain()
        assertNull(view.suggestion)
        view.onKeyDown(KeyEvent.KEYCODE_ENTER, key(KeyEvent.KEYCODE_ENTER))
        assertEquals(emptyList<String>(), remembered)
    }

    @Test
    fun enter_remembersTheCommand_evenWithTheLastKeysStillOnTheirWay() {
        val shell = connected()
        val remembered = mutableListOf<String>()
        view.onCommandRun = { remembered += it }
        shell.print("$ ")
        main.drain()

        typeEchoed(shell, "uptime")
        view.onKeyDown(KeyEvent.KEYCODE_ENTER, key(KeyEvent.KEYCODE_ENTER))
        shell.print("\r\n 10:00 up 3 days\r\n$ ")
        main.drain()

        // Typed quickly: "tus" hasn't come back when Enter is pressed
        press("git status")
        shell.print("git sta")
        main.drain()
        view.onKeyDown(KeyEvent.KEYCODE_ENTER, key(KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf("uptime", "git status"), remembered)
    }

    @Test
    fun aLineEditedWithKeysTheViewCantFollow_isLeftAlone() {
        val shell = connected()
        withSuggestions("git status")
        val remembered = mutableListOf<String>()
        view.onCommandRun = { remembered += it }
        shell.print("$ ")
        main.drain()

        typeEchoed(shell, "git s")
        assertEquals("git status", view.suggestion)
        // Up brings back the shell's own history: the line is no longer what was typed
        view.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, key(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(view.suggestion)
        view.onKeyDown(KeyEvent.KEYCODE_ENTER, key(KeyEvent.KEYCODE_ENTER))
        assertEquals(emptyList<String>(), remembered)

        // Enter starts afresh: following works again
        shell.print("\r\n$ ")
        main.drain()
        typeEchoed(shell, "git s")
        assertEquals("git status", view.suggestion)

        // Ctrl+C drops the line
        view.onKeyDown(KeyEvent.KEYCODE_C, key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        assertNull(view.suggestion)
    }

    @Test
    fun backspace_isFollowed() {
        val shell = connected()
        withSuggestions("ls -la")
        shell.print("$ ")
        main.drain()
        typeEchoed(shell, "lx")
        assertNull(view.suggestion)
        view.onKeyDown(KeyEvent.KEYCODE_DEL, key(KeyEvent.KEYCODE_DEL))
        shell.print("\b \b")
        main.drain()
        typeEchoed(shell, "s -")
        assertEquals("ls -la", view.suggestion)
    }

    @Test
    fun fullScreenPrograms_getNoSuggestions() {
        val shell = connected()
        withSuggestions("git status")
        shell.print("\u001b[?1049h")
        main.drain()
        typeEchoed(shell, "git s")
        assertNull(view.suggestion)
        view.onKeyDown(KeyEvent.KEYCODE_TAB, key(KeyEvent.KEYCODE_TAB))
        assertTrue("Tab goes to the program", shell.typed().endsWith("\t"))
    }

    @Test
    fun withSuggestionsOff_tabIsTheShells() {
        val shell = connected()
        shell.print("$ ")
        main.drain()
        typeEchoed(shell, "git s")
        assertNull(view.suggestion)
        view.onKeyDown(KeyEvent.KEYCODE_TAB, key(KeyEvent.KEYCODE_TAB))
        assertEquals("git s\t", shell.typed())
    }

    // --- Appearance ---

    @Test
    fun theTheme_colorsTheTerminal_andTheAccentFillsInTheCursor() {
        connected()
        val colors = { connection.emulator.mColors.mCurrentColors }
        val accent = 0xFF123456.toInt()

        view.setAppearance(TerminalThemePreset.MIDNIGHT_GREEN, accent, TerminalFont.FIRA_MONO, TerminalCursorStyle.BAR)
        assertEquals(TerminalThemePreset.MIDNIGHT_GREEN.background.toInt(), colors()[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(TerminalThemePreset.MIDNIGHT_GREEN.foreground.toInt(), colors()[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(TerminalThemePreset.MIDNIGHT_GREEN.ansi[4].toInt(), colors()[4])
        assertEquals("The theme's own cursor", TerminalThemePreset.MIDNIGHT_GREEN.cursor!!.toInt(), colors()[TextStyle.COLOR_INDEX_CURSOR])
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR, connection.emulator.cursorStyle)

        view.setAppearance(TerminalThemePreset.REMBROWSE, accent, TerminalFont.JETBRAINS_MONO, TerminalCursorStyle.UNDERLINE)
        assertEquals(TerminalThemePreset.REMBROWSE.background.toInt(), colors()[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals("RemBrowse's accent", accent, colors()[TextStyle.COLOR_INDEX_CURSOR])
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE, connection.emulator.cursorStyle)
    }

    @Test
    fun aNewTerminal_startsInTheChosenTheme() {
        view.setAppearance(TerminalThemePreset.NORD, 0xFF123456.toInt(), TerminalFont.DEFAULT, TerminalCursorStyle.DEFAULT)
        val other = TerminalConnection("other", connector, hostKeys, main::post) { it.run() }
        assertEquals(TerminalThemePreset.NORD.background.toInt(), other.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
        assertFalse(TerminalThemePreset.NORD.isLight)
        view.setAppearance(TerminalThemePreset.DEFAULT, TerminalTheme.DEFAULT_CURSOR, TerminalFont.DEFAULT, TerminalCursorStyle.DEFAULT)
    }

    @Test
    fun cellAt_startsAtTheSideAndTopMargins() {
        connected()
        val density = context.resources.displayMetrics.density
        val cell = cellWidthOf()
        assertEquals("Left of the margin is the first column", 0, view.cellAt(origin - 1f, 0f).column)
        assertEquals(0, view.cellAt(origin + 0.25f * cell, 0f).column)
        assertEquals("One cell in is the second column", 1, view.cellAt(origin + 1.25f * cell, 0f).column)
        assertEquals(2, view.cellAt(origin + 2.25f * cell, 0f).column)

        val top = 12f * density
        var y = top
        while (view.cellAt(origin, y).row == 0 && y < 480f) y += 0.5f
        assertTrue("A second row starts below the top margin", y < 480f)
        assertEquals("Above the top margin is the first row", 0, view.cellAt(origin, top - 1f).row)
        val rowHeight = run {
            var z = y
            while (view.cellAt(origin, z).row == 1 && z < 480f) z += 0.5f
            z - y
        }
        // The row boundary sits one line below the margin plus the ascent, so the margin must be the offset
        assertTrue("Row height $rowHeight", rowHeight > 0f)
        assertEquals(1, view.cellAt(origin, y + 0.25f * rowHeight).row)
    }

    private fun cellWidthOf(): Float {
        // The width of one cell, from where columns start in the view
        val a = view.cellAt(origin, 0f).column
        var x = origin
        while (view.cellAt(x, 0f).column == a) x += 0.5f
        return x - origin
    }
}
