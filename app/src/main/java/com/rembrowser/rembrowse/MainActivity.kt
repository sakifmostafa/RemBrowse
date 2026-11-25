package com.rembrowser.rembrowse

import android.annotation.SuppressLint
import android.app.Application
import android.app.DownloadManager
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.compose.ui.platform.ComposeView
import android.view.MotionEvent
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rembrowser.rembrowse.data.ImageClipboard
import com.rembrowser.rembrowse.data.GeckoImageClipboard
import com.rembrowser.rembrowse.data.ScreenshotClipboard
import com.rembrowser.rembrowse.data.AndroidDownloadStorage
import com.rembrowser.rembrowse.data.AndroidUpdateInstaller
import com.rembrowser.rembrowse.data.KeystoreSecretStore
import com.rembrowser.rembrowse.data.OkHttpAmoApiClient
import com.rembrowser.rembrowse.data.OkHttpUpdateSource
import com.rembrowser.rembrowse.data.SharedPrefsBrowserStateRepository
import com.rembrowser.rembrowse.data.SharedPrefsBrowsingHistoryStore
import com.rembrowser.rembrowse.data.SharedPrefsDefaultExtensionStore
import com.rembrowser.rembrowse.data.SharedPrefsDownloadHistoryStore
import com.rembrowser.rembrowse.data.SharedPrefsSitePermissionStore
import com.rembrowser.rembrowse.data.SharedPrefsTerminalStore
import com.rembrowser.rembrowse.data.SharedPrefsUpdatePreferences
import com.rembrowser.rembrowse.engine.GeckoBrowserEngine
import com.rembrowser.rembrowse.engine.GeckoExtensionManager
import com.rembrowser.rembrowse.engine.GeckoSiteNotifications
import com.rembrowser.rembrowse.engine.PaneGeckoView
import com.rembrowser.rembrowse.engine.WindowTouchpadInput
import com.rembrowser.rembrowse.engine.WindowScreenshot
import com.rembrowser.rembrowse.engine.asPageLayer
import com.rembrowser.rembrowse.engine.usePageRendering
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PageRendering
import com.rembrowser.rembrowse.model.PxRect
import com.rembrowser.rembrowse.model.ThemeMode
import com.rembrowser.rembrowse.terminal.TrileadSshConnector
import com.rembrowser.rembrowse.ui.BrowserChromeState
import com.rembrowser.rembrowse.ui.FullscreenWindowEffect
import com.rembrowser.rembrowse.ui.RemBrowseScreen
import com.rembrowser.rembrowse.ui.components.ChromePointerIconClaim
import com.rembrowser.rembrowse.ui.components.LocalChromePointerIconClaim
import com.rembrowser.rembrowse.ui.components.ScreenshotResult
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.util.UrlResolver
import com.rembrowser.rembrowse.util.keyboardShortcutGroups
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import com.rembrowser.rembrowse.viewmodel.TerminalViewModel
import com.rembrowser.rembrowse.viewmodel.UpdatesViewModel
import org.mozilla.geckoview.GeckoView
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.WeakHashMap
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.rembrowser.rembrowse.engine.SwipeDirection
import com.rembrowser.rembrowse.engine.SwipeNavigation
import com.rembrowser.rembrowse.ui.components.SwipeIndicatorState
import com.rembrowser.rembrowse.ui.components.SwipeNavigationIndicator
import androidx.compose.ui.Modifier

private const val TAG = "MainActivity"

/** Under the cache directory; shared with the installer through the manifest's FileProvider. */
private const val UPDATES_DIR = "updates"

/** The screenshot notice's picture is drawn 40dp high; it is made twice that, to stay sharp. */
private const val SCREENSHOT_THUMBNAIL_DP = 80

/**
 * Owns the engine sessions and view models for as long as the browser window exists.
 *
 * Being an Android ViewModel, it survives activity re-creation, so live tabs are neither
 * duplicated nor leaked when the system rebuilds the activity, and they are closed exactly
 * once, when the window really goes away.
 */
class BrowserSessionHolder(application: Application) : AndroidViewModel(application) {

