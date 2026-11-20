package com.rembrowser.rembrowse

import android.annotation.SuppressLint
import android.app.Application
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rembrowser.rembrowse.data.AndroidDownloadStorage
import com.rembrowser.rembrowse.data.AndroidUpdateInstaller
import com.rembrowser.rembrowse.data.OkHttpUpdateSource
import com.rembrowser.rembrowse.data.SharedPrefsUpdatePreferences
import com.rembrowser.rembrowse.data.OkHttpAmoApiClient
import com.rembrowser.rembrowse.data.SharedPrefsBrowserStateRepository
import com.rembrowser.rembrowse.data.SharedPrefsBrowsingHistoryStore
import com.rembrowser.rembrowse.data.SharedPrefsDefaultExtensionStore
import com.rembrowser.rembrowse.data.SharedPrefsDownloadHistoryStore
import com.rembrowser.rembrowse.engine.GeckoBrowserEngine
import com.rembrowser.rembrowse.engine.GeckoExtensionManager
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.ui.BrowserChromeState
import com.rembrowser.rembrowse.ui.RemBrowseScreen
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.util.BrowserShortcut
import com.rembrowser.rembrowse.util.KeyboardShortcuts
import com.rembrowser.rembrowse.util.UrlResolver
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import com.rembrowser.rembrowse.viewmodel.UpdatesViewModel
import java.io.File
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoView

private const val TAG = "MainActivity"

/** Under the cache directory; shared with the installer through the manifest's FileProvider. */
private const val UPDATES_DIR = "updates"

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

    /**
     * The upload request the system file picker is open for. Kept here, not in the activity, so
     * a re-created activity neither opens the picker a second time nor loses the answer.
     */
    var filePickerPromptId: String? = null

    init {
        val runtime = (application as RemBrowseApplication).geckoRuntime
        engine = GeckoBrowserEngine(application, providedRuntime = runtime)
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
                baseUrl = BuildConfig.UPDATE_BASE_URL,
                userAgent = "RemBrowse/${BuildConfig.VERSION_NAME} (Android; GeckoView)"
            ),
            installer = AndroidUpdateInstaller(application),
            preferences = SharedPrefsUpdatePreferences(application),
            updatesDir = File(application.cacheDir, UPDATES_DIR),
            currentVersionCode = BuildConfig.VERSION_CODE,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            baseUrl = BuildConfig.UPDATE_BASE_URL,
            scope = viewModelScope
        )
        updates.onAppStarted()
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
        downloads.shutdown()
        browser.shutdown()
        extensionManager.dismissPopup()
    }
}

class MainActivity : ComponentActivity() {

    private val holder: BrowserSessionHolder by viewModels()
    private val chrome = BrowserChromeState()

    // Which tab each on-screen web view is showing, so keyboard focus can be given to a page.
    private val paneViewTabs = WeakHashMap<GeckoView, String>()

