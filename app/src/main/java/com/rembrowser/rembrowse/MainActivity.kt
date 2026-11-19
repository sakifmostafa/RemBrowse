package com.rembrowser.rembrowse

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.rembrowser.rembrowse.data.OkHttpAmoApiClient
import com.rembrowser.rembrowse.data.SharedPrefsBrowserStateRepository
import com.rembrowser.rembrowse.engine.GeckoBrowserEngine
import com.rembrowser.rembrowse.engine.GeckoExtensionManager
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.ui.RemBrowseScreen
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import org.mozilla.geckoview.GeckoView
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var engine: GeckoBrowserEngine
    private lateinit var extensionManager: GeckoExtensionManager
    private lateinit var browserViewModel: BrowserViewModel
    private lateinit var extensionsViewModel: ExtensionsViewModel
    private val addressBarFocusRequester = FocusRequester()

    private val pickLocalXpiLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            installFromPickedDocumentUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as RemBrowseApplication
        val runtime = app.geckoRuntime

        engine = GeckoBrowserEngine(applicationContext, providedRuntime = runtime)
        extensionManager = GeckoExtensionManager(runtime)

        val stateRepository = SharedPrefsBrowserStateRepository(applicationContext)
        val amoApiClient = OkHttpAmoApiClient()

        browserViewModel = BrowserViewModel(
            engine = engine,
            repository = stateRepository
        )
        extensionsViewModel = ExtensionsViewModel(
            extensionManager = extensionManager,
            amoApiClient = amoApiClient,
            scope = lifecycleScope
        )

        handleIncomingIntent(intent)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val activeTab = browserViewModel.uiState.value.activeTab
                    if (activeTab?.canGoBack == true) {
                        browserViewModel.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )

        setContent {
            RemBrowseTheme {
                RemBrowseScreen(
                    browserViewModel = browserViewModel,
                    extensionsViewModel = extensionsViewModel,
                    addressBarFocusRequester = addressBarFocusRequester,
                    onPickLocalXpi = {
                        pickLocalXpiLauncher.launch(arrayOf("*/*"))
                    },
                    paneViewport = { tab, modifier ->
                        val geckoViewHolder = remember { arrayOfNulls<GeckoView>(1) }
                        DisposableEffect(Unit) {
                            onDispose {
                                geckoViewHolder[0]?.let { engine.detachView(it) }
                            }
                        }
                        AndroidView(
                            modifier = modifier,
                            factory = { ctx ->
                                GeckoView(ctx).apply {
                                    setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                                    setBackgroundColor(0xFF0B1120.toInt())
                                    geckoViewHolder[0] = this
                                    engine.attachSessionToView(tab.id, this)
                                }
                            },
                            update = { view ->
                                geckoViewHolder[0] = view
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
                                    coverUntilFirstPaint(0xFF111827.toInt())
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_T -> {
                    browserViewModel.openNewTab()
                    return true
                }
                KeyEvent.KEYCODE_W -> {
                    browserViewModel.uiState.value.activeTabId?.let { browserViewModel.closeTab(it) }
                    return true
                }
                KeyEvent.KEYCODE_L -> {
                    runCatching { addressBarFocusRequester.requestFocus() }
                    return true
                }
                KeyEvent.KEYCODE_R -> {
                    browserViewModel.reloadOrStop()
                    return true
                }
                KeyEvent.KEYCODE_1 -> {
                    browserViewModel.setLayoutMode(LayoutMode.SINGLE_1X1)
                    return true
                }
                KeyEvent.KEYCODE_2 -> {
                    browserViewModel.setLayoutMode(LayoutMode.SPLIT_1X2)
                    return true
                }
                KeyEvent.KEYCODE_3 -> {
                    browserViewModel.setLayoutMode(LayoutMode.SPLIT_2X1)
                    return true
                }
                KeyEvent.KEYCODE_4 -> {
                    browserViewModel.setLayoutMode(LayoutMode.GRID_2X2)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (isFinishing) {
            browserViewModel.shutdown()
            extensionManager.dismissPopup()
        }
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val dataUrl = intent.dataString
            if (!dataUrl.isNullOrBlank()) {
                browserViewModel.openNewTab(url = dataUrl)
            }
        }
    }

    private fun installFromPickedDocumentUri(uri: Uri) {
        runCatching {
            val xpiCacheDir = File(cacheDir, "xpi_imports").apply { mkdirs() }
            val targetFile = File(xpiCacheDir, "import-${System.currentTimeMillis()}.xpi")
            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open selected .xpi file")
            val fileUri = Uri.fromFile(targetFile).toString()
            extensionsViewModel.installFromUri(fileUri)
        }.onFailure { err ->
            extensionsViewModel.installFromUri("") // surfaces validation error cleanly
            android.util.Log.e("MainActivity", "Failed to import local XPI", err)
        }
    }
}
