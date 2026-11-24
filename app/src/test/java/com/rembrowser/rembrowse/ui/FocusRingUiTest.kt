package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.components.ScreenshotNotice
import com.rembrowser.rembrowse.ui.components.ScreenshotNoticeBar
import com.rembrowser.rembrowse.ui.components.ScreenshotResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The toolbar with a keyboard: Tab walks its buttons in the order they are drawn, left to right,
 * and each one can take the focus and show its focus ring, which is drawn only while the keyboard
 * is in use.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class FocusRingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browserVm = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val downloadsVm = DownloadsViewModel(
        storage = FakeDownloadStorage(),
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        ioDispatcher = Dispatchers.Unconfined
    )
    private lateinit var inputModes: InputModeManager
    private val chrome = BrowserChromeState()
    private var primary = Color.Unspecified

    /** The theme the screen is drawn in: dark, as by default, or light. */
    private val darkTheme = mutableStateOf(true)

    /**
     * The input mode the screen's composables see, when set; otherwise the window's. Asking the
     * window for touch mode again after the keyboard one changes nothing here (a real touch on the
     * screen is what does it), so this stands in for it.
     */
    private val shownInputMode = mutableStateOf<InputMode?>(null)

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launchWithKeyboard() {
        browserVm.openNewTab("https://one.example/")
        composeRule.setContent {
            inputModes = LocalInputModeManager.current
            val window = inputModes
            val shown = remember {
                object : InputModeManager {
                    override val inputMode: InputMode get() = shownInputMode.value ?: window.inputMode
                    override fun requestInputMode(inputMode: InputMode): Boolean = window.requestInputMode(inputMode)
                }
            }
            val dark by darkTheme
            CompositionLocalProvider(LocalSiteIconsEnabled provides false, LocalInputModeManager provides shown) {
                RemBrowseTheme(darkTheme = dark) {
                    primary = MaterialTheme.colorScheme.primary
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        chrome = chrome,
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier -> Box(modifier) { Text(tab.displayTitle) } },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        composeRule.runOnIdle { inputModes.requestInputMode(InputMode.Keyboard) }
        composeRule.runOnIdle { assertEquals(InputMode.Keyboard, inputModes.inputMode) }
    }

    /** The clickable node of an icon button, which is the one that takes the focus. */
    private fun button(label: String): SemanticsNodeInteraction =
        composeRule.onNode(hasContentDescription(label) and hasClickAction())

    private fun SemanticsNodeInteraction.tab(shift: Boolean = false) = performKeyInput {
        if (shift) withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } else pressKey(Key.Tab)
    }

    @Test
    fun wideWindow_tabWalksBookmarkMoreAndSplitInOrder() {
        launchWithKeyboard()

        button("Bookmark this page").requestFocus().assertIsFocused()
        button("Bookmark this page").tab()
        button("More").assertIsFocused()
        button("More").tab()
        composeRule.onNodeWithTag("split_button").assertIsFocused()

        // And back again; Enter presses the focused button
        composeRule.onNodeWithTag("split_button").tab(shift = true)
        button("More").assertIsFocused()
        button("More").performKeyInput { pressKey(Key.Enter) }
        composeRule.onNodeWithTag("menu_settings").assertIsDisplayed()
    }

    /**
     * The screen's pixel 1dp inside the middle of [node]'s top edge: where the outer line of its
     * ring runs (Material's inset ring, 2dp wide along the edge).
     */
    private fun pixelOnTheRing(node: SemanticsNodeInteraction): Color {
        composeRule.waitForIdle()
        val semantics = node.fetchSemanticsNode()
        val ringOffset = with(composeRule.density) { 1.dp.toPx() }
        val x = semantics.positionOnScreen.x + semantics.size.width / 2f
        val y = semantics.positionOnScreen.y + ringOffset
        // The whole display, as UiSnapshotTest renders it
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        return Color(screen.getPixel(x.roundToInt(), y.roundToInt()))
    }

    private fun Color.isCloseTo(other: Color): Boolean =
        abs(red - other.red) < 0.05f && abs(green - other.green) < 0.05f && abs(blue - other.blue) < 0.05f

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theRingIsDrawnForTheKeyboard_andNotForTouch() {
        launchWithKeyboard()
        val more = button("More")
        val unfocused = pixelOnTheRing(more)
        assertFalse("Nothing drawn there before it has the focus", unfocused.isCloseTo(primary))

        more.requestFocus().assertIsFocused()
        composeRule.waitForIdle()
        assertTrue("The ring, in the accent colour", pixelOnTheRing(more).isCloseTo(primary))

        // Touch use hides it (and the button, focusable only for the keyboard, gives up the focus)
        shownInputMode.value = InputMode.Touch
        composeRule.waitForIdle()
        assertEquals(unfocused, pixelOnTheRing(more))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onANotice_theRingTakesTheInverseAccent() {
        var colors: ColorScheme? = null
        composeRule.setContent {
            inputModes = LocalInputModeManager.current
            RemBrowseTheme(darkTheme = true) {
                colors = MaterialTheme.colorScheme
                Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
                    ScreenshotNoticeBar(notice = ScreenshotNotice(ScreenshotResult.Failed, serial = 1), onDismiss = {})
                }
            }
        }
        composeRule.runOnIdle { inputModes.requestInputMode(InputMode.Keyboard) }
        val dismiss = composeRule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("screenshot_notice_dismiss")))
        dismiss.requestFocus().assertIsFocused()
        // The dark theme's accent is pale, like the notice there; its inverse is the light theme's
        val ring = pixelOnTheRing(dismiss)
        assertTrue("The ring, in inversePrimary: $ring", ring.isCloseTo(colors!!.inversePrimary))
        assertFalse("Not in the accent, which would not show on the notice", ring.isCloseTo(colors.primary))
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun compactWindow_tabWalksFromTheTabCounterToMore() {
        launchWithKeyboard()

        composeRule.onNodeWithTag("tab_switcher_button").requestFocus().assertIsFocused()
        composeRule.onNodeWithTag("tab_switcher_button").tab()
        button("More").assertIsFocused()
        button("More").tab(shift = true)
        composeRule.onNodeWithTag("tab_switcher_button").assertIsFocused()
    }

    // --- Controls with a border of their own: the ring outside it, over it, in both themes ---

    /**
     * The screen's pixels 3dp out from the middle of the top edge and of the start edge of a
     * control: the middle of a ring drawn 2 to 4dp outside its edge, which a border along the
     * inside of the edge can't cover.
     */
    private fun pixelsOutside(node: SemanticsNodeInteraction): List<Color> {
        composeRule.waitForIdle()
        val semantics = node.fetchSemanticsNode()
        val out = with(composeRule.density) { 3.dp.toPx() }
        val position = semantics.positionOnScreen
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val above = Color(screen.getPixel((position.x + semantics.size.width / 2f).toInt(), (position.y - out).toInt()))
        val before = Color(screen.getPixel((position.x - out).toInt(), (position.y + semantics.size.height / 2f).toInt()))
        return listOf(above, before)
    }

    /** The controls checked by [checkRingOutside] that showed no ring, reported by [assertRingsOutside]. */
    private val missingRings = mutableListOf<String>()

    /**
     * [name]'s ring: nothing in the accent just outside its edge until it has the keyboard focus,
     * then the accent there, above it and before it. A missing ring is noted, and the test goes
     * on to the next control, so [assertRingsOutside] lists every one.
     */
    private fun checkRingOutside(name: String, node: SemanticsNodeInteraction) {
        val theme = if (darkTheme.value) "dark" else "light"
        node.assertIsNotFocused()
        pixelsOutside(node).forEach {
            assertFalse("$name ($theme): no accent outside it before it has the focus: $it", it.isCloseTo(primary))
        }
        node.requestFocus().assertIsFocused()
        val ring = pixelsOutside(node)
        if (!ring.all { it.isCloseTo(primary) }) missingRings += "$name ($theme): $ring"
    }

    private fun assertRingsOutside() {
        assertTrue("No ring in the accent outside the border:\n" + missingRings.joinToString("\n"), missingRings.isEmpty())
    }

    /**
     * Moves the keyboard focus to the toolbar's More button, away from the controls checked. (With
     * the keyboard in use, a window with nothing focused gives the focus to its first control,
     * the rail's menu button.)
     */
    private fun focusElsewhere() {
        button("More").requestFocus().assertIsFocused()
        composeRule.waitForIdle()
    }

    /** Runs [checks] in the dark theme, then again in the light one. */
    private fun inBothThemes(checks: () -> Unit) {
        listOf(true, false).forEach { dark ->
            composeRule.runOnIdle { darkTheme.value = dark }
            composeRule.waitForIdle()
            checks()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun splitButton_ringsOutsideItsBorder_andOutsideTheAccentBorderOfAnOpenSplit() {
        launchWithKeyboard()
        inBothThemes {
            focusElsewhere()
            checkRingOutside("Split", composeRule.onNodeWithTag("split_button"))
            focusElsewhere()
            composeRule.runOnIdle { browserVm.splitCurrentTab(currentOnLeft = true) }
            checkRingOutside("Split, a split open", composeRule.onNodeWithTag("split_button"))
            composeRule.runOnIdle { browserVm.setLayoutMode(LayoutMode.SINGLE_1X1) }
        }
        assertRingsOutside()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun railTiles_ringOutside_collapsedAndExpanded() {
        launchWithKeyboard()
        composeRule.runOnIdle { browserVm.openNewTab("https://two.example/") }
        val (first, second) = browserVm.uiState.value.tabs.map { it.id }
        fun tile(tag: String, name: String) {
            focusElsewhere()
            checkRingOutside(name, composeRule.onNodeWithTag(tag))
        }
        inBothThemes {
            tile("tab_rail_toggle", "The rail's menu button")
            tile("tab_bar_item_$first", "A tab")
            tile("tab_bar_item_$second", "The active tab")
            composeRule.runOnIdle { browserVm.setTabRailExpanded(true) }
            tile("tab_rail_toggle", "The expanded rail's menu button")
            tile("tab_bar_item_$first", "A tab's row on the expanded rail")
            tile("tab_bar_item_$second", "The active tab's row on the expanded rail")
            composeRule.runOnIdle { browserVm.setTabRailExpanded(false) }
        }
        assertRingsOutside()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun switcherCards_ringOutside_activeAndNot() {
        launchWithKeyboard()
        composeRule.runOnIdle { browserVm.openNewTab("https://two.example/") }
        val (first, second) = browserVm.uiState.value.tabs.map { it.id }
        composeRule.runOnIdle { chrome.showTabSwitcher = true }
        inBothThemes {
            focusElsewhere()
            checkRingOutside("A switcher card", composeRule.onNodeWithTag("switcher_tab_$first"))
            focusElsewhere()
            checkRingOutside("The active tab's switcher card", composeRule.onNodeWithTag("switcher_tab_$second"))
        }
        assertRingsOutside()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun settingsSections_ringOutside_shownAndNot() {
        launchWithKeyboard()
        composeRule.runOnIdle { browserVm.openSettings(SettingsSection.APPEARANCE) }
        inBothThemes {
            focusElsewhere()
            checkRingOutside("The section shown", composeRule.onNodeWithTag("settings_section_appearance"))
            focusElsewhere()
            checkRingOutside("Another section", composeRule.onNodeWithTag("settings_section_privacy"))
        }
        assertRingsOutside()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun paletteRows_ringOutside_highlightedAndNot() {
        launchWithKeyboard()
        composeRule.runOnIdle { chrome.showCommandPalette = true }
        composeRule.waitForIdle()
        val row = SemanticsMatcher("a palette row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("palette_") == true
        }
        val tags = composeRule.onAllNodes(row).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        inBothThemes {
            // The keyboard is in the palette's field, as it opened
            composeRule.onNodeWithTag("command_palette_input").requestFocus()
            checkRingOutside("The highlighted palette row", composeRule.onNodeWithTag(tags[0]))
            // Not the next row, whose top the highlighted row's accent border runs just above
            checkRingOutside("Another palette row", composeRule.onNodeWithTag(tags[2]))
        }
        assertRingsOutside()
    }
}
