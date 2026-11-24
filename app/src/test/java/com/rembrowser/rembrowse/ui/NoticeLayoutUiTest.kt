package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.DownloadNotice
import com.rembrowser.rembrowse.model.DownloadStatus
import com.rembrowser.rembrowse.ui.components.DownloadNoticeBar
import com.rembrowser.rembrowse.ui.components.NoticeMaxWidth
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The notices over the bottom of the window (here a download's) are as wide as what they say, up
 * to 520dp, so on a wide window or in split view they leave the page beside them free; and the
 * whole notice, its buttons included, is announced. Real text measurement (native graphics).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class NoticeLayoutUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A finished download's notice, placed as RemBrowseScreen places the notices */
    private fun showDownloaded(fileName: String) {
        composeRule.setContent {
            RemBrowseTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DownloadNoticeBar(
                            notice = DownloadNotice(itemId = "a", fileName = fileName, status = DownloadStatus.COMPLETED),
                            message = null,
                            onOpen = {},
                            onShowDownloads = {},
                            onDismiss = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun noticeWidth() = composeRule.onNodeWithTag("download_notice").getBoundsInRoot().width

    @Test
    fun aShortNotice_onAWideWindow_isAsWideAsWhatItSays() {
        showDownloaded("a.pdf")
        val width = noticeWidth()
        assertTrue("$width, narrower than $NoticeMaxWidth", width < NoticeMaxWidth)
        // Its icon, words, Open and close button, not squeezed
        assertTrue("$width", width.value > 200f)
        composeRule.onNodeWithTag("download_notice_action").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Dismiss")).assertIsDisplayed()
    }

    @Test
    fun aLongNotice_stopsAt520dp_andItsButtonsStay() {
        showDownloaded("quarterly-report-final-version-".repeat(6) + ".pdf")
        assertEquals(NoticeMaxWidth.value, noticeWidth().value, 0.5f)
        composeRule.onNodeWithTag("download_notice_action").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Dismiss")).assertIsDisplayed()
    }

    @Test
    fun theWholeNotice_isOnePoliteLiveRegion_itsButtonsIncluded() {
        showDownloaded("a.pdf")
        val inLiveRegion = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNode(hasText("Downloaded a.pdf") and inLiveRegion).assertExists()
        composeRule.onNode(hasTestTag("download_notice_action") and inLiveRegion).assertExists()
        composeRule.onNode(hasContentDescription("Dismiss") and inLiveRegion).assertExists()
    }
}
