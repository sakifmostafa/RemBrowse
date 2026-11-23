package com.rembrowser.rembrowse.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.SiteOrigins
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionKind.CAMERA
import com.rembrowser.rembrowse.model.SitePermissionKind.LOCATION
import com.rembrowser.rembrowse.model.SitePermissionKind.MICROPHONE
import com.rembrowser.rembrowse.model.SitePermissionKind.NOTIFICATIONS
import com.rembrowser.rembrowse.model.SitePermissionKind.PROTECTED_CONTENT
import com.rembrowser.rembrowse.model.SitePermissionKind.STORAGE_ACCESS
import com.rembrowser.rembrowse.model.SitePermissionState
import com.rembrowser.rembrowse.model.SitePermissionState.ALLOW
import com.rembrowser.rembrowse.model.SitePermissionState.BLOCK
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.components.SitePermissionActions
import com.rembrowser.rembrowse.ui.components.SitePermissionsSection
import com.rembrowser.rembrowse.ui.components.sitePermissionRowTag
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings → Site permissions, alone and wired to the browser, and its command palette entry. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h1100dp-land-mdpi")
class SitePermissionsSettingsUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun entry(
        origin: String,
        kind: SitePermissionKind,
        state: SitePermissionState,
        embedded: String? = null
    ) = SitePermissionEntry(origin, SiteOrigins.displayHost(origin), kind, state, embedded)

    private val meet = "https://meet.example.org"

    private val sets = mutableListOf<Pair<SitePermissionEntry, SitePermissionState?>>()
    private val resets = mutableListOf<String>()
    private var refreshes = 0

    private fun actions() = SitePermissionActions(
        onRefresh = { refreshes++ },
        onSet = { e, s -> sets += e to s },
        onReset = { resets += it }
    )

    private fun showSection(entries: List<SitePermissionEntry>) {
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        SitePermissionsSection(entries, actions())
                    }
                }
            }
        }
    }

    /** Android lets RemBrowse post notifications (the app's own permission and switch). */
    private fun androidAllowsNotifications(allowed: Boolean) {
        shadowOf(app).apply {
            if (allowed) grantPermissions(Manifest.permission.POST_NOTIFICATIONS) else denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // --- rows ---

    @Test
    fun eachDecisionIsARowThatSaysAllowedOrBlocked() {
        androidAllowsNotifications(true)
        showSection(
            listOf(
                entry(meet, CAMERA, ALLOW),
                entry(meet, MICROPHONE, BLOCK),
                entry(meet, LOCATION, ALLOW),
                entry(meet, NOTIFICATIONS, BLOCK),
                entry(meet, PROTECTED_CONTENT, ALLOW)
            )
        )
        val expected = mapOf(
            CAMERA to ("Camera" to true),
            MICROPHONE to ("Microphone" to false),
            LOCATION to ("Location" to true),
            NOTIFICATIONS to ("Notifications" to false),
            PROTECTED_CONTENT to ("Protected content" to true)
        )
        expected.forEach { (kind, labelAndAllowed) ->
            val (label, allowed) = labelAndAllowed
            val tag = "site_permission_${meet}_${kind.name.lowercase()}"
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
            composeRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertIsDisplayed()
            val word = if (allowed) "Allowed" else "Blocked"
            composeRule.onNode(hasText(word) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertIsDisplayed()
            if (allowed) composeRule.onNodeWithTag(tag).assertIsOn() else composeRule.onNodeWithTag(tag).assertIsOff()
        }
    }

    @Test
    fun theSitesHostNamesItsCardAndAnHttpSiteKeepsItsScheme() {
        showSection(
            listOf(
                entry(meet, CAMERA, ALLOW),
                entry("http://192.168.1.20:8080", CAMERA, BLOCK)
            )
        )
        composeRule.onNodeWithTag("site_permission_site_$meet").assertIsDisplayed()
        composeRule.onNodeWithText("meet.example.org").assertIsDisplayed()
        composeRule.onNodeWithText("http://192.168.1.20:8080").assertIsDisplayed()
    }

    @Test
    fun decisionsOfOneSiteShareOneCardAndSitesGetTheirOwn() {
        showSection(
            listOf(
                entry(meet, CAMERA, ALLOW),
                entry("https://other.example.net", CAMERA, ALLOW),
                entry(meet, MICROPHONE, ALLOW)
            )
        )
        composeRule.onNodeWithTag("site_permission_site_$meet").assertIsDisplayed()
        composeRule.onNodeWithTag("site_permission_site_https://other.example.net").assertIsDisplayed()
        composeRule.onAllNodesWithText("Reset").assertCountEquals(2)
        composeRule.onNode(
            hasTestTag("site_permission_${meet}_microphone") and hasAnyAncestor(hasTestTag("site_permission_site_$meet"))
        ).assertExists()
    }

    @Test
    fun cookieDecisionsNameTheEmbeddedSite() {
        val news = "https://news.example.org"
        val ads = entry(news, STORAGE_ACCESS, ALLOW, "https://ads.example.com")
        val comments = entry(news, STORAGE_ACCESS, BLOCK, "https://comments.example.com")
        showSection(listOf(ads, comments))

        composeRule.onNodeWithText("Cookies for ads.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Cookies for comments.example.com").assertIsDisplayed()
        composeRule.onNodeWithTag(sitePermissionRowTag(ads)).assertIsOn()
        composeRule.onNodeWithTag(sitePermissionRowTag(comments)).assertIsOff()
        assertEquals("site_permission_https://news.example.org_storage_access_https://ads.example.com", sitePermissionRowTag(ads))
    }

    // --- switching, forgetting, resetting ---

    @Test
    fun tappingAnAllowedRowBlocksItAndTappingABlockedRowAllowsIt() {
        val camera = entry(meet, CAMERA, ALLOW)
        val mic = entry(meet, MICROPHONE, BLOCK)
        showSection(listOf(camera, mic))

        composeRule.onNodeWithTag(sitePermissionRowTag(camera)).performClick()
        composeRule.onNodeWithTag(sitePermissionRowTag(mic)).performClick()

        assertEquals(listOf(camera to BLOCK, mic to ALLOW), sets)
    }

    @Test
    fun theSwitchHandsBackTheEntryItWasShownWithIncludingTheEmbeddedSite() {
        val cookies = entry("https://news.example.org", STORAGE_ACCESS, ALLOW, "https://ads.example.com")
        showSection(listOf(cookies))
        composeRule.onNodeWithTag(sitePermissionRowTag(cookies)).performClick()
        assertEquals("https://ads.example.com", sets.single().first.embeddedOrigin)
        assertEquals(BLOCK, sets.single().second)
    }

    @Test
    fun theCloseButtonForgetsOneDecision() {
        val camera = entry(meet, CAMERA, ALLOW)
        val mic = entry(meet, MICROPHONE, ALLOW)
        showSection(listOf(camera, mic))

        composeRule.onNodeWithTag("${sitePermissionRowTag(camera)}_remove").performClick()

        assertEquals(listOf(camera to null), sets)
        assertTrue(resets.isEmpty())
    }

    @Test
    fun theCloseButtonHasAnAccessibleNameAndResetNamesItsSite() {
        // As Gecko reported the page: its host in Unicode, the form the address bar shows
        SiteOrigins.rememberDisplayForm("https://münchen.example/")
        showSection(listOf(entry(meet, CAMERA, ALLOW), entry("https://xn--mnchen-3ya.example", LOCATION, BLOCK)))
        composeRule.onNode(hasContentDescription("Forget decision: Camera")).assertExists()
        composeRule.onNode(hasContentDescription("Forget decision: Location")).assertExists()
        composeRule.onNode(hasContentDescription("Reset permissions for meet.example.org")).assertExists()
        composeRule.onNode(hasContentDescription("Reset permissions for münchen.example")).assertExists()
    }

    @Test
    fun resetForgetsEverythingOfThatSiteByItsOrigin() {
        showSection(listOf(entry(meet, CAMERA, ALLOW), entry("https://other.example.net", CAMERA, ALLOW)))

        composeRule.onNodeWithTag("site_permission_reset_$meet").performClick()

        assertEquals(listOf(meet), resets)
        assertTrue(sets.isEmpty())
    }

    @Test
    fun openingTheSectionAsksForTheLatestDecisionsOnce() {
        showSection(emptyList())
        composeRule.waitForIdle()
        assertEquals(1, refreshes)
    }

    // --- empty ---

    @Test
    fun withoutDecisionsTheSectionExplainsWhereAnswersWillAppear() {
        showSection(emptyList())
        composeRule.onNodeWithTag("site_permissions_empty").assertIsDisplayed()
        composeRule.onNodeWithText("No site permissions yet").assertIsDisplayed()
        composeRule.onNodeWithText("When a site asks to use your camera", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Reset").assertCountEquals(0)
    }

    @Test
    fun withDecisionsTheEmptyStateIsGone() {
        showSection(listOf(entry(meet, CAMERA, ALLOW)))
        composeRule.onNodeWithTag("site_permissions_empty").assertDoesNotExist()
    }

    // --- the notifications notice ---

    @Test
    fun noticeShowsWhenASiteMayNotifyButAndroidKeepsNotificationsOff() {
        androidAllowsNotifications(false)
        showSection(listOf(entry(meet, NOTIFICATIONS, ALLOW)))
        composeRule.onNodeWithTag("site_permissions_notifications_notice").assertIsDisplayed()
        composeRule.onNodeWithText("Android notifications are off for RemBrowse").assertIsDisplayed()
    }

    @Test
    fun noticeShowsWhenTheAppSwitchIsOffEvenWithThePermission() {
        androidAllowsNotifications(true)
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        showSection(listOf(entry(meet, NOTIFICATIONS, ALLOW)))
        composeRule.onNodeWithTag("site_permissions_notifications_notice").assertIsDisplayed()
    }

    @Test
    fun noNoticeWhenAndroidAllowsNotifications() {
        androidAllowsNotifications(true)
        showSection(listOf(entry(meet, NOTIFICATIONS, ALLOW)))
        composeRule.onNodeWithTag("site_permissions_notifications_notice").assertDoesNotExist()
    }

    @Test
    fun noNoticeWhenNoSiteIsAllowedToNotify() {
        androidAllowsNotifications(false)
        showSection(listOf(entry(meet, NOTIFICATIONS, BLOCK), entry(meet, CAMERA, ALLOW)))
        composeRule.onNodeWithTag("site_permissions_notifications_notice").assertDoesNotExist()
    }

    @Test
    fun noNoticeWithoutAnyDecision() {
        androidAllowsNotifications(false)
        showSection(emptyList())
        composeRule.onNodeWithTag("site_permissions_notifications_notice").assertDoesNotExist()
    }

    @Test
    fun theNoticeButtonOpensAndroidsNotificationSettingsForTheApp() {
        androidAllowsNotifications(false)
        showSection(listOf(entry(meet, NOTIFICATIONS, ALLOW)))

        composeRule.onNodeWithTag("site_permissions_open_notification_settings").performClick()

        val started: Intent = assertNotNull_(shadowOf(app).nextStartedActivity)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    private fun assertNotNull_(intent: Intent?): Intent {
        assertNotNull("no settings screen was opened", intent)
        return intent!!
    }

    @Test
    fun noticeDisappearsOnReturningFromAndroidSettingsWhereNotificationsWereTurnedOn() {
        androidAllowsNotifications(false)
        showSection(listOf(entry(meet, NOTIFICATIONS, ALLOW)))
        composeRule.onNodeWithTag("site_permissions_notifications_notice").assertIsDisplayed()

        androidAllowsNotifications(true)
        val scenario = composeRule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("site_permissions_notifications_notice").assertDoesNotExist()
    }

    // --- wired into the browser ---

    private val engine = FakeBrowserEngine()
    private val browserVm = BrowserViewModel(engine, FakeBrowserStateRepository())
    private val chrome = BrowserChromeState()

    private fun launchScreen() {
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
                        chrome = chrome,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    @Test
    fun thePaletteEntryOpensSitePermissionsSettings() {
        launchScreen()
        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        composeRule.onNodeWithTag("command_palette_input").performTextInput("site perm")
        composeRule.onNodeWithTag("palette_site_permissions").assertIsDisplayed()
        composeRule.onNode(hasText("Site permissions") and hasAnyAncestor(hasTestTag("palette_site_permissions")), useUnmergedTree = true)
            .assertIsDisplayed()

        composeRule.onNodeWithTag("palette_site_permissions").performClick()

        composeRule.onNodeWithTag("command_palette").assertDoesNotExist()
        composeRule.onNodeWithTag("settings_page").assertIsDisplayed()
        assertEquals(SettingsSection.SITE_PERMISSIONS.url, browserVm.uiState.value.activeTab?.url)
        composeRule.onNodeWithTag("site_permissions_empty").assertIsDisplayed()
        composeRule.onNodeWithText(
            "What you allowed or blocked for each site. Remove a decision and the site asks again next time."
        ).assertIsDisplayed()
    }

    @Test
    fun openingTheSectionListsTheEnginesDecisionsAndEditsReachTheEngine() {
        val camera = entry(meet, CAMERA, ALLOW)
        val cookies = entry("https://news.example.org", STORAGE_ACCESS, ALLOW, "https://ads.example.com")
        val other = entry("https://other.example.net", LOCATION, BLOCK)
        engine.storedPermissions += listOf(camera, cookies, other)
        launchScreen()

        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.SITE_PERMISSIONS) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(sitePermissionRowTag(camera)).assertIsOn()

        // Switching
        composeRule.onNodeWithTag(sitePermissionRowTag(camera)).performClick()
        composeRule.waitForIdle()
        assertEquals(SitePermissionState.BLOCK, engine.sitePermissionChanges.last().state)
        assertEquals(CAMERA, engine.sitePermissionChanges.last().kind)
        composeRule.onNodeWithTag(sitePermissionRowTag(camera)).assertIsOff()

        // Switching a cookie decision names the embedded site
        composeRule.onNodeWithTag(sitePermissionRowTag(cookies)).performClick()
        composeRule.waitForIdle()
        assertEquals("https://ads.example.com", engine.sitePermissionChanges.last().embeddedOrigin)

        // Forgetting
        composeRule.onNodeWithTag("${sitePermissionRowTag(other)}_remove").performClick()
        composeRule.waitForIdle()
        assertNull(engine.sitePermissionChanges.last().state)
        composeRule.onNodeWithTag(sitePermissionRowTag(other)).assertDoesNotExist()

        // Reset
        composeRule.onNodeWithTag("site_permission_reset_$meet").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(meet), engine.clearedPermissionOrigins)
        composeRule.onNodeWithTag("site_permission_site_$meet").assertDoesNotExist()
        composeRule.onNodeWithTag("site_permission_site_https://news.example.org").assertIsDisplayed()
    }
}