    // An Enter press that submitted a text field must not also deliver its key release to
    // whatever receives focus next (the page, or a button).
    private var swallowEnterKeyUp = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = initialSettings)
            val darkTheme = settings.useDarkTheme

            // Keep system bar icons legible against the chrome, and let pages that honour
            // prefers-color-scheme match the browser.
            DisposableEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            // A fullscreen page gets the screen to itself: the status and navigation bars go,
            // and come back for a moment on a swipe from the edge, or for good when it ends.
            val pageFullscreen by fullscreenFlow.collectAsStateWithLifecycle(initialValue = false)
            DisposableEffect(pageFullscreen) {
                val systemBars = WindowCompat.getInsetsController(window, window.decorView)
                if (pageFullscreen) {
                    systemBars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    systemBars.hide(WindowInsetsCompat.Type.systemBars())
                }
                onDispose {
                    if (pageFullscreen) systemBars.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            LaunchedEffect(darkTheme) {
                engine.runtime.settings.setPreferredColorScheme(
                    if (darkTheme) {
                        GeckoRuntimeSettings.COLOR_SCHEME_DARK
                    } else {
                        GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                    }
                )
            }

            RemBrowseTheme(darkTheme = darkTheme, accent = settings.accent) {
                val pageBackground = MaterialTheme.colorScheme.surfaceContainerLowest.toArgb()
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
                    onPickFiles = ::pickFilesFor,
                    updatesViewModel = holder.updates,
                    onFocusWebContent = { tabId ->
                        paneViewTabs.entries.firstOrNull { it.value == tabId }?.key?.requestFocus()
                    },
                    paneViewport = { tab, modifier ->
                        val geckoViewHolder = remember { arrayOfNulls<GeckoView>(1) }
                        DisposableEffect(Unit) {
                            onDispose {
                                geckoViewHolder[0]?.let {
                                    paneViewTabs.remove(it)
                                    engine.detachView(it)
                                }
                            }
                        }
                        AndroidView(
                            modifier = modifier,
                            factory = { ctx ->
                                GeckoView(ctx).apply {
                                    setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                                    // Set once: covering again on every update would hide a
                                    // page that has already painted.
                                    coverUntilFirstPaint(pageBackground)
                                    geckoViewHolder[0] = this
                                    paneViewTabs[this] = tab.id
                                    engine.attachSessionToView(tab.id, this)
                                }
                            },
                            update = { view ->
                                geckoViewHolder[0] = view
                                paneViewTabs[view] = tab.id
                                engine.attachSessionToView(tab.id, view)
                            }
                        )
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
                                GeckoView(ctx).apply {
                                    setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
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

    // Shortcuts are read here, before the focused view, so they also work while a web page has
    // focus. Lint flags the override as a restricted API although it is a public Activity hook.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                swallowEnterKeyUp = holder.browser.uiState.value.isAddressBarFocused || chrome.isTabSearchFocused
            } else if (event.action == KeyEvent.ACTION_UP && swallowEnterKeyUp) {
                swallowEnterKeyUp = false
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val shortcut = KeyboardShortcuts.resolve(
                keyCode = event.keyCode,
                ctrl = event.isCtrlPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
                meta = event.isMetaPressed,
                repeatCount = event.repeatCount
            )
            if (shortcut != null) {
                performShortcut(shortcut)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun performShortcut(shortcut: BrowserShortcut) {
        val browser = holder.browser
        when (shortcut) {
            BrowserShortcut.NEW_TAB -> {
                browser.openNewTab()
                chrome.focusAddressBar()
            }
            BrowserShortcut.CLOSE_TAB -> browser.uiState.value.activeTabId?.let { browser.closeTab(it) }
            BrowserShortcut.FOCUS_ADDRESS_BAR -> chrome.focusAddressBar()
            BrowserShortcut.RELOAD -> browser.reloadOrStop()
            BrowserShortcut.BACK -> browser.goBack()
            BrowserShortcut.FORWARD -> browser.goForward()
            BrowserShortcut.NEXT_TAB -> browser.selectAdjacentTab(1)
            BrowserShortcut.PREVIOUS_TAB -> browser.selectAdjacentTab(-1)
            BrowserShortcut.TOGGLE_BOOKMARK -> browser.toggleBookmarkForActiveTab()
            BrowserShortcut.COMMAND_PALETTE -> chrome.showCommandPalette = true
            BrowserShortcut.OPEN_SETTINGS -> {
                chrome.showTabSwitcher = false
                browser.openSettings()
            }
            BrowserShortcut.OPEN_DOWNLOADS -> {
                chrome.showTabSwitcher = false
                browser.openSettings(SettingsSection.DOWNLOADS)
            }
            BrowserShortcut.OPEN_HISTORY -> {
                chrome.showTabSwitcher = false
                browser.openSettings(SettingsSection.HISTORY)
            }
            BrowserShortcut.TOGGLE_SPLIT_VIEW -> if (browser.uiState.value.isSplit) {
                browser.setLayoutMode(LayoutMode.SINGLE_1X1)
            } else {
                browser.splitCurrentTab(currentOnLeft = true)
            }
            BrowserShortcut.LAYOUT_SINGLE -> browser.setLayoutMode(LayoutMode.SINGLE_1X1)
            BrowserShortcut.LAYOUT_SIDE_BY_SIDE -> browser.splitCurrentTab(currentOnLeft = true)
            BrowserShortcut.LAYOUT_STACKED -> browser.setLayoutMode(LayoutMode.SPLIT_2X1)
            BrowserShortcut.LAYOUT_GRID -> browser.setLayoutMode(LayoutMode.GRID_2X2)
        }
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
}