    val engine: GeckoBrowserEngine
    val extensionManager: GeckoExtensionManager
    val browser: BrowserViewModel
    val extensions: ExtensionsViewModel
    val downloads: DownloadsViewModel
    val updates: UpdatesViewModel
    val terminal: TerminalViewModel

    /**
     * The upload request the system file picker is open for. Kept here, not in the activity, so
     * a re-created activity neither opens the picker a second time nor loses the answer.
     */
    var filePickerPromptId: String? = null

    /** Puts pictures from pages on the clipboard (the page menu's Copy image). */
    val imageClipboard: ImageClipboard

    /** Puts screenshots (Ctrl+Alt+S) on the clipboard, through the same files as Copy image. */
    val screenshotClipboard: ScreenshotClipboard

    /**
     * Asks Android for the camera, microphone, location and notification permissions that sites
     * need, through the activity on screen. Kept here so a request survives activity re-creation.
     */
    val androidPermissions = ActivityPermissionBridge(application)

    init {
        val runtime = (application as RemBrowseApplication).geckoRuntime
        val clipboard = GeckoImageClipboard(application, runtime)
        imageClipboard = clipboard
        screenshotClipboard = clipboard
        engine = GeckoBrowserEngine(
            application,
            providedRuntime = runtime,
            sitePermissionStore = SharedPrefsSitePermissionStore(application),
            androidPermissions = androidPermissions,
            pageImageClipboard = clipboard
        )
        extensionManager = GeckoExtensionManager(
            runtime = runtime,
            openExtensionTab = { url -> engine.adoptNewTab(url, openerTabId = null) },
            openUrlInNewTab = ::openTabForExtension
        )
        engine.onFocusedSessionChanged = extensionManager::setActiveSession
        browser = BrowserViewModel(
            engine = engine,
            repository = SharedPrefsBrowserStateRepository(application),
            historyStore = SharedPrefsBrowsingHistoryStore(application)
        )
        extensions = ExtensionsViewModel(
            extensionManager = extensionManager,
            amoApiClient = OkHttpAmoApiClient(
                userAgent = "RemBrowse/${BuildConfig.VERSION_NAME} (Android; GeckoView)"
            ),
            scope = viewModelScope,
            defaults = SharedPrefsDefaultExtensionStore(application)
        )
        downloads = DownloadsViewModel(
            storage = AndroidDownloadStorage(application),
            history = SharedPrefsDownloadHistoryStore(application),
            scope = viewModelScope,
            askBeforeDownloading = { browser.uiState.value.settings.askBeforeDownloading },
            onRequestSettled = browser::closeTabLeftBehindByDownload
        )
        browser.downloadHandler = downloads::onDownloadRequested
        updates = UpdatesViewModel(
            source = OkHttpUpdateSource(
                hostedAt = BuildConfig.UPDATE_URL,
                userAgent = "RemBrowse/${BuildConfig.VERSION_NAME} (Android; GeckoView)"
            ),
            installer = AndroidUpdateInstaller(application),
            preferences = SharedPrefsUpdatePreferences(application),
            updatesDir = File(application.cacheDir, UPDATES_DIR),
            currentVersionCode = BuildConfig.VERSION_CODE,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            deviceSdk = Build.VERSION.SDK_INT,
            // Only if the source cannot say where it read the files
            baseUrl = BuildConfig.UPDATE_URL,
            scope = viewModelScope
        )
        updates.onAppStarted()

        val mainHandler = Handler(Looper.getMainLooper())
        terminal = TerminalViewModel(
            store = SharedPrefsTerminalStore(application),
            secrets = KeystoreSecretStore(application),
            connector = TrileadSshConnector(),
            scope = viewModelScope,
            postToMain = { mainHandler.post(it) },
            onTabTitleChanged = browser::setTerminalTitle
        )
        // A terminal tab that closes takes its connection with it
        viewModelScope.launch {
            browser.uiState
                .map { state -> state.tabs.filter { it.isTerminal }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect(terminal::retainSessions)
        }
    }

    private fun openTabForExtension(url: String) {
        browser.openNewTab(url)
    }

    /** Copies a picked `.xpi` into the cache off the main thread, installs it, then cleans up. */
    fun installLocalExtension(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    val importDir = File(app.cacheDir, "xpi_imports").apply { mkdirs() }
                    val target = File(importDir, "import-${System.currentTimeMillis()}.xpi")
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("Unable to open the selected file")
                    input.use { source -> target.outputStream().use { source.copyTo(it) } }
                    target
                }.onFailure { Log.e(TAG, "Failed to import local XPI", it) }.getOrNull()
            }
            if (copy == null) {
                extensions.reportError("Couldn't read the selected file.")
            } else {
                extensions.installFromUri(Uri.fromFile(copy).toString(), onFinished = { copy.delete() })
            }
        }
    }

    override fun onCleared() {
        terminal.closeAll()
        downloads.shutdown()
        browser.shutdown()
        extensionManager.dismissPopup()
    }
}

