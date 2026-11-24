package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The toolbar with a keyboard: Tab walks its buttons in the order they are drawn, left to right,
 * and each one can take the focus (and so show its focus ring).
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

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launchWithKeyboard() {
        browserVm.openNewTab("https://one.example/")
        composeRule.setContent {
            inputModes = LocalInputModeManager.current
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
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
