package com.rembrowser.rembrowse.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.PermissionAnswer
import com.rembrowser.rembrowse.model.PermissionRequest
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionKind.CAMERA
import com.rembrowser.rembrowse.model.SitePermissionKind.LOCATION
import com.rembrowser.rembrowse.model.SitePermissionKind.MICROPHONE
import com.rembrowser.rembrowse.model.SitePermissionKind.NOTIFICATIONS
import com.rembrowser.rembrowse.model.SitePermissionKind.PROTECTED_CONTENT
import com.rembrowser.rembrowse.model.SitePermissionKind.STORAGE_ACCESS
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.components.PermissionActionDelayMillis
import com.rembrowser.rembrowse.ui.components.PermissionPromptCard
import com.rembrowser.rembrowse.ui.components.PermissionPromptOverlay
import com.rembrowser.rembrowse.ui.components.permissionAskText
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

/** The permission card: what it says, which answers it gives, and the half second it ignores taps. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class PermissionPromptUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Draws the window once, as a frame on screen would. Robolectric never draws a window by
     * itself, and the card's answers only start their half second once they have been drawn.
     * Tests that call it run with native graphics, where the card's icons can be drawn.
     */
    private fun drawFrame() {
        composeRule.runOnUiThread {
            val root = composeRule.activity.window.decorView
            // As the window does each frame: the pre-draw listeners, then the drawing
            root.viewTreeObserver.dispatchOnPreDraw()
            val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            bitmap.recycle()
        }
    }

    /** Lets [millis] pass with the window drawn every frame, as a screen would. */
    private fun runFrames(millis: Long) {
        val start = composeRule.mainClock.currentTime
        while (composeRule.mainClock.currentTime - start < millis) {
            composeRule.mainClock.advanceTimeByFrame()
            drawFrame()
        }
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun request(
        kinds: Set<SitePermissionKind> = setOf(CAMERA),
        id: String = "r1",
        host: String = "web.example.org",
        embeddedHost: String? = null,
        offersAllowOnce: Boolean = kinds.all { it.offersAllowOnce }
    ) = PermissionRequest(
        id = id,
        tabId = "tab-1",
        origin = "https://$host",
        displayHost = host,
        kinds = kinds,
        embeddedHost = embeddedHost,
        offersAllowOnce = offersAllowOnce
    )

    private val answers = mutableListOf<Pair<String, PermissionAnswer>>()
    private var focusReturns = 0

    private fun showCard(request: PermissionRequest) {
        composeRule.setContent {
            RemBrowseTheme {
                PermissionPromptCard(
                    request = request,
                    onAnswer = { id, answer -> answers += id to answer },
                    onReturnFocus = { focusReturns++ }
                )
            }
        }
    }

    // --- the words ---

    @Test
    fun theCardNamesTheSiteAndWhatItWants() {
        showCard(request(setOf(CAMERA, MICROPHONE), host = "meet.example.org"))
        composeRule.onNodeWithText("meet.example.org wants to").assertIsDisplayed()
        composeRule.onNodeWithText("Use your camera and microphone").assertIsDisplayed()
    }

    @Test
    fun theAskTextForEachKind() {
        val expected = mapOf(
            setOf(CAMERA) to "Use your camera",
            setOf(MICROPHONE) to "Use your microphone",
            setOf(CAMERA, MICROPHONE) to "Use your camera and microphone",
            setOf(LOCATION) to "Know your location",
            setOf(NOTIFICATIONS) to "Show notifications",
            setOf(PROTECTED_CONTENT) to "Play protected content (DRM)",
            setOf(CAMERA, LOCATION) to "Use your camera and know your location",
            setOf(MICROPHONE, LOCATION, NOTIFICATIONS) to "Use your microphone, know your location and show notifications"
        )
        expected.forEach { (kinds, text) ->
            assertEquals("text for $kinds", text, permissionAskText(kinds, null).text)
        }
    }

    @Test
    fun storageAccessNamesTheEmbeddedHostOrFallsBackToAGenericWords() {
        assertEquals(
            "Let comments.example.com use its cookies on this site",
            permissionAskText(setOf(STORAGE_ACCESS), "comments.example.com").text
        )
        assertEquals(
            "Let an embedded site use its cookies on this site",
            permissionAskText(setOf(STORAGE_ACCESS), null).text
        )
    }

    @Test
    fun theCardShowsTheEmbeddedHostForStorageAccess() {
        showCard(request(setOf(STORAGE_ACCESS), host = "news.example.org", embeddedHost = "comments.example.com"))
        composeRule.onNodeWithText("news.example.org wants to").assertIsDisplayed()
        composeRule.onNodeWithText("Let comments.example.com use its cookies on this site").assertIsDisplayed()
    }

    @Test
    fun aPortedHostIsShownWithItsPort() {
        showCard(request(setOf(LOCATION), host = "192.168.1.20:8080"))
        composeRule.onNodeWithText("192.168.1.20:8080 wants to").assertIsDisplayed()
    }

    // --- allow once ---

    @Test
    fun allowThisTimeIsShownOnlyWhenOffered() {
        showCard(request(setOf(LOCATION), offersAllowOnce = true))
        composeRule.onNodeWithTag("permission_allow_once").assertIsDisplayed()
        composeRule.onNodeWithTag("permission_block").assertIsDisplayed()
        composeRule.onNodeWithTag("permission_allow").assertIsDisplayed()
    }

    @Test
    fun noAllowThisTimeForNotifications() {
        showCard(request(setOf(NOTIFICATIONS)))
        composeRule.onNodeWithTag("permission_allow").assertIsDisplayed()
        composeRule.onNodeWithTag("permission_allow_once").assertDoesNotExist()
        composeRule.onNodeWithText("Allow this time").assertDoesNotExist()
    }

    // --- the half second ---

    private fun tapAnswerBeforeAndAfterTheDelay(tag: String, expected: PermissionAnswer) {
        composeRule.mainClock.autoAdvance = false
        showCard(request(setOf(LOCATION), id = "r9"))
        drawFrame()
        composeRule.mainClock.advanceTimeBy(100)

        composeRule.onNodeWithTag(tag).performClick()
        composeRule.mainClock.advanceTimeBy(350)
        composeRule.onNodeWithTag(tag).performClick()
        assertTrue("$tag answered inside the first half second: $answers", answers.isEmpty())

        composeRule.mainClock.advanceTimeBy(PermissionActionDelayMillis)
        composeRule.onNodeWithTag(tag).performClick()
        assertEquals(listOf("r9" to expected), answers)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun blockIgnoresTapsForHalfASecondThenWorks() =
        tapAnswerBeforeAndAfterTheDelay("permission_block", PermissionAnswer.BLOCK)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun allowThisTimeIgnoresTapsForHalfASecondThenWorks() =
        tapAnswerBeforeAndAfterTheDelay("permission_allow_once", PermissionAnswer.ALLOW_ONCE)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun allowIgnoresTapsForHalfASecondThenWorks() =
        tapAnswerBeforeAndAfterTheDelay("permission_allow", PermissionAnswer.ALLOW)

    @Test
    fun theDelayIsHalfASecond() {
        assertEquals(500L, PermissionActionDelayMillis)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aNewRequestInTheSameCardStartsTheHalfSecondAgain() {
        composeRule.mainClock.autoAdvance = false
        var current by mutableStateOf(request(setOf(LOCATION), id = "first"))
        composeRule.setContent {
            RemBrowseTheme {
                PermissionPromptCard(request = current, onAnswer = { id, answer -> answers += id to answer })
            }
        }
        drawFrame()
        composeRule.mainClock.advanceTimeBy(PermissionActionDelayMillis + 100)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertEquals(listOf("first" to PermissionAnswer.ALLOW), answers)

        current = request(setOf(CAMERA), id = "second")
        // Let the crossfade from the first request finish, so only one card is left to tap
        runFrames(300)
        composeRule.onNodeWithTag("permission_allow").performClick()
        runFrames(100)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertEquals("the second request answered too early", 1, answers.size)

        runFrames(PermissionActionDelayMillis)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertEquals("second" to PermissionAnswer.ALLOW, answers.last())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theHalfSecondStartsWhenTheCardIsFirstDrawnNotWhenItIsComposed() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RemBrowseTheme {
                PermissionPromptCard(request = request(setOf(LOCATION), id = "late"), onAnswer = { id, answer -> answers += id to answer })
            }
        }
        // Composed and laid out (so it can be hit), but no frame drawn yet: no screen shows it
        composeRule.mainClock.advanceTimeBy(PermissionActionDelayMillis * 2)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertTrue("answered before it was ever drawn: $answers", answers.isEmpty())

        runFrames(200)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertTrue("answered inside the first half second on screen: $answers", answers.isEmpty())

        runFrames(PermissionActionDelayMillis)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertEquals(listOf("late" to PermissionAnswer.ALLOW), answers)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aCardThatJumpsUnderThePointerStartsTheHalfSecondAgain() {
        composeRule.mainClock.autoAdvance = false
        var top by mutableStateOf(0.dp)
        composeRule.setContent {
            RemBrowseTheme {
                Box(Modifier.size(600.dp)) {
                    PermissionPromptCard(
                        request = request(setOf(LOCATION), id = "moved"),
                        onAnswer = { id, answer -> answers += id to answer },
                        modifier = Modifier.offset { IntOffset(0, top.roundToPx()) }
                    )
                }
            }
        }
        drawFrame()
        // Long past the half second: it would take a tap here, if it stayed put
        composeRule.mainClock.advanceTimeBy(PermissionActionDelayMillis * 2)

        top = 120.dp
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithTag("permission_allow").performClick()
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertTrue("answered inside half a second of moving: $answers", answers.isEmpty())

        composeRule.mainClock.advanceTimeBy(PermissionActionDelayMillis)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertEquals(listOf("moved" to PermissionAnswer.ALLOW), answers)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aMoveOfAPixelOrTwoDoesNotStartTheWaitAgain() {
        composeRule.mainClock.autoAdvance = false
        var top by mutableStateOf(0.dp)
        composeRule.setContent {
            RemBrowseTheme {
                Box(Modifier.size(600.dp)) {
                    PermissionPromptCard(
                        request = request(setOf(LOCATION), id = "nudged"),
                        onAnswer = { id, answer -> answers += id to answer },
                        modifier = Modifier.offset { IntOffset(0, top.roundToPx()) }
                    )
                }
            }
        }
        drawFrame()
        composeRule.mainClock.advanceTimeBy(PermissionActionDelayMillis * 2)

        top = 2.dp
        composeRule.mainClock.advanceTimeBy(50)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertEquals(listOf("nudged" to PermissionAnswer.ALLOW), answers)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theFindBarOpeningAboveTheCardStartsTheHalfSecondAgain() {
        composeRule.mainClock.autoAdvance = false
        var findBarOpen by mutableStateOf(false)
        composeRule.setContent {
            RemBrowseTheme {
                // Too narrow for the card beside the find bar, so the card moves down below it
                Box(Modifier.size(600.dp)) {
                    PermissionPromptOverlay(
                        request = request(setOf(CAMERA), id = "under-find"),
                        findBarOpen = findBarOpen,
                        onAnswer = { id, answer -> answers += id to answer }
                    )
                }
            }
        }
        runFrames(PermissionActionDelayMillis * 2)

        findBarOpen = true
        // The card slides down for a moment; the wait starts again from where it settles
        runFrames(400)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertTrue("answered just after moving below the find bar: $answers", answers.isEmpty())

        runFrames(PermissionActionDelayMillis)
        composeRule.onNodeWithTag("permission_allow").performClick()
        assertEquals(listOf("under-find" to PermissionAnswer.ALLOW), answers)
    }

    // --- dismissing ---

    @Test
    fun theCloseButtonDismissesAtOnce() {
        composeRule.mainClock.autoAdvance = false
        showCard(request())
        composeRule.mainClock.advanceTimeBy(50)

        composeRule.onNodeWithTag("permission_dismiss").performClick()

        assertEquals(listOf("r1" to PermissionAnswer.DISMISS), answers)
    }

    @Test
    fun escapeDismissesWhileFocusIsInTheCardAndFocusGoesBackToThePage() {
        showCard(request())
        composeRule.onNodeWithTag("permission_allow").requestFocus()

        composeRule.onNodeWithTag("permission_allow").performKeyInput { pressKey(Key.Escape) }

        assertEquals(listOf("r1" to PermissionAnswer.DISMISS), answers)
        assertEquals(1, focusReturns)
    }

    @Test
    fun escapeIsTakenOncePerPress() {
        showCard(request())
        composeRule.onNodeWithTag("permission_block").requestFocus()
        composeRule.onNodeWithTag("permission_block").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, answers.size)
    }

    @Test
    fun otherKeysDoNotAnswer() {
        showCard(request())
        composeRule.onNodeWithTag("permission_allow").requestFocus()
        composeRule.onNodeWithTag("permission_allow").performKeyInput { pressKey(Key.A) }
        assertTrue(answers.isEmpty())
    }

    // --- the overlay in a pane ---

    @Test
    fun theOverlayShowsTheRequestAndShowsNothingWithoutOne() {
        var shown by mutableStateOf<PermissionRequest?>(request(setOf(NOTIFICATIONS)))
        composeRule.setContent {
            RemBrowseTheme {
                Box(Modifier.fillMaxSize()) {
                    PermissionPromptOverlay(
                        request = shown,
                        findBarOpen = false,
                        onAnswer = { id, answer -> answers += id to answer }
                    )
                }
            }
        }
        composeRule.onNodeWithTag("permission_prompt").assertIsDisplayed()

        shown = null
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag("permission_prompt").assertDoesNotExist()
    }

    // --- in the whole screen ---

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aSitesRequestAppearsInItsPaneAndAnAnswerReachesTheEngine() {
        val engine = FakeBrowserEngine()
        val browserVm = BrowserViewModel(engine, FakeBrowserStateRepository())
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
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread {
            browserVm.navigatePane(0, "https://meet.example.org/")
            engine.sessions.getValue(tab).simulatePageLoad("https://meet.example.org/", "Meet", canGoBack = false)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("permission_prompt").assertDoesNotExist()

        val ask = engine.simulatePermissionRequest(tab, "https://meet.example.org", setOf(CAMERA, MICROPHONE))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("permission_prompt").assertIsDisplayed()
        composeRule.onNodeWithText("meet.example.org wants to").assertIsDisplayed()

        drawFrame()
        composeRule.mainClock.advanceTimeBy(PermissionActionDelayMillis + 200)
        composeRule.onNodeWithTag("permission_allow").performClick()
        composeRule.waitForIdle()

        assertEquals(PermissionAnswer.ALLOW, ask.answer)
        composeRule.onNodeWithTag("permission_prompt").assertDoesNotExist()
    }
}