class MainActivity : ComponentActivity() {

    private val holder: BrowserSessionHolder by viewModels()
    private val chrome = BrowserChromeState()

    // Lets the chrome's mouse cursor (a pane divider's resize arrows) show over a page's edge
    private val chromePointerIcon = ChromePointerIconClaim()

    // Which tab each on-screen web view is showing, so keyboard focus can be given to a page.
    private val paneViewTabs = WeakHashMap<GeckoView, String>()

    /**
     * Two-finger swipes on [view] go back and forward in the tab it shows now (see
     * [com.rembrowser.rembrowse.engine.TouchpadSwipeNavigator]). Back can also close a tab a link
     * opened and return to its opener, as the toolbar's Back does.
     */
    private fun swipeNavigationFor(view: GeckoView, browser: BrowserViewModel): SwipeNavigation =
        object : SwipeNavigation {
            override val enabled: Boolean
                get() = browser.uiState.value.settings.swipeNavigation

            override val reverseDirection: Boolean
                get() = browser.uiState.value.settings.reverseSwipeDirection

            override fun canGo(direction: SwipeDirection): Boolean {
                val state = browser.uiState.value
                val tab = state.tabs.firstOrNull { it.id == paneViewTabs[view] } ?: return false
                return when (direction) {
                    SwipeDirection.BACK -> state.canNavigateBack(tab)
                    SwipeDirection.FORWARD -> tab.canGoForward
                }
            }

            override fun go(direction: SwipeDirection) {
                val tabId = paneViewTabs[view] ?: return
                val pane = browser.uiState.value.paneIndexOf(tabId).takeIf { it >= 0 } ?: return
                when (direction) {
                    SwipeDirection.BACK -> browser.goBack(pane)
                    SwipeDirection.FORWARD -> browser.goForward(pane)
                }
            }
        }

    // The window was made fullscreen for a page and should be given back when the page leaves it
    private var desktopFullscreenRequested = false

    // The colour a pane shows before its page paints, in the theme on screen (light or dark as
    // resolved from the device, when the theme follows it). Kept for screenshots.
    private var pageBackgroundArgb = AndroidColor.BLACK

