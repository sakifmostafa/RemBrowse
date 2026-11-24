package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The More menu with larger text (Settings → Display → Font size): it opens below the toolbar,
 * never over it or over its own button, and every row in it can be reached, scrolling the menu
 * when it is longer than the room below (a phone). Its rows drop Material 3 Expressive's padding
 * above and below the text from 150%, keeping the row's 44dp, so on a laptop's window it still
 * fits whole at 200%.
 */
@RunWith(AndroidJUnit4::class)
// Text measured as on a device, for the rows' heights
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class LargeFontMenuUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browserVm = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun openMoreMenu() {
        browserVm.openNewTab("https://one.example/")
        val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
        val downloadsVm = DownloadsViewModel(
            storage = FakeDownloadStorage(),
            history = FakeDownloadHistoryStore(),
            scope = uiScope,
            ioDispatcher = Dispatchers.Unconfined
        )
        composeRule.setContent {
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
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.top(): Float = fetchSemanticsNode().positionOnScreen.y
    private fun SemanticsNodeInteraction.bottom(): Float = fetchSemanticsNode().let { it.positionOnScreen.y + it.size.height }
    private fun SemanticsNodeInteraction.height(): Float = fetchSemanticsNode().size.height.toFloat()
    private fun px(dp: Float): Float = dp * composeRule.density.density

    /** The More menu's rows, top to bottom, on a window with the rail ([compact] false) or a phone's. */
    private fun rows(compact: Boolean): List<String> =
        (if (compact) listOf("menu_reload", "bookmark_button") else emptyList()) + listOf(
            "menu_new_tab", "menu_new_terminal", "menu_command_palette", "menu_find_in_page", "menu_print",
            "menu_screenshot"
        ) + (if (compact) listOf("split_button") else emptyList()) + listOf(
            "menu_bookmarks", "menu_history", "menu_downloads", "menu_extensions", "menu_desktop_site", "menu_settings"
        )

    /**
     * The menu's first row starts below the toolbar and the More button, and each row, the last
     * included, can be scrolled to and is then on screen, below the toolbar.
     */
    private fun assertOpensBelowTheToolbar_everyRowReachable(compact: Boolean) {
        val toolbarBottom = composeRule.onNodeWithTag("toolbar").bottom()
        val moreBottom = composeRule.onNodeWithTag("more_button").bottom()
        val rows = rows(compact)
        val first = composeRule.onNodeWithTag(rows.first())
        assertTrue("The menu (${first.top()}) is below the toolbar ($toolbarBottom)", first.top() >= toolbarBottom)
        assertTrue("The menu (${first.top()}) is below the More button ($moreBottom)", first.top() >= moreBottom)
        rows.forEach { tag ->
            val row = composeRule.onNodeWithTag(tag)
            row.performScrollTo().assertIsDisplayed()
            assertTrue("$tag (${row.top()}) shows below the toolbar ($toolbarBottom)", row.top() >= toolbarBottom)
        }
        // Scrolled back up, the first row is where it was
        first.performScrollTo()
        assertTrue(first.top() >= toolbarBottom)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun fontAt200_onALaptopWindow_theMenuOpensBelowTheToolbar_withRowsAsTallAsAt100() {
        openMoreMenu()
        assertOpensBelowTheToolbar_everyRowReachable(compact = false)
        // Material 3 Expressive's 44dp row, the text taking the room of the padding (with the
        // padding the rows were 62dp at 200%)
        assertEquals(px(44f), composeRule.onNodeWithTag("menu_new_tab").height(), 1f)
        // So it fits whole, without scrolling, as it did before the expressive rows
        composeRule.onNodeWithTag("menu_new_tab").performScrollTo()
        assertTrue(composeRule.onNodeWithTag("menu_settings").bottom() <= px(800f))
    }

    @Test
    @Config(fontScale = 1.5f)
    fun fontAt150_onALaptopWindow_theMenuOpensBelowTheToolbar() {
        openMoreMenu()
        assertOpensBelowTheToolbar_everyRowReachable(compact = false)
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi", fontScale = 2.0f)
    fun fontAt200_onAPhone_theMenuOpensBelowTheToolbar_andScrolls() {
        openMoreMenu()
        assertOpensBelowTheToolbar_everyRowReachable(compact = true)
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi", fontScale = 1.5f)
    fun fontAt150_onAPhone_theMenuOpensBelowTheToolbar() {
        openMoreMenu()
        assertOpensBelowTheToolbar_everyRowReachable(compact = true)
    }

    @Test
    fun usualFontSize_theMenuKeepsMaterial3ExpressivesRows() {
        openMoreMenu()
        assertOpensBelowTheToolbar_everyRowReachable(compact = false)
        // 12dp above and below a 20dp line of text
        assertEquals(px(44f), composeRule.onNodeWithTag("menu_new_tab").height(), 1f)
    }
}
