package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.PageMediaType
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The menu a right click opens on a page: for a link or picture, or the page's own. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class PageContextMenuUiTest {

    @get:Rule
    val composeRule = createComposeRule()

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
    private val copiedImages = mutableListOf<Pair<String, String?>>()

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launchOnAWebPage(): String {
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false, LocalClipboardManager provides clipboard) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        versionName = "test",
                        onCopyImage = { imageUrl, pageUrl -> copiedImages += imageUrl to pageUrl },
                        onPickLocalXpi = {},
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread {
            browserVm.navigatePane(0, "https://example.com/")
            engine.sessions.getValue(tab).simulatePageLoad("https://example.com/", "Example", canGoBack = false)
        }
        composeRule.waitForIdle()
        return tab
    }

    @Test
    fun rightClickingALink_offersItsActions_andCopiesIt() {
        val tab = launchOnAWebPage()
        composeRule.runOnUiThread {
            engine.sessions.getValue(tab).simulateContextMenu(linkUrl = "https://example.com/story", linkText = "A story")
        }

        composeRule.onNodeWithTag("page_context_menu").assertIsDisplayed()
        composeRule.onNodeWithText("Open link in new tab").assertIsDisplayed()
        composeRule.onNodeWithText("Open link in split view").assertIsDisplayed()
        composeRule.onNodeWithText("Copy link text").assertIsDisplayed()

        composeRule.onNodeWithTag("page_menu_copy_link").performClick()
        composeRule.waitForIdle()

        assertEquals("https://example.com/story", clipboard.copied?.text)
        assertNull(browserVm.pageMenu.value)
        composeRule.onNodeWithTag("page_context_menu").assertDoesNotExist()
    }

    @Test
    fun rightClickingAPicture_namesWhatItIs() {
        val tab = launchOnAWebPage()
        composeRule.runOnUiThread {
            engine.sessions.getValue(tab).simulateContextMenu(mediaUrl = "https://cdn.example.com/v.mp4", mediaType = PageMediaType.VIDEO)
        }

        composeRule.onNodeWithText("Open video in new tab").assertIsDisplayed()
        composeRule.onNodeWithText("Copy video address").performClick()
        composeRule.waitForIdle()
        assertEquals("https://cdn.example.com/v.mp4", clipboard.copied?.text)
    }

    @Test
    fun rightClickingThePage_opensItsOwnMenu() {
        val tab = launchOnAWebPage()
        composeRule.runOnUiThread { engine.sessions.getValue(tab).simulateContextMenu() }

        composeRule.onNodeWithTag("page_menu_back").assertIsNotEnabled()
        composeRule.onNodeWithText("Bookmark this page").assertIsDisplayed()
        composeRule.onNodeWithTag("page_menu_reload").performClick()
        composeRule.waitForIdle()

        assertEquals(1, engine.sessions.getValue(tab).reloadCount)
        composeRule.onNodeWithTag("page_context_menu").assertDoesNotExist()
    }

    @Test
    fun choosingCopyImage_handsThePictureAndThePageToTheClipboardOfImages() {
        val tab = launchOnAWebPage()
        composeRule.runOnUiThread {
            engine.sessions.getValue(tab).simulateContextMenu(mediaUrl = "https://cdn.example.com/cat.png", mediaType = PageMediaType.IMAGE)
        }

        composeRule.onNodeWithText("Open image in new tab").assertIsDisplayed()
        composeRule.onNodeWithText("Copy image address").assertIsDisplayed()
        composeRule.onNodeWithText("Copy image").assertIsDisplayed()
        composeRule.onNodeWithTag("page_menu_copy_image").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("https://cdn.example.com/cat.png" to "https://example.com/"), copiedImages)
        assertNull("The address is not put on the text clipboard", clipboard.copied)
        assertNull(browserVm.pageMenu.value)
        composeRule.onNodeWithTag("page_context_menu").assertDoesNotExist()
    }

    @Test
    fun aBlobPicture_hasNoCopyImage() {
        val tab = launchOnAWebPage()
        composeRule.runOnUiThread {
            engine.sessions.getValue(tab).simulateContextMenu(mediaUrl = "blob:https://example.com/1", mediaType = PageMediaType.IMAGE)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("page_menu_copy_image").assertDoesNotExist()
        assertEquals(emptyList<Pair<String, String?>>(), copiedImages)
    }
}
