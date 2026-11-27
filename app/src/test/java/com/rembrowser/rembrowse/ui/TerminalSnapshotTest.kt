package com.rembrowser.rembrowse.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import java.util.concurrent.TimeUnit
import com.rembrowser.rembrowse.data.TerminalSettings
import com.rembrowser.rembrowse.model.TerminalCursorStyle
import com.rembrowser.rembrowse.model.TerminalFont
import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.rembrowser.rembrowse.terminal.TerminalScreenView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rembrowser.rembrowse.data.InMemorySecretStore
import com.rembrowser.rembrowse.data.InMemoryTerminalStore
import com.rembrowser.rembrowse.data.SecretStore
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.fakes.FakeSshConnector
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.KnownHost
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.SshAuthMethod
import com.rembrowser.rembrowse.model.SshHost
import com.rembrowser.rembrowse.model.SshKeyInfo
import com.rembrowser.rembrowse.model.TerminalTarget
import com.rembrowser.rembrowse.model.ThemeMode
import com.rembrowser.rembrowse.terminal.HostKeyCheck
import com.rembrowser.rembrowse.terminal.TerminalStatus
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import com.rembrowser.rembrowse.viewmodel.TerminalViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Renders the terminal's screens to PNG files for design review, like UiSnapshotTest. Skipped
 * unless a target directory is given: `-Prembrowse.screenshots=build/ui-screenshots`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TerminalSnapshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val outputDir: File? = System.getProperty("rembrowse.screenshots")?.let(::File)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private val connector = FakeSshConnector()
    private lateinit var browserVm: BrowserViewModel
    private lateinit var terminalVm: TerminalViewModel

    private val hosts = listOf(
        SshHost("homelab", "Homelab", "homelab.local", 22, "sakif", SshAuthMethod.KEY, keyId = "k1"),
        SshHost("vps", "Web server", "203.0.113.24", 2222, "deploy", SshAuthMethod.PASSWORD, hasSavedPassword = true),
        SshHost("pi", "", "raspberrypi.lan", 22, "pi", SshAuthMethod.PASSWORD)
    )

    @Before
    fun setUp() {
        assumeTrue("Pass -Prembrowse.screenshots=<dir> to render snapshots", outputDir != null)
        outputDir!!.mkdirs()
    }

    @After
    fun tearDown() {
        if (::terminalVm.isInitialized) terminalVm.closeAll()
        uiScope.cancel()
    }

    private fun launch(
        settings: BrowserSettings = BrowserSettings(),
        terminalUrl: String = "rem://terminal",
        split: Boolean = false,
        terminalSettings: TerminalSettings = TerminalSettings()
    ) {
        val tabs = listOf(
            BrowserTab(id = "docs", url = "https://docs.docker.com/", title = "Docker Docs"),
            BrowserTab(id = "github", url = "https://github.com/", title = "GitHub"),
            BrowserTab(id = "term", url = terminalUrl)
        )
        browserVm = BrowserViewModel(
            engine,
            FakeBrowserStateRepository(
                PersistedBrowserState(
                    layoutMode = if (split) LayoutMode.SPLIT_1X2 else LayoutMode.SINGLE_1X1,
                    tabs = tabs,
                    paneTabIds = if (split) listOf("docs", "term") else listOf("term"),
                    activePaneIndex = if (split) 1 else 0,
                    settings = settings
                )
            )
        )
        val secrets = InMemorySecretStore().apply {
            put(SecretStore.privateKey("k1"), com.rembrowser.rembrowse.terminal.SshKeys.generateEd25519("googlebook").privateKeyPem)
            put(SecretStore.hostPassword("vps"), "secret")
        }
        val main = Handler(Looper.getMainLooper())
        terminalVm = TerminalViewModel(
            store = InMemoryTerminalStore(
                hosts = hosts,
                keys = listOf(
                    SshKeyInfo("k1", "googlebook", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGkq2b9wJcP0vT1m googlebook", "SHA256:4k5VtZ1yW0n0qAqk2R8m3N9xYb7cJp0eL2sG6hT1uFw", 0),
                    SshKeyInfo("k2", "work laptop", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIB7 work-laptop", "SHA256:Zq9rT2vU8wX1yA3bC5dE7fG9hJ1kL3mN5pQ7rS9tU1v", 0)
                ),
                knownHosts = listOf(
                    KnownHost("homelab.local:22", "ssh-ed25519", "AAAA", "SHA256:cdvxaIN6RP+t0WZSOrRvWO69Dj2Sd3N/aAjUkR2+gfg", 0),
                    KnownHost("203.0.113.24:2222", "ecdsa-sha2-nistp256", "AAAA", "SHA256:p1Bf0bq3M8x2kYc7uQ1oR5sT9vW3yA6dF8hJ2lN4pZ0", 0)
                ),
                settings = terminalSettings,
                commands = listOf("git stash pop", "docker compose logs -f backup", "sudo systemctl restart plex")
            ),
            secrets = secrets,
            connector = connector,
            scope = uiScope,
            postToMain = { main.post(it) },
            onTabTitleChanged = { tab, title -> browserVm.setTerminalTitle(tab, title) }
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme(darkTheme = settings.themeMode.isDark(systemIsDark = true), accent = settings.accent) {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope),
                        downloadsViewModel = DownloadsViewModel(
                            storage = FakeDownloadStorage(),
                            history = FakeDownloadHistoryStore(),
                            scope = uiScope,
                            ioDispatcher = Dispatchers.Unconfined
                        ),
                        versionName = "0.13.6",
                        onPickLocalXpi = {},
                        terminalViewModel = terminalVm,
                        paneViewport = { tab, modifier ->
                            Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                                Text(tab.displayTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun connectHomelab(): FakeSshConnector.FakeShell {
        composeRule.runOnUiThread { terminalVm.connect("term", TerminalTarget.SavedHost("homelab")) }
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            terminalVm.session("term").status.value is TerminalStatus.Connected
        }
        return connector.shells.last()
    }

    private fun printSession(shell: FakeSshConnector.FakeShell) {
        val e = "\u001b"
        val prompt = "$e[1;32msakif@homelab$e[0m:$e[1;34m~/projects/rembrowse$e[0m$ "
        composeRule.runOnUiThread {
            shell.print(
                "$e]0;sakif@homelab: ~/projects/rembrowse\u0007" +
                    "Welcome to Ubuntu 24.04.1 LTS (GNU/Linux 6.8.0-49-generic x86_64)\r\n\r\n" +
                    "  System load:  0.08              Processes:             214\r\n" +
                    "  Usage of /:   41.2% of 456.9GB  Users logged in:       1\r\n" +
                    "  Memory usage: 23%               IPv4 address for eth0: 192.168.1.20\r\n\r\n" +
                    "Last login: Fri Nov 21 18:02:11 2026 from 100.64.0.7\r\n" +
                    prompt + "git status\r\n" +
                    "On branch $e[1mmain$e[0m\r\nYour branch is up to date with '$e[1morigin/main$e[0m'.\r\n\r\n" +
                    "Changes not staged for commit:\r\n" +
                    "\t$e[31mmodified:   app/src/main/java/com/rembrowser/rembrowse/MainActivity.kt$e[0m\r\n" +
                    "\t$e[31mmodified:   docs/USER_GUIDE.md$e[0m\r\n\r\n" +
                    prompt + "ls -la\r\n" +
                    "total 116\r\n" +
                    "drwxr-xr-x 10 sakif sakif  4096 Nov 21 16:37 $e[1;34m.$e[0m\r\n" +
                    "drwxr-xr-x  4 sakif sakif  4096 Nov 20 03:37 $e[1;34m..$e[0m\r\n" +
                    "-rw-r--r--  1 sakif sakif 30671 Nov 21 16:35 CHANGELOG.md\r\n" +
                    "-rw-r--r--  1 sakif sakif  7373 Nov 21 16:37 README.md\r\n" +
                    "drwxr-xr-x  4 sakif sakif  4096 Nov 20 13:58 $e[1;34mapp$e[0m\r\n" +
                    "drwxr-xr-x  3 sakif sakif  4096 Nov 21 16:28 $e[1;34mdocs$e[0m\r\n" +
                    "-rwxr-xr-x  1 sakif sakif  8762 Nov 19 23:03 $e[1;32mgradlew$e[0m\r\n" +
                    prompt + "docker ps --format 'table {{.Names}}\\t{{.Status}}'\r\n" +
                    "NAMES        STATUS\r\nplex         Up 3 days (healthy)\r\nhome-assist  Up 3 days\r\n$e[33mbackup       Restarting (1) 4 seconds ago$e[0m\r\n" +
                    prompt
            )
        }
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
    }

    /** Types [text] into the terminal and echoes it, as the shell would. */
    private fun type(shell: FakeSshConnector.FakeShell, text: String) {
        val view = findTerminal(composeRule.activity.window.decorView)!!
        composeRule.runOnUiThread {
            view.requestFocus()
            text.forEach { char ->
                val code = KeyEvent.keyCodeFromString("KEYCODE_" + if (char == ' ') "SPACE" else char.uppercase())
                view.onKeyDown(code, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0))
            }
            shell.print(text)
        }
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
    }

    private fun findTerminal(view: View): TerminalScreenView? = when (view) {
        is TerminalScreenView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findTerminal(view.getChildAt(it)) }
        else -> null
    }

    private fun save(name: String) {
        composeRule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun picker() {
        launch()
        save("t01-new-terminal")
    }

    @Test
    fun session() {
        launch(terminalUrl = "rem://terminal/host/homelab")
        val shell = connectHomelab()
        printSession(shell)
        type(shell, "git s")
        save("t02-session")
    }

    @Test
    fun sessionMidnightGreen() {
        launch(
            terminalUrl = "rem://terminal/host/homelab",
            terminalSettings = TerminalSettings(theme = TerminalThemePreset.MIDNIGHT_GREEN, font = TerminalFont.FIRA_MONO, cursorStyle = TerminalCursorStyle.BAR)
        )
        val shell = connectHomelab()
        printSession(shell)
        type(shell, "docker c")
        save("t02b-session-midnight-green")
    }

    @Test
    fun themeMenu() {
        launch(terminalUrl = "rem://terminal/host/homelab", terminalSettings = TerminalSettings(theme = TerminalThemePreset.DRACULA))
        printSession(connectHomelab())
        composeRule.onNodeWithTag("terminal_theme_button").performClick()
        save("t11-theme-menu")
    }

    @Test
    fun settingsAppearance() {
        launch(terminalSettings = TerminalSettings(theme = TerminalThemePreset.MIDNIGHT_GREEN))
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TERMINAL) }
        composeRule.onNodeWithTag("terminal_suggestions").performScrollTo()
        save("t09b-settings-appearance")
    }

    @Test
    fun settingsCopyAndPaste() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TERMINAL) }
        composeRule.onNodeWithTag("terminal_ctrl_v_pastes").performScrollTo()
        save("t09c-settings-copy-paste")
    }

    @Test
    fun splitWithDocs() {
        launch(terminalUrl = "rem://terminal/host/homelab", split = true)
        printSession(connectHomelab())
        save("t03-split")
    }

    @Test
    fun hostKey() {
        connector.hostKey = HostKeyCheck("", 0, "ssh-ed25519", "AAAAnew", "SHA256:cdvxaIN6RP+t0WZSOrRvWO69Dj2Sd3N/aAjUkR2+gfg", changed = false)
        launch(terminalUrl = "rem://terminal/host/pi")
        composeRule.runOnUiThread { terminalVm.connect("term", TerminalTarget.SavedHost("pi")) }
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            terminalVm.session("term").status.value is TerminalStatus.ConfirmingHostKey
        }
        save("t04-host-key")
    }

    @Test
    fun changedHostKey() {
        connector.hostKey = HostKeyCheck("", 0, "ssh-ed25519", "AAAAother", "SHA256:Xw2bN8cQ4rT6yU1iO3pA5sD7fG9hJ2kL4zX6cV8bN0m", changed = true)
        launch(terminalUrl = "rem://terminal/host/homelab")
        composeRule.runOnUiThread { terminalVm.connect("term", TerminalTarget.SavedHost("homelab")) }
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            terminalVm.session("term").status.value is TerminalStatus.ConfirmingHostKey
        }
        save("t05-host-key-changed")
    }

    @Test
    fun password() {
        launch(terminalUrl = "rem://terminal/host/pi")
        composeRule.runOnUiThread { terminalVm.connect("term", TerminalTarget.SavedHost("pi")) }
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            terminalVm.session("term").status.value is TerminalStatus.NeedsPassword
        }
        save("t06-password")
    }

    @Test
    fun disconnected() {
        launch(terminalUrl = "rem://terminal/host/homelab")
        val shell = connectHomelab()
        printSession(shell)
        composeRule.runOnUiThread { shell.end("Connection reset by peer") }
        ShadowLooper.idleMainLooper()
        save("t07-disconnected")
    }

    @Test
    fun failed() {
        connector.failure = "Couldn't find raspberrypi.lan:22. Check the host name."
        launch(terminalUrl = "rem://terminal/host/pi")
        composeRule.runOnUiThread { terminalVm.connect("term", TerminalTarget.SavedHost("pi")) }
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            terminalVm.session("term").status.value is TerminalStatus.Disconnected
        }
        save("t08-failed")
    }

    @Test
    fun settings() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TERMINAL) }
        save("t09-settings")
    }

    @Test
    fun otherAccentAndLightTheme() {
        launch(settings = BrowserSettings(themeMode = ThemeMode.LIGHT, accent = AccentColor.entries[2]), terminalUrl = "rem://terminal/host/homelab", split = true)
        printSession(connectHomelab())
        save("t10-light-accent")
    }

    @Test
    fun paperTheme() {
        launch(
            settings = BrowserSettings(themeMode = ThemeMode.LIGHT),
            terminalUrl = "rem://terminal/host/homelab",
            terminalSettings = TerminalSettings(theme = TerminalThemePreset.PAPER)
        )
        val shell = connectHomelab()
        printSession(shell)
        type(shell, "sudo sys")
        save("t12-paper")
    }

    @Test
    fun touchSelection() {
        launch(terminalUrl = "rem://terminal/host/homelab")
        printSession(connectHomelab())
        selectByTouch("README.md")
        save("t13-touch-selection")
    }

    @Test
    fun touchSelectionPaper() {
        launch(
            settings = BrowserSettings(themeMode = ThemeMode.LIGHT),
            terminalUrl = "rem://terminal/host/homelab",
            terminalSettings = TerminalSettings(theme = TerminalThemePreset.PAPER)
        )
        printSession(connectHomelab())
        selectByTouch("README.md")
        save("t13b-touch-selection-paper")
    }

    /**
     * A finger long-presses [word], then drags the end handle two rows down: a selection over
     * three rows, with both handles showing.
     */
    private fun selectByTouch(word: String) {
        val view = findTerminal(composeRule.activity.window.decorView)!!
        val emulator = view.connection!!.emulator
        val line = { row: Int -> emulator.screen.getSelectedText(0, row, emulator.mColumns - 1, row) }
        val row = (0 until emulator.mRows).first { line(it).contains(word) }
        val pressAt = view.touchSelection.cellCenter(TerminalScreenView.Cell(line(row).indexOf(word) + 1, row))
        finger(view, MotionEvent.ACTION_DOWN, pressAt)
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout() + 100L, TimeUnit.MILLISECONDS)
        finger(view, MotionEvent.ACTION_UP, pressAt)

        val handle = view.touchSelection.handleCenter(start = false)!!
        val twoRows = 2 * (view.touchSelection.cellCenter(TerminalScreenView.Cell(0, 1)).y - view.touchSelection.cellCenter(TerminalScreenView.Cell(0, 0)).y)
        finger(view, MotionEvent.ACTION_DOWN, handle)
        finger(view, MotionEvent.ACTION_MOVE, PointF(handle.x, handle.y + twoRows))
        finger(view, MotionEvent.ACTION_UP, PointF(handle.x, handle.y + twoRows))
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
    }

    private var fingerDownAt = 0L

    private fun finger(view: View, action: Int, at: PointF) {
        composeRule.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) fingerDownAt = now
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = at.x; y = at.y; pressure = 1f; size = 1f })
            val event = MotionEvent.obtain(fingerDownAt, now, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }
}