    // Upload buttons on web pages: one file, or several when the page allows it
    private val pickOneFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> onFilesPicked(listOfNotNull(uri)) }

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> onFilesPicked(uris) }

    private val pickLocalXpiLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            holder.installLocalExtension(uri)
        }
    }

    // Android permissions that sites need (camera, microphone, location, notifications). The
    // answer may reach a re-created activity: the request it belongs to waits in the holder.
    private val androidPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { holder.androidPermissions.onResult() }

    private val launchAndroidPermissions: (Array<String>) -> Unit = { androidPermissionsLauncher.launch(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch screen (the icon on the chrome's colour) gives way to the browser as soon
        // as it draws; nothing holds it on screen
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // The window already starts in the mode set last time (applyNightModeToWindow)
        enableEdgeToEdge()

        val engine = holder.engine
        val extensionManager = holder.extensionManager

        // Only for a fresh launch: a re-created activity still carries the original intent and
        // would otherwise open the same link again.
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        val initialSettings = holder.browser.uiState.value.settings
        val settingsFlow = holder.browser.uiState.map { it.settings }.distinctUntilChanged()
        val fullscreenFlow = holder.browser.uiState.map { it.fullscreenTabId != null }.distinctUntilChanged()

        val content = ComposeView(this).apply {
            setContent {
                val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = initialSettings)
                // The activity handles uiMode changes itself, so the device switching between
                // light and dark recomposes here instead of restarting the activity
                val theme = resolveTheme(this@MainActivity, settings, systemIsDark = isSystemInDarkTheme())
                val darkTheme = theme.dark
                LaunchedEffect(settings.themeMode) { applyNightModeToWindow(settings.themeMode) }
                LaunchedEffect(darkTheme) { applyWindowTheme(darkTheme) }

                // Keep system bar icons legible against the chrome, and let pages that honour
                // prefers-color-scheme match the browser.
                DisposableEffect(darkTheme) {
                    val style = if (darkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    blendCaptionBar(darkTheme)
                    onDispose { }
                }
                // A fullscreen page gets the screen to itself: the status and navigation bars go,
                // and come back for a moment on a swipe from the edge, or for good when it ends.
                val pageFullscreen by fullscreenFlow.collectAsStateWithLifecycle(initialValue = false)
                FullscreenWindowEffect(
                    fullscreen = pageFullscreen,
                    onEnter = {
                        val systemBars = WindowCompat.getInsetsController(window, window.decorView)
                        systemBars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        systemBars.hide(WindowInsetsCompat.Type.systemBars())
                        requestDesktopFullscreen(enter = true)
                    },
                    onExit = {
                        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                        requestDesktopFullscreen(enter = false)
                    }
                )
                LaunchedEffect(theme.pageColorScheme) {
                    engine.runtime.settings.setPreferredColorScheme(theme.pageColorScheme)
                }

                RemBrowseTheme(theme.colorScheme) {
                    val pageBackground = theme.pageBackground.toArgb()
                    SideEffect { pageBackgroundArgb = pageBackground }
                    CompositionLocalProvider(LocalChromePointerIconClaim provides chromePointerIcon) {
                        RemBrowseScreen(
                            browserViewModel = holder.browser,
                            extensionsViewModel = holder.extensions,
                            chrome = chrome,
                            versionName = BuildConfig.VERSION_NAME,
                            downloadsViewModel = holder.downloads,
                            onPickLocalXpi = {
                                pickLocalXpiLauncher.launch(arrayOf("*/*"))
                            },
                            onOpenDownload = ::openDownloadedFile,
                            onOpenDownloadsFolder = ::openDownloadsFolder,
                            onCopyImage = holder.imageClipboard::copyImage,
                            onCaptureScreen = { area -> captureScreen(area) },
                            onShowKeyboardShortcuts = ::requestShowKeyboardShortcuts,
                            onPickFiles = ::pickFilesFor,
                            updatesViewModel = holder.updates,
                            terminalViewModel = holder.terminal,
                            onFocusWebContent = { tabId ->
                                paneViewTabs.entries.firstOrNull { it.value == tabId }?.key?.requestFocus()
                            },
                            paneViewport = { tab, modifier ->
                                // A page shown another way needs another view: the choice is made
                                // once, when the view is created
                                val rendering = settings.pageRendering
                                key(rendering) {
                                    val geckoViewHolder = remember { arrayOfNulls<GeckoView>(1) }
                                    val swipeArrow = remember { SwipeIndicatorState() }
                                    DisposableEffect(Unit) {
                                        onDispose {
                                            geckoViewHolder[0]?.let {
                                                paneViewTabs.remove(it)
                                                engine.detachView(it)
                                            }
                                        }
                                    }
                                    // The swipe arrow is drawn over the page, inside its pane
                                    Box(modifier = modifier) {
                                        AndroidView(
                                            modifier = Modifier.fillMaxSize(),
                                            factory = { ctx ->
                                                PaneGeckoView(ctx, chromePointerIcon::isClaimed, swipeArrow).apply {
                                                    usePageRendering(rendering)
                                                    // Set once: covering again on every update would hide
                                                    // a page that has already painted.
                                                    coverUntilFirstPaint(pageBackground)
                                                    geckoViewHolder[0] = this
                                                    paneViewTabs[this] = tab.id
                                                    swipeNavigation = swipeNavigationFor(this, holder.browser)
                                                    engine.attachSessionToView(tab.id, this)
                                                }
                                            },
                                            update = { view ->
                                                geckoViewHolder[0] = view
                                                paneViewTabs[view] = tab.id
                                                engine.attachSessionToView(tab.id, view)
                                            }
                                        )
                                        SwipeNavigationIndicator(swipeArrow, Modifier.matchParentSize())
                                    }
                                }
                            },
                            extensionPopupContent = { modifier ->
                                val popupViewHolder = remember { arrayOfNulls<GeckoView>(1) }
                                DisposableEffect(Unit) {
                                    onDispose {
                                        popupViewHolder[0]?.releaseSession()
                                    }
                                }
                                AndroidView(
                                    modifier = modifier,
                                    factory = { ctx ->
                                        PaneGeckoView(ctx).apply {
                                            // Always a texture: the popup's window fades in and
                                            // has rounded corners of its own
                                            usePageRendering(
                                                if (settings.pageRendering == PageRendering.LEGACY) {
                                                    PageRendering.LEGACY
                                                } else {
                                                    PageRendering.STANDARD
                                                }
                                            )
                                            coverUntilFirstPaint(pageBackground)
                                            popupViewHolder[0] = this
                                            extensionManager.getPopupGeckoSession()?.let { setSession(it) }
                                        }
                                    },
                                    update = { view ->
                                        val session = extensionManager.getPopupGeckoSession()
                                        if (session != null && view.session !== session) {
                                            view.releaseSession()
                                            view.setSession(session)
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
        // The window's content sits in a layout that reads the browser's shortcuts before the
        // keyboard app (input method) sees them: see BrowserKeys.windowContent
        setContentView(keys.windowContent(content) { currentFocus })
    }

    /**
     * Tells Android which mode the app is in, so it starts RemBrowse, its launch screen and its
     * window in that mode, and the platform's own pieces (text selection toolbar, autofill)
     * match. Android keeps the choice between launches; it is set again at every start to follow
     * the saved setting. When the mode changes, the configuration changes with it.
     */
    private fun applyNightModeToWindow(mode: ThemeMode) {
        runCatching { getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode.applicationNightMode) }
            .onFailure { Log.w(TAG, "Couldn't set the app's night mode", it) }
    }

    /**
     * In a desktop window (a Googlebook), the window's header (the caption bar with its close,
     * maximise and minimise buttons) is drawn over the window rather than in a colour of its own,
     * so the chrome's colour runs up through it and the window reads as one surface, as Chrome's
     * does; its buttons are tinted for a light or a dark theme. The content already stays below
     * it (safeDrawingPadding includes the caption bar), and the system still drags and resizes
     * the window from the header.
     */
    private fun blendCaptionBar(darkTheme: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val transparent = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
        val lightCaption = WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        window.insetsController?.setSystemBarsAppearance(
            transparent or (if (darkTheme) 0 else lightCaption),
            transparent or lightCaption
        )
    }

    /**
     * In a desktop window (a Googlebook), hiding the system bars leaves the window its size, so a
     * fullscreen video would only fill the window. Android 15 lets an app in such a window ask
     * for the whole screen, as Chrome does for fullscreen video, and give it back afterwards.
     */
    private fun requestDesktopFullscreen(enter: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (enter) {
            // Only a freeform (desktop) window can be made fullscreen this way
            if (!isInMultiWindowMode) return
            desktopFullscreenRequested = true
            runCatching { requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, null) }
                .onFailure { Log.w(TAG, "Couldn't make the window fullscreen", it) }
        } else if (desktopFullscreenRequested) {
            // The window is no longer in multi-window mode while fullscreen, so this is tracked
            desktopFullscreenRequested = false
            runCatching { requestFullscreenMode(FULLSCREEN_MODE_REQUEST_EXIT, null) }
                .onFailure { Log.w(TAG, "Couldn't restore the window", it) }
        }
    }

    /** Opens the system file picker for a page's upload button, unless it is already open. */
    private fun pickFilesFor(prompt: PagePrompt.FilePicker) {
        if (holder.filePickerPromptId == prompt.id) return
        holder.filePickerPromptId = prompt.id
        val types = prompt.mimeTypes.toTypedArray()
        try {
            if (prompt.multiple) pickFilesLauncher.launch(types) else pickOneFileLauncher.launch(types)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app can pick files", e)
            onFilesPicked(emptyList())
        }
    }

    /** Answers the open upload request with what was picked; nothing picked cancels it. */
    private fun onFilesPicked(uris: List<Uri>) {
        val promptId = holder.filePickerPromptId ?: return
        holder.filePickerPromptId = null
        val browser = holder.browser
        // The page may have withdrawn the request (navigated away) while the picker was open
        val prompt = browser.uiState.value.pendingPrompts.firstOrNull { it.id == promptId } as? PagePrompt.FilePicker
            ?: return
        if (uris.isEmpty()) prompt.onDismiss() else prompt.onResult(uris.map(Uri::toString))
        browser.resolvePrompt(promptId)
    }

    override fun onStart() {
        super.onStart()
        holder.browser.setWindowShown(true)
        // Only a window on screen can show the system's permission dialog
        holder.androidPermissions.attach(launchAndroidPermissions)
    }

    // Gecko only pauses a page when its view's surface goes, and a TextureView's survives the
    // window being minimised; without this the pages on screen would keep running unseen
    override fun onStop() {
        super.onStop()
        holder.browser.setWindowShown(false)
        holder.androidPermissions.detach(launchAndroidPermissions)
    }

    override fun onResume() {
        super.onResume()
        // Back from the screen that lets RemBrowse install apps: finish a waiting update
        holder.updates.onResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    // Touchpad clicks reach the whole window as mouse clicks, and resting fingers mid-scroll are
    // never a press (see WindowTouchpadInput). Created on first use: it needs the display.
    private val touchpad by lazy { WindowTouchpadInput(this) }

    /** The browser's shortcuts in the system's Keyboard Shortcuts sheet (Meta+/). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        // With a terminal focused the shell keeps most keys (Ctrl+R, Ctrl+L, Alt+arrows...): the
        // sheet lists the keys that reach the browser there. Asked the same way as the shortcuts
        // themselves, so the sheet shows the keys that work right now.
        data?.addAll(keyboardShortcutGroups(inTerminal = keys.terminalHasKeyboard(currentFocus)))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        touchpad.dispatchTouchEvent(event) { super.dispatchTouchEvent(it) }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        WindowTouchpadInput.dispatchGenericMotionEvent(event) { super.dispatchGenericMotionEvent(it) }

    /**
     * The browser's shortcuts and the keys it takes before a page or the keyboard app (see
     * [BrowserKeys]), made from the session on first use. MainActivityInputTest gives its own
     * ([useKeys]) to run the activity's dispatchKeyEvent without the engine.
     */
    private var browserKeys: BrowserKeys? = null
    private val keys: BrowserKeys
        get() = browserKeys ?: BrowserKeys(holder.browser, chrome).also { browserKeys = it }

    @VisibleForTesting
    internal fun useKeys(keys: BrowserKeys) {
        browserKeys = keys
    }

    // Shortcuts are read here, before the focused view, so they also work while a web page has
    // focus. Lint flags the override as a restricted API although it is a public Activity hook.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keys.dispatchKeyEvent(event, currentFocus) { super.dispatchKeyEvent(it) }

    /**
     * Copies [area] of the window (the screenshot overlay already gone) to the clipboard as a PNG,
     * and reports it with a small picture for the notice. Pages shown behind the window (Direct
     * page rendering) are filled in from the engine (see [WindowScreenshot]).
     *
     * Runs in the screen's coroutine, which gives up after a while and cancels it: a cancelled
     * screenshot frees its pictures and never reaches the clipboard, however late it finishes.
     * Running out of memory for the pictures is a failed screenshot, not a crash.
     */
    private suspend fun captureScreen(area: PxRect): ScreenshotResult {
        val settings = holder.browser.uiState.value.settings
        val pages = if (settings.pageRendering == PageRendering.DIRECT) {
            paneViewTabs.keys.toList().mapNotNull { it.asPageLayer() }
        } else {
            emptyList()
        }
        // What a pane shows before its page paints, for a page that can't be captured
        val holeColor = pageBackgroundArgb
        val thumbnailHeight = (SCREENSHOT_THUMBNAIL_DP * resources.displayMetrics.density).roundToInt()
        // This screenshot's own job: once it is cancelled, nothing of it goes on the clipboard
        val job = currentCoroutineContext().job
        var shot: Bitmap? = null
        var thumbnail: Bitmap? = null
        var copied = false
        return try {
            val taken = WindowScreenshot(window).capture(area, pages, holeColor).also { shot = it }
            val png = withContext(Dispatchers.Default) {
                thumbnail = thumbnailOf(taken, thumbnailHeight)
                ByteArrayOutputStream().also { taken.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
            copied = suspendCancellableCoroutine { continuation ->
                holder.screenshotClipboard.copyScreenshot(png, stillWanted = { job.isActive }) { done ->
                    if (continuation.isActive) continuation.resume(done)
                }
            }
            val picture = thumbnail
            if (copied && picture != null) {
                ScreenshotResult.Copied(picture.asImageBitmap(), taken.width, taken.height)
            } else {
                ScreenshotResult.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory for a screenshot", e)
            ScreenshotResult.Failed
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't take a screenshot", e)
            ScreenshotResult.Failed
        } finally {
            shot?.recycle()
            // The notice keeps the small picture of a screenshot that was copied
            if (!copied) thumbnail?.recycle()
        }
    }

    /**
     * A small copy of [shot] for the notice, its shape kept: at most [height] pixels high and four
     * times that wide, and never larger than the screenshot itself.
     */
    private fun thumbnailOf(shot: Bitmap, height: Int): Bitmap {
        val scale = minOf(1f, height.toFloat() / shot.height, height * 4f / shot.width)
        val width = (shot.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (shot.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(shot, width, scaledHeight, true)
    }

    /** Hands a finished download to whichever app can show it. False when none can. */
    private fun openDownloadedFile(item: DownloadItem): Boolean {
        val uri = item.contentUri?.let(Uri::parse) ?: return false
        val view = Intent(Intent.ACTION_VIEW)
            // The type comes from the saved file's own extension, not from what the server claimed
            .setDataAndType(uri, AndroidDownloadStorage.mimeTypeFor(item.fileName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            startActivity(view)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to open the downloaded file", e)
            false
        }
    }

    private fun openDownloadsFolder() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: ActivityNotFoundException) {
            holder.downloads.reportMessage("This device has no app for browsing downloaded files.")
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == GeckoSiteNotifications.ACTION_OPEN) {
            openSiteNotification(intent)
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val dataUrl = intent.dataString?.trim().orEmpty()
        // Any app can send this activity a VIEW intent. Only ordinary web links are accepted;
        // file:, javascript:, about: or extension URLs from outside are ignored.
        if (UrlResolver.isWebUrl(dataUrl)) {
            holder.browser.openNewTab(url = dataUrl)
        } else if (dataUrl.isNotEmpty()) {
            Log.w(TAG, "Ignored VIEW intent with a non-web URL scheme")
        }
    }

    /**
     * A site notification was tapped, which brought the window forward: the tab with its site
     * comes to the front (see [BrowserViewModel.showTabForSite]), and then the page handles the
     * click as it would on a desktop (Telegram opens the chat). The page must be showing first,
     * since its handler may only switch what it shows. After a restart the notification is no
     * longer live, and its page is just shown.
     */
    private fun openSiteNotification(intent: Intent) {
        val notification = GeckoSiteNotifications.takeNotificationOf(intent)
        val page = notification?.source ?: GeckoSiteNotifications.sourceOf(intent)
        chrome.showTabSwitcher = false
        if (page != null) holder.browser.showTabForSite(page)
        notification?.let { tapped ->
            runCatching { tapped.click() }
                .onFailure { Log.w(TAG, "Couldn't pass a notification's click to its page", it) }
        }
    }
}
