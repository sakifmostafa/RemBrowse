package com.rembrowser.rembrowse

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.rembrowser.rembrowse.engine.PaneGeckoView
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.terminal.TerminalScreenView
import com.rembrowser.rembrowse.ui.BrowserChromeState
import com.rembrowser.rembrowse.util.BrowserShortcut
import com.rembrowser.rembrowse.util.KeyboardShortcuts
import com.rembrowser.rembrowse.util.PhysicalKeyRouter
import com.rembrowser.rembrowse.util.PhysicalTyping
import com.rembrowser.rembrowse.util.ScreenCaptureKey
import com.rembrowser.rembrowse.util.TakenKeys
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel

/**
 * The window's hardware keys, before the focused view has them: the browser's shortcuts, Esc for
 * the tab rail over the page and for the find bar, the screenshot overlay's keys, and Enter for
 * a focused page. MainActivity hands it every key twice: before the keyboard app (input method)
 * sees it ([beforeIme], from the window's root layout) and from its own dispatchKeyEvent
 * ([dispatch]), with the view that has the focus: [windowContent] and [dispatchKeyEvent] are
 * those two hooks. Kept apart from the activity, which needs the engine, so tests drive the same
 * keys through the screen they compose.
 */
internal class BrowserKeys(
    private val browser: BrowserViewModel,
    private val chrome: BrowserChromeState
) {
    // An Enter press that submitted a text field must not also deliver its key release to
    // whatever receives focus next (the page, or a button).
    private var swallowEnterKeyUp = false

    /**
     * Presses taken by a shortcut or by the screenshot overlay: their repeats and their release
     * are taken too, and nothing after them (see [TakenKeys]).
     */
    private val takenKeys = TakenKeys()

    /** The key (Esc, or a keyboard's Back) that closed the find bar; its release is ours too. */
    private var escapeTakenForFindBar = KeyEvent.KEYCODE_UNKNOWN

    /** A physical keyboard's Enter, sent to the focused web page before the keyboard app sees it. */
    private val keyRouter = PhysicalKeyRouter()

    /**
     * The window's content: [content] in a layout that offers every key to [beforeIme], with the
     * view [focus] gives, before the keyboard app sees it (see [ShortcutKeysLayout]).
     */
    fun windowContent(content: View, focus: () -> View?): ViewGroup =
        ShortcutKeysLayout(content.context) { beforeIme(it, focus()) }.apply { addView(content) }

    /**
     * The activity's dispatchKeyEvent: [dispatch] first, with the view that has the [focus], and
     * what it leaves to [toViews], the activity's own dispatch to the focused view.
     */
    fun dispatchKeyEvent(event: KeyEvent, focus: View?, toViews: (KeyEvent) -> Boolean): Boolean =
        dispatch(event, focus) || toViews(event)

    /** A terminal has the keyboard: the shortcuts, Esc and the shortcut sheet all ask this. */
    fun terminalHasKeyboard(focus: View?): Boolean = focus is TerminalScreenView

    /**
     * The activity's dispatchKeyEvent, before the views get [event]: true when it was taken here.
     * Shortcuts are read here, before the focused view, so they also work while a web page has
     * focus.
     */
    fun dispatch(event: KeyEvent, focus: View?): Boolean {
        forgetMissedEnterRelease(event)
        if (takenKeys.takesFollowUp(event.action, event.keyCode, event.repeatCount)) return true
        if (interceptKeyForScreenCapture(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                swallowEnterKeyUp = browser.uiState.value.isAddressBarFocused || chrome.isTabSearchFocused
            } else if (event.action == KeyEvent.ACTION_UP && (swallowEnterKeyUp || chrome.swallowNextEnterUp)) {
                swallowEnterKeyUp = false
                chrome.swallowNextEnterUp = false
                return true
            }
        }
        if (interceptEscapeForTabRail(event)) return true
        if (interceptEscapeForFindBar(event, focus)) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            val shortcut = resolveShortcut(event, focus)
            if (shortcut != null) {
                takenKeys.take(event.keyCode)
                performShortcut(shortcut)
                return true
            }
        }
        return false
    }

    /**
     * Shortcuts, read before the keyboard app (input method). With a text box focused, keys reach
     * the keyboard app first, and one that handles physical keys may type them itself, or pass
     * them back through the text box's input connection, which for a web page's text box goes
     * straight to the page (GeckoView's `GeckoInputConnection.sendKeyEvent`): either way the
     * activity never saw Ctrl+T. [dispatch] still reads them for windows without this layout.
     */
    fun beforeIme(event: KeyEvent, focus: View?): Boolean {
        forgetMissedEnterRelease(event)
        // The repeats and release of a shortcut's key, or of a key the screenshot overlay took
        if (takenKeys.takesFollowUp(event.action, event.keyCode, event.repeatCount)) return true
        if (interceptKeyForScreenCapture(event)) return true
        if (interceptEscapeForTabRail(event)) return true
        if (interceptEscapeForFindBar(event, focus)) return true
        if (interceptTypingBeforeIme(event, focus)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val shortcut = resolveShortcut(event, focus) ?: return false
        takenKeys.take(event.keyCode)
        performShortcut(shortcut)
        return true
    }

    /**
     * Esc closes the expanded tab rail open over the page (see
     * [KeyboardShortcuts.escapeClosesTabRail]), wherever the focus is: a page or a terminal under
     * it never sees the key. Its release and repeats are taken too ([takenKeys]). The keyboard
     * goes back to where it was before the rail opened ([BrowserChromeState.closeTabRailOverlay]).
     */
    private fun interceptEscapeForTabRail(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !chrome.tabRailOverlayOpen) return false
        val closes = KeyboardShortcuts.escapeClosesTabRail(
            keyCode = event.keyCode,
            railOverlayOpen = true,
            noModifiers = event.hasNoModifiers(),
            repeatCount = event.repeatCount
        )
        if (!closes) return false
        takenKeys.take(event.keyCode)
        chrome.closeTabRailOverlay()
        return true
    }

    /**
     * Esc closes the find bar (see [KeyboardShortcuts.escapeClosesFindBar]) and puts the focus
     * back on the page. Taken before the keyboard app: with a physical keyboard, Gboard and
     * Android may keep Esc or turn it into Back, and the bar's own text box never saw it.
     */
    private fun interceptEscapeForFindBar(event: KeyEvent, focus: View?): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ESCAPE) return false
        if (event.keyCode == escapeTakenForFindBar) {
            // Its release, or auto-repeat while it is held. A fresh press (the release was
            // missed) is looked at anew below.
            if (event.action == KeyEvent.ACTION_UP) {
                escapeTakenForFindBar = KeyEvent.KEYCODE_UNKNOWN
                return true
            }
            if (event.repeatCount > 0) return true
            escapeTakenForFindBar = KeyEvent.KEYCODE_UNKNOWN
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val browserState = browser.uiState.value
        val closes = KeyboardShortcuts.escapeClosesFindBar(
            keyCode = event.keyCode,
            findBarOpen = browser.findInPage.value != null,
            noModifiers = event.hasNoModifiers(),
            repeatCount = event.repeatCount,
            inTerminal = terminalHasKeyboard(focus),
            escapeTakenInFront = browserState.isAddressBarFocused || chrome.isTabSearchFocused ||
                chrome.showTabSwitcher || browserState.fullscreenTabId != null
        )
        if (!closes) return false
        escapeTakenForFindBar = event.keyCode
        browser.closeFindInPage()
        chrome.focusContent()
        return true
    }

    /**
     * While the screenshot overlay is up, Esc cancels it and Enter takes the focused pane, and no
     * other key press reaches the page or terminal underneath (see [KeyboardShortcuts.screenCaptureKey]).
     * Read before the keyboard app, which may keep Esc for itself or turn it into Back. A press
     * taken here is followed by its release (see [takenKeys]), even after the overlay has gone.
     * Releases of keys held down from before (Ctrl and Alt of Ctrl+Alt+S) still go where they go.
     */
    private fun interceptKeyForScreenCapture(event: KeyEvent): Boolean {
        val capture = chrome.screenCapture
        if (!capture.isSelecting || event.action != KeyEvent.ACTION_DOWN) return false
        val action = KeyboardShortcuts.screenCaptureKey(keyCode = event.keyCode, isSystemKey = event.isSystem)
        if (action == ScreenCaptureKey.PASS) return false
        // A key held from before the overlay keeps repeating: kept from the page, but not taken,
        // so its release still reaches the page that saw it go down
        if (event.repeatCount > 0) return true
        takenKeys.take(event.keyCode)
        when (action) {
            // The screen gives the keyboard back to whatever had it
            ScreenCaptureKey.CANCEL -> capture.cancel()
            // The overlay knows where the panes are, so it does the taking
            ScreenCaptureKey.CAPTURE_FOCUSED -> capture.requestFocusedCapture()
            else -> Unit
        }
        return true
    }

    /**
     * A popup that ran its row on Enter ([BrowserChromeState.swallowNextEnterUp]) waits for that
     * press's release here. When the row opened a dialog, the release went to the dialog instead;
     * a fresh press of Enter in this window shows it is not coming, so it is forgotten and this
     * press's release reaches the page.
     */
    private fun forgetMissedEnterRelease(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && PhysicalTyping.isEnter(event.keyCode)) {
            chrome.swallowNextEnterUp = false
        }
    }

    /**
     * A physical keyboard's Enter goes to the focused web page as the key itself, before the keyboard
     * app can turn it into a typed line break (see [PhysicalKeyRouter] and [PhysicalTyping]).
     * The release of an Enter that submitted the address bar or a popup is swallowed, as in
     * [dispatch]: focus has moved to the page by then, and it must not see half a key press.
     */
    private fun interceptTypingBeforeIme(event: KeyEvent, focus: View?): Boolean =
        keyRouter.route(event, focus as? PaneGeckoView) {
            val swallow = swallowEnterKeyUp || chrome.swallowNextEnterUp
            swallowEnterKeyUp = false
            chrome.swallowNextEnterUp = false
            swallow
        }

    private fun resolveShortcut(event: KeyEvent, focus: View?): BrowserShortcut? = KeyboardShortcuts.resolve(
        keyCode = event.keyCode,
        ctrl = event.isCtrlPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
        meta = event.isMetaPressed,
        repeatCount = event.repeatCount,
        inTerminal = terminalHasKeyboard(focus)
    )

    private fun performShortcut(shortcut: BrowserShortcut) {
        // The rail over the page steps aside first, so the shortcut lands where it would without it;
        // the keyboard goes back where it was, unless the shortcut puts it somewhere itself
        if (KeyboardShortcuts.closesTabRail(shortcut)) {
            chrome.closeTabRailOverlay(giveFocusBack = !KeyboardShortcuts.placesFocus(shortcut))
        }
        when (shortcut) {
            BrowserShortcut.NEW_TAB -> {
                browser.openNewTab()
                chrome.focusAddressBar()
            }
            BrowserShortcut.NEW_TERMINAL -> {
                // Opens on the host picker, as More -> New terminal does
                chrome.showTabSwitcher = false
                browser.openTerminal()
            }
            BrowserShortcut.CLOSE_TAB -> browser.uiState.value.activeTabId?.let { browser.closeTab(it) }
            BrowserShortcut.FOCUS_ADDRESS_BAR -> chrome.focusAddressBar()
            BrowserShortcut.RELOAD -> browser.reloadOrStop()
            BrowserShortcut.BACK -> browser.goBack()
            BrowserShortcut.FORWARD -> browser.goForward()
            BrowserShortcut.NEXT_TAB -> browser.selectAdjacentTab(1)
            BrowserShortcut.PREVIOUS_TAB -> browser.selectAdjacentTab(-1)
            BrowserShortcut.TOGGLE_BOOKMARK -> browser.toggleBookmarkForActiveTab()
            BrowserShortcut.COMMAND_PALETTE -> chrome.showCommandPalette = true
            BrowserShortcut.GO_TO_ADDRESS -> chrome.toggleGoToAddress()
            BrowserShortcut.OPEN_SETTINGS -> {
                chrome.showTabSwitcher = false
                browser.openSettings()
            }
            BrowserShortcut.OPEN_DOWNLOADS -> {
                chrome.showTabSwitcher = false
                browser.openSettings(SettingsSection.DOWNLOADS)
            }
            BrowserShortcut.OPEN_HISTORY -> {
                chrome.showTabSwitcher = false
                browser.openSettings(SettingsSection.HISTORY)
            }
            BrowserShortcut.TOGGLE_SPLIT_VIEW -> if (browser.uiState.value.isSplit) {
                browser.setLayoutMode(LayoutMode.SINGLE_1X1)
            } else {
                browser.splitCurrentTab(currentOnLeft = true)
            }
            BrowserShortcut.LAYOUT_SINGLE -> browser.setLayoutMode(LayoutMode.SINGLE_1X1)
            BrowserShortcut.LAYOUT_SIDE_BY_SIDE -> browser.splitCurrentTab(currentOnLeft = true)
            BrowserShortcut.LAYOUT_STACKED -> browser.setLayoutMode(LayoutMode.SPLIT_2X1)
            BrowserShortcut.LAYOUT_GRID -> browser.setLayoutMode(LayoutMode.GRID_2X2)
            BrowserShortcut.LAYOUT_ONE_AND_TWO -> browser.setLayoutMode(LayoutMode.SPLIT_1_2)
            BrowserShortcut.FIND_IN_PAGE -> {
                chrome.showTabSwitcher = false
                browser.openFindInPage()
            }
            BrowserShortcut.PRINT -> {
                chrome.showTabSwitcher = false
                browser.printPage()
            }
            // Over whatever is showing, the tab switcher included
            BrowserShortcut.SCREENSHOT -> chrome.screenCapture.start()
            // With the bar closed, F3 opens it, as in a desktop browser
            BrowserShortcut.FIND_NEXT ->
                if (browser.findInPage.value == null) browser.openFindInPage() else browser.findNext()
            BrowserShortcut.FIND_PREVIOUS ->
                if (browser.findInPage.value == null) browser.openFindInPage() else browser.findPrevious()
        }
    }
}

/**
 * The root of the window's content. Before a key reaches the keyboard app (input method), Android
 * passes it down the focused views' ancestors (`dispatchKeyEventPreIme`); this layout offers it to
 * [interceptBeforeIme] first, so browser shortcuts work in any text box, a web page's included.
 */
@SuppressLint("ViewConstructor")
private class ShortcutKeysLayout(
    context: Context,
    private val interceptBeforeIme: (KeyEvent) -> Boolean
) : FrameLayout(context) {
    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
        interceptBeforeIme(event) || super.dispatchKeyEventPreIme(event)
}
