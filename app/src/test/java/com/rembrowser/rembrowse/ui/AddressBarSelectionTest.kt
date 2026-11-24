package com.rembrowser.rembrowse.ui

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.engine.TouchpadInputFilter
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Selecting text in the address bar with a mouse and with a touchpad, the way desktop browsers
 * do: a click into the bar selects the address, a click in it places the caret, a double click
 * selects the word between the address's punctuation, a drag selects what it covers, a triple
 * click selects everything, and a right click opens Cut, Copy and Paste.
 *
 * Android reports a touchpad as a mouse source whose pointer is a finger; the activity hands such
 * presses on as a mouse (TouchpadInputFilter). Touchpad events here go through the same filter.
 * Real text measurement (native graphics) so that positions land on the letters they aim at.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class AddressBarSelectionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private val browserVm = BrowserViewModel(engine, FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val downloadsVm = DownloadsViewModel(
        storage = FakeDownloadStorage(),
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        ioDispatcher = Dispatchers.Unconfined
    )

    private class FakeClipboard : ClipboardManager {
        var copied: AnnotatedString? = null
        override fun getText(): AnnotatedString? = copied
        override fun setText(annotatedString: AnnotatedString) {
            copied = annotatedString
        }
    }

    private val clipboard = FakeClipboard()
    private val touchpadFilter = TouchpadInputFilter(scrollSlopPx = 16f)
    private val url = "https://www.example.com/some-path/page.html"

    private enum class Pointer { MOUSE, TOUCHPAD }

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch() {
        rule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false, LocalClipboardManager provides clipboard) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        val tab = browserVm.uiState.value.activeTabId!!
        rule.runOnUiThread {
            browserVm.navigatePane(0, url)
            engine.sessions.getValue(tab).simulatePageLoad(url, "Example")
        }
        rule.waitForIdle()
    }

    // --- Pointer input, as the window receives it ---

    private var now = SystemClock.uptimeMillis()
    private var downTime = 0L

    private fun event(action: Int, x: Float, y: Float, pointer: Pointer, buttons: Int): MotionEvent {
        val tool = if (pointer == Pointer.MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
        return MotionEvent.obtain(downTime, now, action, 1, properties, coords, 0, buttons, 1f, 1f, 1, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    private fun send(event: MotionEvent, pointer: Pointer) {
        val window = rule.activity.window.decorView
        rule.runOnUiThread {
            // What MainActivity does with everything the window receives
            if (pointer == Pointer.TOUCHPAD) touchpadFilter.filter(event) { window.dispatchTouchEvent(it) } else window.dispatchTouchEvent(event)
        }
        event.recycle()
    }

    private fun pause(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        now += millis
    }

    private fun click(at: Pair<Float, Float>, pointer: Pointer, buttons: Int = MotionEvent.BUTTON_PRIMARY, pauseAfter: Long = 60) {
        now += 10
        downTime = now
        send(event(MotionEvent.ACTION_DOWN, at.first, at.second, pointer, buttons), pointer)
        now += 40
        send(event(MotionEvent.ACTION_UP, at.first, at.second, pointer, 0), pointer)
        pause(pauseAfter)
    }

    private fun drag(from: Pair<Float, Float>, to: Pair<Float, Float>, pointer: Pointer) {
        now += 10
        downTime = now
        send(event(MotionEvent.ACTION_DOWN, from.first, from.second, pointer, MotionEvent.BUTTON_PRIMARY), pointer)
        var x = from.first
        while (x < to.first) {
            x = minOf(x + 6f, to.first)
            pause(16)
            send(event(MotionEvent.ACTION_MOVE, x, from.second, pointer, MotionEvent.BUTTON_PRIMARY), pointer)
        }
        pause(16)
        send(event(MotionEvent.ACTION_UP, to.first, from.second, pointer, 0), pointer)
        pause(400)
    }

    // --- The field ---

    private fun field() = rule.onNodeWithTag("omnibox_input").fetchSemanticsNode()
    private fun text() = field().config[SemanticsProperties.EditableText].text

    private fun selected(): String {
        rule.waitForIdle()
        val selection = field().config[SemanticsProperties.TextSelectionRange]
        return text().substring(selection.min, selection.max)
    }

    private fun selection(): TextRange {
        rule.waitForIdle()
        return field().config[SemanticsProperties.TextSelectionRange]
    }

    private fun isFocused() = field().config[SemanticsProperties.Focused]

    /** A point on the letter at [offset] of the address: its middle, or just inside its left edge. */
    private fun at(offset: Int, leftEdge: Boolean = false): Pair<Float, Float> {
        val node = field()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val box = layouts.first().getBoundingBox(offset)
        val bounds = node.boundsInWindow
        val x = bounds.left + if (leftEdge) box.left + 1f else box.center.x
        return x to (bounds.top + bounds.height / 2)
    }

    private fun word(word: String) = at(text().indexOf(word) + 1)

    // --- Clicks ---

    private fun clickIntoTheBarSelectsTheAddress_thenAClickPlacesTheCaret(pointer: Pointer) {
        launch()
        click(word("some"), pointer, pauseAfter = 400)
        assertTrue(isFocused())
        assertEquals("Ready to replace, as in desktop browsers", url, selected())

        click(word("some"), pointer, pauseAfter = 400)
        assertEquals("The caret where it was clicked", TextRange(url.indexOf("some") + 1), selection())
    }

    @Test
    fun mouse_clickIntoTheBarSelectsTheAddress_thenAClickPlacesTheCaret() =
        clickIntoTheBarSelectsTheAddress_thenAClickPlacesTheCaret(Pointer.MOUSE)

    @Test
    fun touchpad_clickIntoTheBarSelectsTheAddress_thenAClickPlacesTheCaret() =
        clickIntoTheBarSelectsTheAddress_thenAClickPlacesTheCaret(Pointer.TOUCHPAD)

    private fun doubleClickSelectsTheWordBetweenPunctuation(pointer: Pointer) {
        launch()
        // Straight away on the unfocused bar
        click(word("some"), pointer)
        click(word("some"), pointer, pauseAfter = 400)
        assertEquals("some", selected())

        click(word("example"), pointer)
        click(word("example"), pointer, pauseAfter = 400)
        assertEquals("example", selected())

        click(word("page"), pointer)
        click(word("page"), pointer, pauseAfter = 400)
        assertEquals("page", selected())
    }

    @Test
    fun mouse_doubleClickSelectsTheWordBetweenPunctuation() = doubleClickSelectsTheWordBetweenPunctuation(Pointer.MOUSE)

    @Test
    fun touchpad_doubleClickSelectsTheWordBetweenPunctuation() = doubleClickSelectsTheWordBetweenPunctuation(Pointer.TOUCHPAD)

    private fun tripleClickSelectsEverything(pointer: Pointer) {
        launch()
        click(word("some"), pointer, pauseAfter = 400)
        click(word("some"), pointer, pauseAfter = 400)
        click(word("some"), pointer)
        click(word("some"), pointer)
        click(word("some"), pointer, pauseAfter = 400)
        assertEquals(url, selected())
    }

    @Test
    fun mouse_tripleClickSelectsEverything() = tripleClickSelectsEverything(Pointer.MOUSE)

    @Test
    fun touchpad_tripleClickSelectsEverything() = tripleClickSelectsEverything(Pointer.TOUCHPAD)

    // --- Drags ---

    private fun dragSelectsWhatItCovers(pointer: Pointer) {
        launch()
        // From the unfocused bar: the press that focuses it is not undone by "select all"
        drag(at(url.indexOf("example"), leftEdge = true), at(url.indexOf(".com"), leftEdge = true), pointer)
        assertEquals("example", selected())

        drag(at(url.indexOf("some"), leftEdge = true), at(url.indexOf("/page"), leftEdge = true), pointer)
        assertEquals("some-path", selected())
    }

    @Test
    fun mouse_dragSelectsWhatItCovers() = dragSelectsWhatItCovers(Pointer.MOUSE)

    @Test
    fun touchpad_dragSelectsWhatItCovers() = dragSelectsWhatItCovers(Pointer.TOUCHPAD)

    // --- Focus from the keyboard ---

    @Test
    fun focusFromTheKeyboardSelectsTheAddress() {
        launch()
        rule.onNodeWithTag("omnibox_input").performSemanticsAction(SemanticsActions.RequestFocus)
        assertEquals(url, selected())
    }

    // --- Right-click menu ---

    /**
     * The bar's own menu, and not Compose's text menu as well (Compose 1.11 opens one on a right
     * click in any text field, in a popup of its own)
     */
    private fun assertOnlyTheBarsMenuIsOpen() {
        rule.onNodeWithTag("address_menu").assertExists()
        assertEquals(1, rule.onAllNodes(isPopup()).fetchSemanticsNodes().size)
    }

    @Test
    fun rightClick_copiesCutsAndPastes() {
        launch()
        click(word("example"), Pointer.TOUCHPAD)
        click(word("example"), Pointer.TOUCHPAD, pauseAfter = 400)
        assertEquals("example", selected())

        // A two-finger tap: the touchpad's right click
        click(word("example"), Pointer.TOUCHPAD, buttons = MotionEvent.BUTTON_SECONDARY, pauseAfter = 400)
        assertEquals("The selection stays for the menu to act on", "example", selected())
        assertOnlyTheBarsMenuIsOpen()
        rule.onNodeWithTag("address_menu_copy").performClick()
        assertEquals("example", clipboard.copied?.text)
        rule.onNodeWithTag("address_menu").assertDoesNotExist()
        assertTrue("The bar keeps focus and selection", isFocused())
        assertEquals("example", selected())

        clipboard.copied = AnnotatedString("mozilla\n")
        click(word("example"), Pointer.MOUSE, buttons = MotionEvent.BUTTON_SECONDARY, pauseAfter = 400)
        assertOnlyTheBarsMenuIsOpen()
        rule.onNodeWithTag("address_menu_paste").performClick()
        rule.waitForIdle()
        assertEquals("https://www.mozilla.com/some-path/page.html", text())
        assertEquals(text(), browserVm.uiState.value.addressBarInput)

        click(word("mozilla"), Pointer.MOUSE)
        click(word("mozilla"), Pointer.MOUSE, pauseAfter = 400)
        click(word("mozilla"), Pointer.MOUSE, buttons = MotionEvent.BUTTON_SECONDARY, pauseAfter = 400)
        rule.onNodeWithTag("address_menu_cut").performClick()
        rule.waitForIdle()
        assertEquals("mozilla", clipboard.copied?.text)
        assertEquals("https://www..com/some-path/page.html", text())

        click(word("some"), Pointer.MOUSE, buttons = MotionEvent.BUTTON_SECONDARY, pauseAfter = 400)
        rule.onNodeWithTag("address_menu_select_all").performClick()
        assertEquals(text(), selected())
    }

    @Test
    fun rightClick_onTheUnfocusedBar_selectsTheAddress_andOffersPasteAndGo() {
        launch()
        clipboard.copied = AnnotatedString("https://mozilla.org/")
        click(word("some"), Pointer.MOUSE, buttons = MotionEvent.BUTTON_SECONDARY, pauseAfter = 400)
        assertTrue(isFocused())
        assertEquals(url, selected())
        assertOnlyTheBarsMenuIsOpen()

        rule.onNodeWithTag("address_menu_paste_and_go").performClick()
        rule.waitForIdle()
        val tab = browserVm.uiState.value.activeTabId!!
        assertEquals("https://mozilla.org/", engine.sessions.getValue(tab).loadedUrls.last())
    }

    @Test
    fun rightClick_withNothingSelected_cannotCutOrCopy() {
        launch()
        click(word("some"), Pointer.MOUSE, pauseAfter = 400)
        click(word("some"), Pointer.MOUSE, pauseAfter = 400)
        click(word("some"), Pointer.MOUSE, buttons = MotionEvent.BUTTON_SECONDARY, pauseAfter = 400)
        rule.onNodeWithTag("address_menu_cut").assertIsNotEnabled()
        rule.onNodeWithTag("address_menu_copy").assertIsNotEnabled()
        rule.onNodeWithTag("address_menu_paste").assertIsNotEnabled()
    }
}
