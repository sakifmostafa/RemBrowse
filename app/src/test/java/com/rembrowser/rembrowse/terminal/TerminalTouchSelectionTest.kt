package com.rembrowser.rembrowse.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PointF
import android.os.Looper
import android.os.SystemClock
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeSshConnector
import com.rembrowser.rembrowse.fakes.TestMainQueue
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.rembrowser.rembrowse.ui.theme.tones
import java.time.Duration
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Selecting with a finger or a stylus: long-press, the handles, the platform's floating toolbar,
 * taps and scrolling. The view sits in a real window, so the toolbar is Android's own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp-mdpi")
class TerminalTouchSelectionTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val main = TestMainQueue()
    private val connector = FakeSshConnector()
    private val hostKeys = object : HostKeyStore {
        override fun knownKey(hostname: String, port: Int): Pair<String, String>? = null
        override fun remember(check: HostKeyCheck) {}
    }
    private val connection = TerminalConnection("tab", connector, hostKeys, main::post) { it.run() }
    private val view = TerminalScreenView(activity)

    /** The action modes the window started and finished, as the window itself was told. */
    private val started = mutableListOf<ActionMode>()
    private val finished = mutableListOf<ActionMode>()

    @Before
    fun setUp() {
        val window = activity.window
        val original = window.callback
        window.callback = object : Window.Callback by original {
            override fun onActionModeStarted(mode: ActionMode) {
                started += mode
                original.onActionModeStarted(mode)
            }

            override fun onActionModeFinished(mode: ActionMode) {
                finished += mode
                original.onActionModeFinished(mode)
            }
        }
        view.connection = connection
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(800, 480)) })
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun connected(): FakeSshConnector.FakeShell {
        connection.connect(TerminalConnectSpec("server", "server", 22, "me", SshCredentials.Password("secret")))
        main.drain()
        return connector.shells.last()
    }

    private fun connectedShowing(text: String): FakeSshConnector.FakeShell = connected().also {
        it.print(text)
        main.drain()
    }

    // --- Fingers ---

    private var downTime = 0L

    /** A finger (or with [tool] and [source], a stylus or the touchpad) at [at]. */
    private fun touch(
        action: Int,
        at: PointF,
        tool: Int = MotionEvent.TOOL_TYPE_FINGER,
        source: Int = InputDevice.SOURCE_TOUCHSCREEN
    ) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = at.x; y = at.y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(downTime, now, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun waitFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun waitPastLongPress() = waitFor(ViewConfiguration.getLongPressTimeout() + 50L)

    private fun longPress(at: PointF) {
        touch(MotionEvent.ACTION_DOWN, at)
        waitPastLongPress()
    }

    /** A long-press at [at], and the finger lifted. */
    private fun longPressAndLift(at: PointF) {
        longPress(at)
        touch(MotionEvent.ACTION_UP, at)
    }

    private fun tap(at: PointF) {
        touch(MotionEvent.ACTION_DOWN, at)
        touch(MotionEvent.ACTION_UP, at)
    }

    /** The middle of the cell at [column], [row]. */
    private fun center(column: Int, row: Int): PointF = view.touchSelection.cellCenter(TerminalScreenView.Cell(column, row))

    private fun cellWidth() = center(1, 0).x - center(0, 0).x

    private fun rowHeight() = center(0, 1).y - center(0, 0).y

    // --- The toolbar, the keyboard and the clipboard ---

    private fun item(id: Int): MenuItem = view.touchSelection.toolbar!!.menu.findItem(id)

    /** Taps [id] on the toolbar, as its button does. */
    private fun choose(id: Int) {
        assertTrue("${activity.resources.getResourceEntryName(id)} is offered", view.touchSelection.toolbar!!.menu.performIdentifierAction(id, 0))
    }

    private fun keyboardShown() = shadowOf(activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible

    private fun clipboard() = activity.getSystemService(ClipboardManager::class.java)

    private fun lastHaptic() = shadowOf(view).lastHapticFeedbackPerformed()

    @Test
    fun aLongPress_selectsTheWord_andOpensThePlatformsToolbar() {
        val shell = connectedShowing("hello world\r\nsecond line\r\n")

        longPress(center(7, 0))
        assertEquals("world", view.selectedText())
        assertEquals(HapticFeedbackConstants.LONG_PRESS, lastHaptic())
        val toolbar = view.touchSelection.toolbar
        assertNotNull("A toolbar", toolbar)
        assertEquals(ActionMode.TYPE_FLOATING, toolbar!!.type)
        assertEquals(listOf(toolbar), started)
        // The platform's words
        assertEquals(activity.getString(android.R.string.copy), item(android.R.id.copy).title.toString())
        assertEquals(activity.getString(android.R.string.paste), item(android.R.id.paste).title.toString())
        assertEquals(activity.getString(android.R.string.selectAll), item(android.R.id.selectAll).title.toString())
        assertTrue(item(android.R.id.copy).isVisible)
        assertTrue(item(android.R.id.selectAll).isVisible)
        assertNotNull("Both handles show", view.touchSelection.handleCenter(start = true))
        assertNotNull(view.touchSelection.handleCenter(start = false))

        touch(MotionEvent.ACTION_UP, center(7, 0))
        assertEquals("Lifting the finger keeps it", "world", view.selectedText())
        assertTrue(view.touchSelection.isActive)
        assertSame(toolbar, view.touchSelection.toolbar)
        assertFalse("A long-press is not a tap", keyboardShown())
        assertEquals("Nothing reached the shell", "", shell.typed())
    }

    @Test
    fun aLongPressThenADrag_takesInMore_aWordAtATime() {
        connectedShowing("hello world\r\nsecond line\r\n")

        longPress(center(1, 0))
        assertEquals("hello", view.selectedText())
        touch(MotionEvent.ACTION_MOVE, center(7, 0))
        assertEquals("The whole word reached", "hello world", view.selectedText())
        assertEquals("A tick as it grows", HapticFeedbackConstants.TEXT_HANDLE_MOVE, lastHaptic())
        touch(MotionEvent.ACTION_MOVE, center(2, 1))
        assertEquals("hello world\nsecond", view.selectedText())
        touch(MotionEvent.ACTION_UP, center(2, 1))
        assertEquals("hello world\nsecond", view.selectedText())
        assertTrue(view.touchSelection.isActive)
    }

    @Test
    fun aDragWithoutALongPress_scrolls_andSelectsNothing() {
        val shell = connectedShowing((1..200).joinToString("") { "line $it\r\n" })
        val start = center(3, 5)
        val row = rowHeight()

        touch(MotionEvent.ACTION_DOWN, start)
        // Past the touch slop it is a scroll; it moves the view from there on
        touch(MotionEvent.ACTION_MOVE, PointF(start.x, start.y + row))
        touch(MotionEvent.ACTION_MOVE, PointF(start.x, start.y + 5 * row))
        assertEquals("Dragged down four rows, four older rows come into view", -4, view.topRow)
        waitPastLongPress()
        touch(MotionEvent.ACTION_UP, PointF(start.x, start.y + 5 * row))

        assertNull(view.selection)
        assertFalse(view.touchSelection.isActive)
        assertTrue("No toolbar", started.isEmpty())
        assertFalse("Not a tap either", keyboardShown())
        assertEquals("", shell.typed())
    }

    @Test
    fun aFingerThatWobblesWithinTheSlop_stillLongPresses() {
        connectedShowing("hello world\r\n")
        val at = center(1, 0)
        touch(MotionEvent.ACTION_DOWN, at)
        touch(MotionEvent.ACTION_MOVE, PointF(at.x + 2f, at.y + 2f))
        waitPastLongPress()
        assertEquals("hello", view.selectedText())
        assertEquals("The view didn't scroll", 0, view.topRow)
    }

    @Test
    fun draggingAHandle_movesItsEndCellByCell_andTheEndsSwapWhenTheyCross() {
        connectedShowing("alpha beta gamma\r\n")
        longPressAndLift(center(7, 0))
        assertEquals("beta", view.selectedText())

        val handle = view.touchSelection.handleCenter(start = false)!!
        val cell = cellWidth()
        touch(MotionEvent.ACTION_DOWN, handle)
        touch(MotionEvent.ACTION_MOVE, PointF(handle.x + 3 * cell, handle.y))
        assertEquals("beta ga", view.selectedText())
        assertEquals(HapticFeedbackConstants.TEXT_HANDLE_MOVE, lastHaptic())

        // Past the start: the dragged end becomes the start, and the old start the end
        touch(MotionEvent.ACTION_MOVE, PointF(handle.x - 7 * cell, handle.y))
        assertEquals("pha b", view.selectedText())
        touch(MotionEvent.ACTION_UP, PointF(handle.x - 7 * cell, handle.y))
        val range = view.selection!!.normalized()
        assertEquals(2, range.startColumn)
        assertEquals(6, range.endColumn)
        assertTrue("Still showing", view.touchSelection.isActive)

        // Dragging the start handle moves the start, and nothing else
        val start = view.touchSelection.handleCenter(start = true)!!
        touch(MotionEvent.ACTION_DOWN, start)
        touch(MotionEvent.ACTION_MOVE, PointF(start.x - 2 * cell, start.y))
        touch(MotionEvent.ACTION_UP, PointF(start.x - 2 * cell, start.y))
        assertEquals("alpha b", view.selectedText())
    }

    @Test
    fun draggingAHandleToTheTopEdge_scrollsTheHistoryAtASteadyRate() {
        connectedShowing((1..200).joinToString("") { "line $it\r\n" })
        longPressAndLift(center(1, 3))
        assertEquals("line", view.selectedText())

        val handle = view.touchSelection.handleCenter(start = true)!!
        touch(MotionEvent.ACTION_DOWN, handle)
        touch(MotionEvent.ACTION_MOVE, PointF(handle.x, 2f))
        assertEquals(0, view.topRow)
        waitFor(500)
        assertTrue("${view.topRow} after half a second", view.topRow in -11..-9)
        assertTrue("The start went up into the history with it", view.selection!!.normalized().startRow < 0)
        touch(MotionEvent.ACTION_UP, PointF(handle.x, 2f))
        val stopped = view.topRow
        waitFor(500)
        assertEquals("Lifting the finger stops it", stopped, view.topRow)
    }

    /**
     * Whether the toolbar is on screen. Android keeps that in the action mode's FloatingToolbar,
     * which has no public accessor, so this reads it by reflection (SDK 34's field and methods).
     */
    private fun toolbarShowing(): Boolean {
        val mode = view.touchSelection.toolbar ?: return false
        val toolbar = mode.javaClass.getDeclaredField("mFloatingToolbar").apply { isAccessible = true }.get(mode)!!
        val showing = toolbar.javaClass.getMethod("isShowing").invoke(toolbar) as Boolean
        val hidden = toolbar.javaClass.getMethod("isHidden").invoke(toolbar) as Boolean
        return showing && !hidden
    }

    @Test
    fun theToolbar_hidesWhileAFingerScrollsOrDragsAHandle_andComesBackAfterwards() {
        connectedShowing((1..200).joinToString("") { "line $it\r\n" })
        longPressAndLift(center(1, 3))
        waitFor(100)
        assertTrue("Shown after the long-press", toolbarShowing())

        val at = center(30, 10)
        val row = rowHeight()
        touch(MotionEvent.ACTION_DOWN, at)
        touch(MotionEvent.ACTION_MOVE, PointF(at.x, at.y + row))
        touch(MotionEvent.ACTION_MOVE, PointF(at.x, at.y + 3 * row))
        assertFalse("Hidden while scrolling", toolbarShowing())
        touch(MotionEvent.ACTION_UP, PointF(at.x, at.y + 3 * row))
        waitFor(100)
        assertTrue("Back after the scroll", toolbarShowing())
        assertEquals("The selection kept its text", "line", view.selectedText())

        val handle = view.touchSelection.handleCenter(start = false)!!
        touch(MotionEvent.ACTION_DOWN, handle)
        touch(MotionEvent.ACTION_MOVE, PointF(handle.x + 3 * cellWidth(), handle.y))
        assertFalse("Hidden while a handle moves", toolbarShowing())
        touch(MotionEvent.ACTION_UP, PointF(handle.x + 3 * cellWidth(), handle.y))
        waitFor(100)
        assertTrue("Back after the drag", toolbarShowing())
    }

    @Test
    fun handlesAtTheEdges_turnOver_soNoneIsCutOff() {
        connectedShowing("hello world\r\n")
        // Select all from a long-press on blank space: the start at the top-left corner, the end
        // at the bottom-right one, where a handle hanging outward would leave the view
        longPressAndLift(center(40, 6))
        choose(android.R.id.selectAll)
        val radius = 11f * activity.resources.displayMetrics.density
        for (start in listOf(true, false)) {
            val handle = view.touchSelection.handleCenter(start)!!
            assertTrue("${if (start) "start" else "end"} handle at $handle in ${view.width}x${view.height}",
                handle.x - radius >= 0f && handle.x + radius <= view.width && handle.y - radius >= 0f && handle.y + radius <= view.height)
        }
    }

    @Test
    fun aTap_putsTheSelectionAway_withoutTheKeyboard() {
        val shell = connectedShowing("hello world\r\n")
        longPressAndLift(center(1, 0))
        val toolbar = view.touchSelection.toolbar!!

        tap(center(20, 3))
        assertNull(view.selection)
        assertFalse(view.touchSelection.isActive)
        assertNull(view.touchSelection.toolbar)
        assertEquals("The toolbar closed", listOf(toolbar), finished)
        assertFalse(keyboardShown())

        tap(center(20, 3))
        assertTrue("With nothing selected a tap brings up the keyboard, as before", keyboardShown())
        assertEquals("", shell.typed())
    }

    @Test
    fun copy_putsTheSelectionOnTheClipboard_andClosesTheToolbar() {
        connectedShowing("cd /var/log\r\n")
        longPressAndLift(center(5, 0))
        assertEquals("/var/log", view.selectedText())
        val toolbar = view.touchSelection.toolbar!!

        choose(android.R.id.copy)
        assertEquals("/var/log", clipboard().primaryClip!!.getItemAt(0).text.toString())
        assertNull(view.selection)
        assertNull(view.touchSelection.toolbar)
        assertEquals(listOf(toolbar), finished)
    }

    @Test
    fun typing_clearsTheSelection_andClosesTheToolbar() {
        val shell = connectedShowing("hello world\r\n")
        longPressAndLift(center(1, 0))
        val toolbar = view.touchSelection.toolbar!!

        view.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0))
        assertEquals("a", shell.typed())
        assertNull(view.selection)
        assertFalse(view.touchSelection.isActive)
        assertNull("No handles", view.touchSelection.handleCenter(start = true))
        assertEquals(listOf(toolbar), finished)
    }

    @Test
    fun typing_closesTheToolbarOfALongPressOnBlankSpace_too() {
        val shell = connectedShowing("hello world\r\n")
        longPressAndLift(center(40, 6))
        assertNull("Nothing selected there", view.selection)
        val toolbar = view.touchSelection.toolbar!!

        view.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0))
        assertEquals("a", shell.typed())
        assertFalse(view.touchSelection.isActive)
        assertEquals(listOf(toolbar), finished)
    }

    @Test
    fun aLongPressOnBlankSpace_selectsNothing_andOffersPasteAndSelectAll() {
        val shell = connectedShowing("hello world\r\n")
        clipboard().setPrimaryClip(ClipData.newPlainText("test", "uptime"))

        longPress(center(40, 6))
        assertNull(view.selection)
        assertEquals(HapticFeedbackConstants.LONG_PRESS, lastHaptic())
        assertTrue(view.touchSelection.isActive)
        assertFalse("Nothing to copy", item(android.R.id.copy).isVisible)
        assertTrue(item(android.R.id.paste).isEnabled)
        assertTrue(item(android.R.id.selectAll).isVisible)
        touch(MotionEvent.ACTION_UP, center(40, 6))

        // Select all selects everything and keeps the toolbar, now with Copy
        val toolbar = view.touchSelection.toolbar!!
        choose(android.R.id.selectAll)
        val emulator = connection.emulator
        assertEquals(
            TerminalSelection(0, -emulator.screen.activeTranscriptRows, emulator.mColumns - 1, emulator.mRows - 1),
            view.selection
        )
        assertSame(toolbar, view.touchSelection.toolbar)
        assertTrue(item(android.R.id.copy).isVisible)

        // Paste types what is on the clipboard and puts the selection away
        choose(android.R.id.paste)
        assertEquals("uptime", shell.typed())
        assertNull(view.selection)
        assertNull(view.touchSelection.toolbar)
        assertFalse(view.touchSelection.isActive)
    }

    @Test
    fun paste_isOfferedOnlyWhileConnected_withTextOnTheClipboard() {
        val shell = connectedShowing("hello world\r\n")
        longPressAndLift(center(1, 0))
        assertFalse("Nothing on the clipboard", item(android.R.id.paste).isEnabled)
        // Put away first: next to a selection, a press can land on a handle's 48dp target
        tap(center(20, 3))

        clipboard().setPrimaryClip(ClipData.newPlainText("test", "ls"))
        longPressAndLift(center(7, 0))
        assertTrue(item(android.R.id.paste).isEnabled)
        tap(center(20, 3))

        shell.end(null)
        main.drain()
        longPressAndLift(center(1, 0))
        assertEquals("Selecting still works once disconnected", "hello", view.selectedText())
        assertFalse("Nowhere to paste to", item(android.R.id.paste).isEnabled)
    }

    @Test
    fun theMouse_takesOverFromTouchSelection() {
        connectedShowing("hello world\r\n")
        longPressAndLift(center(1, 0))
        var menuAt: Pair<Float, Float>? = null
        view.onContextMenu = { x, y -> menuAt = x to y }

        view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, 300f, 100f, MotionEvent.BUTTON_SECONDARY))
        assertEquals(300f to 100f, menuAt)
        assertEquals("A right click keeps the selection for the menu's Copy", "hello", view.selectedText())
        assertFalse(view.touchSelection.isActive)
        assertNull(view.touchSelection.toolbar)
        assertNull("No handles", view.touchSelection.handleCenter(start = false))

        longPressAndLift(center(7, 0))
        assertTrue(view.touchSelection.isActive)
        view.onTouchEvent(mouse(MotionEvent.ACTION_DOWN, 300f, 100f, MotionEvent.BUTTON_PRIMARY))
        assertNull("A left click starts afresh", view.selection)
        assertFalse(view.touchSelection.isActive)
        assertNull(view.touchSelection.toolbar)
    }

    @Test
    fun theView_tellsWhenTouchSelectionShowsAndGoes_andEndingItLeavesOtherSelections() {
        connectedShowing("hello world\r\n")
        val changes = mutableListOf<Boolean>()
        view.onTouchSelectionChange = { changes += it }

        longPressAndLift(center(1, 0))
        assertEquals(listOf(true), changes)
        view.endTouchSelection()
        assertEquals("However it ends, it says so once", listOf(true, false), changes)
        assertNull(view.selection)
        assertNull(view.touchSelection.toolbar)

        // A long-press on blank space shows the toolbar alone, which counts too
        longPress(center(40, 6))
        touch(MotionEvent.ACTION_UP, center(40, 6))
        assertEquals(listOf(true, false, true), changes)
        tap(center(20, 3))
        assertEquals(listOf(true, false, true, false), changes)

        // A selection that isn't touch selection (the menu's Select all, the mouse) stays
        view.selectAll()
        view.endTouchSelection()
        assertNotNull(view.selection)
        assertEquals(listOf(true, false, true, false), changes)
    }

    // --- Output that scrolls, and another terminal, while a finger is down ---

    /** A full screen of "row N" lines, so that every new line scrolls it. */
    private fun fullScreen(): FakeSshConnector.FakeShell = connectedShowing((1..40).joinToString("") { "row $it\r\n" })

    private fun lineText(row: Int): String {
        val emulator = connection.emulator
        return emulator.screen.getSelectedText(0, row, emulator.mColumns - 1, row).trimEnd()
    }

    @Test
    fun holdingAHandle_whileOutputScrolls_theEndThatStaysKeepsToItsText() {
        val shell = fullScreen()
        val line = 5
        val text = lineText(line)
        longPressAndLift(center(1, line))
        assertEquals("row", view.selectedText())
        val handle = view.touchSelection.handleCenter(start = false)!!
        touch(MotionEvent.ACTION_DOWN, handle)
        touch(MotionEvent.ACTION_MOVE, PointF(handle.x + 2 * cellWidth(), handle.y))
        val held = view.selectedText()

        // Three lines come in (tail -f, say) while the finger holds the handle
        shell.print("new 1\r\nnew 2\r\nnew 3\r\n")
        main.drain()
        assertEquals("The selection went up with its text", held, view.selectedText())

        // The finger moves on: the end that stays is still where that text went
        touch(MotionEvent.ACTION_MOVE, PointF(handle.x + 3 * cellWidth(), handle.y))
        val range = view.selection!!.normalized()
        assertEquals(line - 3, range.startRow)
        assertEquals(0, range.startColumn)
        assertTrue(view.selectedText()!!, view.selectedText()!!.startsWith(text))
        touch(MotionEvent.ACTION_UP, PointF(handle.x + 3 * cellWidth(), handle.y))
    }

    @Test
    fun extendingFromALongPress_whileOutputScrolls_keepsTheLongPressedWord() {
        val shell = fullScreen()
        val line = 5
        val text = lineText(line)
        longPress(center(1, line))
        assertEquals("row", view.selectedText())

        shell.print("new 1\r\nnew 2\r\nnew 3\r\n")
        main.drain()
        // Still held where it was on the screen, which now shows a later line
        touch(MotionEvent.ACTION_MOVE, center(2, line))
        val range = view.selection!!.normalized()
        assertEquals("From the long-pressed word, where its text went", line - 3, range.startRow)
        assertEquals(0, range.startColumn)
        assertTrue(view.selectedText()!!, view.selectedText()!!.startsWith(text))
    }

    @Test
    fun aLongPressOnBlankSpace_keepsItsToolbar_whileOutputScrolls() {
        val shell = fullScreen()
        longPressAndLift(center(40, 5))
        val toolbar = view.touchSelection.toolbar!!

        shell.print("new 1\r\nnew 2\r\n")
        main.drain()
        assertTrue(view.touchSelection.isActive)
        assertSame(toolbar, view.touchSelection.toolbar)
        assertTrue("Paste and Select all still offered", finished.isEmpty())
    }

    @Test
    fun aLongPressStillToCome_whenTheViewShowsAnotherTerminal_landsOnNeither() {
        connectedShowing("hello world\r\n")
        touch(MotionEvent.ACTION_DOWN, center(1, 0))

        // Another terminal, with a word where the finger is
        val other = TerminalConnection("other", connector, hostKeys, main::post) { it.run() }
        other.connect(TerminalConnectSpec("server", "server", 22, "me", SshCredentials.Password("secret")))
        main.drain()
        connector.shells.last().print("other words\r\n")
        main.drain()
        view.connection = other
        waitPastLongPress()
        assertNull(view.selection)
        assertFalse(view.touchSelection.isActive)
        assertTrue("No toolbar", started.isEmpty())

        touch(MotionEvent.ACTION_UP, center(1, 0))
        assertFalse("Nor a tap: the keyboard stays down", keyboardShown())
    }

    @Test
    fun aNewSize_orAnotherTerminal_endsTouchSelection() {
        connectedShowing("hello world\r\n")
        longPressAndLift(center(1, 0))
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 400, 480)
        assertNull(view.selection)
        assertFalse(view.touchSelection.isActive)
        assertNull(view.touchSelection.toolbar)

        longPressAndLift(center(1, 0))
        assertTrue(view.touchSelection.isActive)
        view.connection = TerminalConnection("other", connector, hostKeys, main::post) { it.run() }
        assertFalse(view.touchSelection.isActive)
        assertNull(view.touchSelection.toolbar)
    }

    @Test
    fun aStylus_selectsLikeAFinger() {
        connectedShowing("hello world\r\n")
        touch(MotionEvent.ACTION_DOWN, center(1, 0), tool = MotionEvent.TOOL_TYPE_STYLUS, source = InputDevice.SOURCE_STYLUS)
        waitPastLongPress()
        assertEquals("hello", view.selectedText())
        assertTrue(view.touchSelection.isActive)
    }

    @Test
    fun theTouchpadsFingers_neverLongPress() {
        connectedShowing("hello world\r\n")
        // A touchpad reports its fingers through a mouse source
        touch(MotionEvent.ACTION_DOWN, center(1, 0), source = InputDevice.SOURCE_MOUSE)
        waitPastLongPress()
        touch(MotionEvent.ACTION_UP, center(1, 0), source = InputDevice.SOURCE_MOUSE)
        assertNull(view.selection)
        assertFalse(view.touchSelection.isActive)
    }

    @Test
    fun aProgramThatAskedForTheMouse_neverGetsTouches() {
        val shell = connectedShowing("\u001b[?1000hhello world\r\n")
        assertTrue(connection.emulator.isMouseTrackingActive)

        longPressAndLift(center(1, 0))
        assertEquals("Touch selection works all the same", "hello", view.selectedText())
        tap(center(20, 3))
        tap(center(20, 3))
        assertTrue(keyboardShown())
        assertEquals("No clicks reported to the program", "", shell.typed())
    }

    // --- Handle colours ---

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color shr 16 and 0xFF) + 0.7152 * channel(color shr 8 and 0xFF) + 0.0722 * channel(color and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun theHandles_standOutFromEveryThemesBackground_atLeastThreeToOne() {
        val accents = AccentColor.entries.map { it.tones().darkPrimary.toArgb() } + TerminalTheme.DEFAULT_CURSOR
        // Colours a program may set for the cursor, or a wallpaper's scheme may give
        val others = listOf(0xFFFFFF00.toInt(), 0xFF808080.toInt(), 0xFF101010.toInt(), 0xFFFFFFFF.toInt(), 0xFF2F5DA8.toInt())
        for (theme in TerminalThemePreset.entries) {
            val background = theme.background.toInt()
            for (cursor in accents.map { theme.cursor?.toInt() ?: it } + others) {
                val color = TouchSelection.handleColor(cursor, background)
                val ratio = contrast(color, background)
                assertTrue("${theme.name}, cursor %08x: %.2f:1".format(cursor, ratio), ratio >= 3.0)
            }
        }
        // The theme's own colour wherever it already stands out
        val dracula = TerminalThemePreset.DRACULA
        assertEquals(dracula.cursor!!.toInt(), TouchSelection.handleColor(dracula.cursor!!.toInt(), dracula.background.toInt()))
        val accent = AccentColor.MAROON.tones().darkPrimary.toArgb()
        assertEquals(accent, TouchSelection.handleColor(accent, TerminalThemePreset.REMBROWSE.background.toInt()))
    }

    private fun mouse(action: Int, x: Float, y: Float, buttons: Int): MotionEvent {
        val time = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        return MotionEvent.obtain(time, time, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }
}
