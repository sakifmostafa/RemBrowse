package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
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
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
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
    private var primary = Color.Unspecified

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
            CompositionLocalProvider(LocalSiteIconsEnabled provides false, LocalInputModeManager provides shown) {
                RemBrowseTheme {
                    primary = MaterialTheme.colorScheme.primary
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
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

    /** The screen's pixel 3dp above the middle of [node]'s top edge: where its ring's line runs. */
    private fun pixelOnTheRing(node: SemanticsNodeInteraction): Color {
        composeRule.waitForIdle()
        val semantics = node.fetchSemanticsNode()
        val ringOffset = with(composeRule.density) { 3.dp.toPx() }
        val x = semantics.positionOnScreen.x + semantics.size.width / 2f
        val y = semantics.positionOnScreen.y - ringOffset
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
        assertFalse("Not in the accent, which would not show on the notice", ring.isCloseTo(colors!!.primary))
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
}
