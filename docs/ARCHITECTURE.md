# RemBrowse Architecture Guide

## 1. High-Level Architecture (MVVM + Engine Abstraction)

RemBrowse separates **UI rendering (Jetpack Compose)**, **state and business logic (plain JVM view models)** and **platform/native engine integration (GeckoView, SharedPreferences, OkHttp)**, so the logic and the UI can be tested headlessly on the JVM without native `.so` binaries.

```mermaid
flowchart TD
    UI["Compose UI (RemBrowseScreen: TabRail, BrowserTopBar, TiledBrowserGrid, SettingsPage, DownloadsPanel, TabSwitcher, CommandPalette, dialogs)"]
    CHROME["BrowserChromeState (which overlays are open)"]
    HOLDER["BrowserSessionHolder (AndroidViewModel: owns everything below)"]
    BVM["BrowserViewModel (tabs, panes, split ratios, bookmarks, settings, page prompts)"]
    EVM["ExtensionsViewModel (AMO catalog, .xpi install, prompts, popups)"]
    DVM["DownloadsViewModel (asking, saving, the downloads list)"]
    DSTORE["DownloadStorage (MediaStore Downloads) & DownloadHistoryStore"]
    BE["BrowserEngine / BrowserSessionHandle (interfaces)"]
    EM["ExtensionManager (interface)"]
    REPO["BrowserStateRepository (SharedPreferences JSON)"]
    AMO["AmoApiClient (OkHttp -> addons.mozilla.org v5 API)"]
    GECKO["GeckoBrowserEngine & GeckoExtensionManager (Mozilla GeckoView 138)"]

    UI --> BVM
    UI --> EVM
    UI --> DVM
    UI --> CHROME
    HOLDER --> BVM
    HOLDER --> EVM
    HOLDER --> DVM
    BVM -- "files pages offer" --> DVM
    DVM --> DSTORE
    BVM --> BE
    BVM --> REPO
    EVM --> EM
    EVM --> AMO
    BE -.-> GECKO
    EM -.-> GECKO
```

### Lifetime

`BrowserSessionHolder` is an `AndroidViewModel` created by `MainActivity`. It owns the engine, the extension manager and the three view models, which gives live tabs the lifetime of the browser *window* rather than of one activity instance:

* If Android re-creates the activity, the same sessions are reused. Nothing is duplicated or leaked.
* When the window really goes away, `onCleared()` saves state and closes every session exactly once.
* `GeckoRuntime` itself is process-wide and lives in `RemBrowseApplication`.

`BrowserChromeState` is deliberately outside the composition so the activity's hardware-key handling (`KeyboardShortcuts`) can open the command palette or settings, or focus the address bar.

---

## 2. Tabs, panes and engine sessions

`BrowserUiState` holds the full tab list plus `paneTabIds`, the tab shown in each visible pane. `BrowserViewModel.syncSessions()` keeps the engine in step with it:

* **Lazy sessions**: a tab only gets an engine session once it is shown in a pane. Restoring twenty tabs therefore loads only the visible ones.
* **Visibility vs. focus**: `setVisible()` maps to `GeckoSession.setActive()`, so every visible pane keeps painting, while `setFocusedForExtensions()` tells WebExtensions which single tab is focused.
* **Start page**: a tab at `about:blank` is rendered natively by `NewTabPage`; no web view is composed for it until it navigates. Going back to the start of a tab's history returns to it.

### Internal pages (`rem://`)

RemBrowse's own pages are tabs whose address starts with `rem://`. Today that is the settings page: `rem://settings/<section>`, one address per `SettingsSection`. `TiledBrowserGrid` hands such a tab to the `internalPage` slot, where `RemBrowseScreen` draws `SettingsPage` (with `ExtensionsPanel` embedded as its Extensions section), instead of composing a web view.

The web engine is kept out of it entirely:

* **No engine session.** `syncSessions()` and `openNewTab()` skip internal tabs, and `showInternalPage()` closes the session of the start page it replaces. This is not only a saving: a `GeckoSession` reports `about:blank` when it opens, and that update would turn the settings tab back into the start page. For the same reason `onSessionUpdated` ignores anything that still arrives for a tab showing an internal page.
* **Never loaded by Gecko.** `navigatePane()` routes a typed `rem://` address to `openSettings()`. That reuses the settings tab if there is one, converts an untouched start page in place, and otherwise opens a new tab, so a website and its history are never replaced.
* **Not reachable from web content.** `onNewTabRequested` (from `window.open`, `target=_blank` or an extension's `tabs.create`) refuses `rem://` addresses, and no tab is created.
* **Back to the web.** Typing a web address on the settings tab creates a session for it at that point and marks it visible.

Because the section is the tab's URL, it is saved and restored with the rest of the tab state.

### Rendering several GeckoViews

`TiledBrowserGrid` lays every pane out in one flat box with explicit offsets and sizes computed from the layout tree (`PaneNode.geometry()`), and keys each pane by the tab it shows. Whatever happens to the layout (a split, a closed pane, a tab dragged to another pane, fullscreen), the `GeckoView` of a tab that stays on screen is moved and resized, never re-created. A pane that starts showing a different tab gets a new view, and the old one is detached. In `GeckoBrowserEngine`:

1. **TextureView backend** (`GeckoView.BACKEND_TEXTURE_VIEW`) so panes can be clipped to rounded corners and drawn under Compose overlays. GeckoView 138's `SurfaceViewWrapper` treats every frame a TextureView shows (`onSurfaceTextureUpdated`) as a new surface: a new `Surface`, then `GeckoSession.onSurfaceChanged` and a blocking `syncResumeResizeCompositor` that rebuilds the EGL surface and forces a frame, for every frame of a scroll. `PageSurface.kt` wraps GeckoView's texture listener (`OncePerTextureListener`) so only available, size-changed and destroyed reach it, which is the life a SurfaceView's surface has. The **Page rendering** setting (`PageRendering`) chooses Standard (that fix), Direct (GeckoView's default SurfaceView: no rounded-corner clipping, so the corners are painted over the page; a pane under a fullscreen page drops its view, since surfaces are ordered behind the window) or Legacy (the texture untouched). Views are keyed on the setting, so open panes switch at once; extension popups stay textures.
2. **Single-owner attachment**: `attachSessionToView()` tracks which view holds each session and releases the previous owner first, because a session may only be attached to one view.

### Page width in a pane

A tab that asks for desktop sites uses `VIEWPORT_MODE_DESKTOP`, which sets `forceDesktopViewport` on its browsing context. Gecko then lays the page out at `max(width of the view, browser.viewport.desktopWidth)` CSS px and scales it to fit the view (`Document::GetViewportInfo`). With Gecko's default of 980, a pane narrower than 980 px shows every page as a 980 px layout shrunk to fit, so its text is small.

GeckoView 138 has no setter for that preference, so `EngineConfig` passes it in the startup configuration file GeckoView reads (`GeckoRuntimeSettings.Builder.configFilePath`). The file's `prefs` are applied as default preference values through `MOZ_DEFAULT_PREFS` before the engine starts, in release builds too.

* On screens whose smallest side is at least 600dp the preference is set to 320, so the viewport follows the pane's real width and pages reflow at their natural size, as in a desktop browser with a narrow window. Panes wider than 980 px already used their own width.
* Phones keep 980: there, a desktop site is expected to be fitted to the screen.
* On every device the same file also sets `browser.translations.enable` to false. RemBrowse has no page translation, but the machinery GeckoView turns on runs a language-detection worker (`translations/cld-worker.js`) in every web content process, about 17 MB each as measured with `about:memory`.
* The file is written to the app's files directory at each start and read back with GeckoView's own parser (`DebugConfig`) before its path is handed over. If writing or parsing fails in any way, the engine starts without it. `proguard-rules.pro` keeps SnakeYAML whole so the parser cannot be shrunk away.

### Split view

The layout is a tree (`model/PaneLayout.kt`): a `PaneNode` is either a `Leaf(tabId)` or a `Split(ROW | COLUMN, ratio, first, second)`, with at most four leaves. Pane indices are the leaves in reading order. `BrowserUiState.layout` is the source of truth; `paneTabIds` and `paneCount` are derived from it, and `layoutMode` is the preset the tree matches, or null for a custom shape. Presets (`LayoutMode`, the Split menu, `Ctrl+1`–`5`) build trees; `SPLIT_1_2` is `Split(ROW, Leaf, Split(COLUMN, Leaf, Leaf))`. Each split's ratio is clamped to 0.2–0.8, and `PaneNode.sanitized()` drops closed tabs, duplicates and anything past four panes, so a restored or edited tree is always valid.

**Dividers.** One divider per split, keyed by its path and orientation, so a split that turns gets a fresh gesture for the other axis. `detectDividerGestures` (in `TiledBrowserGrid.kt`) measures the pointer's travel from the press in window coordinates and sets the ratio from where the drag began (`setSplitRatio`, clamped), rather than adding up step deltas. That keeps the divider under the pointer, and after a limit it moves again only when the pointer returns. A mouse starts dragging after Compose's mouse slop, a finger after the touch slop; a press released within the touch slop is a click, and two within the double-tap timeout reset the split. Only the primary button drags. The grid's root box carries a `pointerHoverIcon` that pins the resize cursor while a divider is held. It is also the stationary node on the pointer's hit path: Compose delivers a move only if some node on the path sees its position change in its own coordinates, and a divider that keeps up with the pointer sees none, so without it moves of equal steps would be dropped. Android asks the innermost view for the cursor first, and Compose sets it only on the view hosting the whole screen, so `PaneGeckoView` returns no cursor while `ChromePointerIconClaim` is held (the pointer is over a divider's grab area, which overlaps the pages, or a divider is being dragged).

**Drag and drop.** `Modifier.tabDragSource` (in `TabDrag.kt`) watches a rail tile's pointer on the Initial pass without consuming anything until a drag starts: with a mouse after the touch slop, with a finger only after a long press, so taps, the tile's menu and scrolling the rail still work. `TabDragState` holds the dragged tab and pointer position. The grid sets its `hitTest`, which maps a position to a `PaneDropTarget`: the outer quarter of a pane on each side is an edge zone, the rest is the centre. `BrowserViewModel.dropTabOnPane()` splits the pane on that side, or shows the tab in it; a tab already on screen is moved, and the leaf it left is removed. When four panes are on screen only the centre is offered, unless the dragged tab is one of them.

**Persistence.** The tree is saved as JSON next to the legacy `layoutMode`, `paneTabIds` and ratios, which are still written (`SPLIT_1_2` and any custom three- or four-pane layout as `GRID_2X2`, which older versions know) so an older version can read the state. A state without a tree, or with an unreadable one, is rebuilt from the legacy fields.

**Fullscreen.** `ContentDelegate.onFullScreen` reaches `BrowserViewModel` as `onFullScreenChanged`, which sets `fullscreenTabId` for a tab that is on screen (a hidden tab asking is told to exit). It is cleared when the page leaves fullscreen, the tab closes or crashes, or its pane stops showing it; `exitFullscreen()` (Back) calls `GeckoSession.exitFullScreen()`. While it is set, the screen drops the rail, the toolbar and the safe-drawing padding, and the grid draws that pane over the whole area. The other panes stay composed underneath, without semantics, focus or pointer input, and their sessions are made inactive until fullscreen ends; the layout itself is never changed. `MainActivity` hides the system bars (transient on swipe) and, on Android 15 and newer in a freeform window, calls `requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER)` so the window covers the screen, giving it back on exit. Both go through `FullscreenWindowEffect`, which fixes the value per effect so every enter is matched by one exit; reading the `by` state in `onDispose` instead saw it already false, and 0.13 never restored the window.

`splitCurrentTab()` puts the focused tab into a two-pane layout on the requested side and keeps focus on it; `swapFocusedPane()`, `setSplitPreset()` and `closePane()` back the controls in the Split menu. That menu (`SplitMenu` in `BrowserTopBar`) is the only place the split's controls live: with one page on screen it lists the ways to split, and while a split is open it lists swap, the size presets (two panes only), the layouts and Close split. No row is drawn above the panes, and panes have no toolbars of their own: the single address bar reads `activeTab`, so it always shows and acts on the focused pane, which is marked by its outline and in the tab rail.

### New windows

When a page calls `window.open` (or an extension calls `tabs.create`), GeckoView expects an *unopened* session back and opens it itself. `GeckoBrowserEngine.adoptNewTab()` asks the view model for a tab, and the session created during that call is flagged `awaitingGeckoOpen`: it is neither opened nor loaded by us, only returned. This keeps `window.opener` and POST data intact. If Gecko never opens it, a timeout opens it directly.

### Pages nobody can see

`syncSessions()` makes a session active (`setVisible`, which is `GeckoSession.setActive`) only while its tab is in a pane, the window is shown, and no other page is fullscreen. Background tabs are inactive anyway; this also pauses the panes under a fullscreen page and, through `MainActivity.onStop`/`onStart` (`setWindowShown`), every pane while the window is stopped. Gecko pauses a page by itself only when its view's surface is destroyed, and a TextureView's surface outlives a stopped window. An inactive page stops painting and its timers are throttled; with `suspendMediaWhenInactive(false)` its sound keeps playing.

### Crashes and low memory

`ContentDelegate.onCrash`/`onKill` close a session. The handle keeps the last `SessionState` and restores it immediately for a visible tab, or lazily when a background tab is shown again. A second crash within ten seconds is treated as a crash loop: the tab is flagged `crashed` and the UI offers a manual reload.

### Keyboard focus

Focus is always handed somewhere deliberate, never just cleared. Clearing it lets Android give focus to the first focusable control in the window, and the release of the key that was being pressed (Enter, typically) then activates that control. `BrowserChromeState.focusContent()` is called when a text field is done: Enter or Esc in the address bar, or the tab switcher closing. The focused pane takes Compose focus first, so the text field lets go, and a web page's `GeckoView` is then focused inside it. `MainActivity.dispatchKeyEvent` additionally swallows the key-up of an Enter that submitted a text field, so the page never receives a stray key release.

### Tracking protection

Standard protection (`AntiTracking.DEFAULT`) is always on. The optional enhanced level adds the cryptomining, fingerprinting and email-tracker lists. Neither uses `AntiTracking.CONTENT`/`STRICT` or `EtpLevel.STRICT`: that list contains general-purpose hosts (`cloudflare.com`, `gstatic.com`, `google.com`, `cdn.jsdelivr.net`), and blocking it breaks Cloudflare's bot check, reCAPTCHA and much of the web.

### Page prompts and load errors

* `GeckoPagePrompts` turns Gecko's prompt requests (`alert`, `confirm`, `prompt`, HTTP authentication, `<select>`, file inputs) into `PagePrompt` models. The view model queues them and the UI shows the oldest one that belongs to a visible tab.
* **File inputs** (`onFilePrompt`) become `PagePrompt.FilePicker`. Instead of a dialog, `RemBrowseScreen` calls `onPickFiles` once per request and `MainActivity` opens the system picker (`OpenDocument` or `OpenMultipleDocuments`, with the page's MIME types); the request id is kept in `BrowserSessionHolder` so a re-created activity neither opens it twice nor loses the answer. GeckoView resolves a picked document to a shared-storage path, which the app cannot open under scoped storage, so `UploadCache` copies each file under its own name into `cacheDir/uploads/<uuid>/` off the main thread and the prompt is confirmed with those `file://` copies. Copies are deleted when the engine starts. Folder inputs (`webkitdirectory`) are dismissed.
* `NavigationDelegate.onLoadError` returns a small self-contained page built by `ErrorPages`, with everything interpolated into it HTML-escaped.

### Tab settings

`BrowserSettings` carries three choices the view model applies itself:

* `restoreTabsOnStartup`: when off, `restoreInitialState()` ignores the saved tabs and layout and starts on one start page. Bookmarks and settings are still loaded. "Startup" means a new `BrowserViewModel`, which is a new process or window, not an activity re-creation.
* `openNewTabsNextToCurrent`: `openNewTab()` inserts after the tab it is opened from. For a window a page asked for, that is the opener, even if the opener is not on screen.
* `desktopSiteByDefault`: the default for `createTabModel()`. Changing it also updates tabs that hold no website yet, so the next address typed into them uses it. A tab opened by a page inherits its opener's mode instead.

---

## 3. Downloads

A response Gecko cannot display arrives in `ContentDelegate.onExternalResponse`. `downloadRequestFrom()` turns it into a `DownloadRequest` (file name, type, size and the body stream), the session listener passes it to `BrowserViewModel.downloadHandler`, and `BrowserSessionHolder` points that at `DownloadsViewModel`.

* **Nothing is trusted.** `DownloadFileNames` takes the name from `Content-Disposition` (RFC 6266, `filename*` first) or the last path segment, then reduces it to a plain file name: no directories, no reserved characters, no leading dot, bounded length. Header names are matched in any letter case, and a missing or nonsensical size becomes "unknown".
* **Asking costs nothing.** GeckoView keeps the response's network channel suspended until the first `read()`, so the request can wait in `pendingRequests` while `DownloadPromptDialog` is on screen. Declining just closes the stream. At most five unanswered requests are held; further ones are declined. Gecko's `skipConfirmation` (a PDF viewer's save button) and the **Ask before downloading** setting bypass the question.
* **Copying** runs on `Dispatchers.IO` as plain blocking code: `DownloadStorage.create()`, read to the end, `commit()`. Progress is published in 256 KB steps rather than per chunk. Any exception, or a cancel flag set from the UI, ends in `discard()`, so a partial file never stays behind. Cancelling also closes the stream from another IO coroutine to wake a `read()` that is waiting on the network.
* **Storage** is `AndroidDownloadStorage`. On Android 10+ it inserts into `MediaStore.Downloads` with `IS_PENDING` set until the last byte is written, which needs no storage permission and lets MediaStore number a taken name. The MIME type passed is the one that belongs to the file name's own extension, because MediaStore appends an extension when the two disagree. Before Android 10 there is no permission-free shared folder, so files go to the app's external `Download` directory and are opened through a `FileProvider`.
* **The list** is `DownloadsUiState.items`, newest first and capped at 50. Finished entries are saved by `SharedPrefsDownloadHistoryStore`; an entry saved as running is loaded as failed, since a download cannot outlive the process. A `data:` address is stored as just its scheme.
* **Opening** a file is an `ACTION_VIEW` intent with a read grant, sent by `MainActivity` after the view model has checked, off the main thread, that the file still exists.
* **Leftover tabs.** A `target=_blank` link to a file opens a tab in which nothing ever loads. The view model records which tabs the engine has shown a document in (a location reported outside a page start, other than a new session's `about:blank`), and `closeTabLeftBehindByDownload()` closes a page-opened tab that is not among them. It does so once the request is over (saved, failed or declined), not before: whether the response body survives its window closing is not something the code relies on.

Downloads live in the browser process; there is no foreground service, so a download ends if the process does.

---

## 4. History and app updates

### Browsing history

`BrowserViewModel.recordHistory()` runs at the end of every session update. A new location that is a web address (`UrlResolver.isWebUrl`) puts a `HistoryEntry` at the top, replacing any older entry for the same address, capped at 1,000; a title that arrives afterwards fills in the entry for the tab's address. Internal pages, the start page and extension pages are never web addresses, so they are never recorded. The list is a `StateFlow` of its own (`history`), not part of `BrowserUiState`, so a visit does not recompose the whole screen. `SharedPrefsBrowsingHistoryStore` keeps it in its own preferences file and writes on a background thread, coalescing bursts of visits into one write.

### App updates

`./gradlew publishApk` writes `build/update.json` beside the APKs: `versionName`, `versionCode`, and for each processor type the APK's file name, size and SHA-256. `UpdatesViewModel` reads it from `BuildConfig.UPDATE_BASE_URL`, the repository's raw `build/` folder on GitHub, so it only works while the repository is public.

* **Checking.** `OkHttpUpdateSource.fetchManifest()`; `UpdateManifests.updateFor()` offers the newest version only if its `versionCode` is higher than the running one, in the APK for the first of `Build.SUPPORTED_ABIS` that was published. File names containing `/`, sizes below 1 and malformed hashes are dropped when the file is parsed. An automatic check runs at launch when allowed and 24 hours have passed (or the clock went back); it fails silently and raises `notice` only for a version whose notice was not closed before.
* **Downloading** streams to `cache/updates/<name>.part` on `Dispatchers.IO`, hashing as it goes. More bytes than published, fewer, or a different SHA-256 throws `UpdateIntegrityException` and deletes the partial file; only a matching file is renamed into place. Progress is published in steps of 1 %. `cache/updates/` is emptied at every launch and before every download.
* **Installing.** `AndroidUpdateInstaller` reads the APK with `getPackageArchiveInfo` and requires this package name, a higher version code, and the installed copy's current signing certificate among the update's (current or rotated-from) certificates. Android enforces the signer itself; this check exists to explain a mismatch instead of the installer's bare "App not installed". Without `canRequestPackageInstalls()` it opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` and `MainActivity.onResume` resumes the install once the permission is granted. The APK reaches the system installer as an `ACTION_VIEW` intent with a read grant on a `FileProvider` URI (the `updates` cache path in `download_paths.xml`). Nothing is installed without the system installer's confirmation.

---

## 5. Native WebExtensions (`.xpi`) Pipeline

* **Discovery (`OkHttpAmoApiClient` + `AmoJsonParser`)**: queries `https://addons.mozilla.org/api/v5/addons/search/?app=android&type=extension&lang=en-US`. The parser handles AMO's locale maps (including the `null` + `_default` shape), skips entries without an id, duplicates and non-HTTPS downloads.
* **Installation (`GeckoExtensionManager`)**: accepts `https://` `.xpi` URLs or a `file://` copy of a picked document (copied off the main thread into `cacheDir/xpi_imports/` and deleted afterwards). Install errors are mapped to plain-language messages; a cancelled prompt is not an error.
* **Permission prompts**: install, update-with-new-permissions and optional-permission requests all go through one queue and the same review dialog. Nothing is approved silently.
* **Toolbar actions**: the default action (reported with a null session) and per-tab overrides are stored separately and merged for the focused tab, so one tab's badge never shows on another. The merged action's icon (or the extension's own icon when the action has none) is rendered with `Image.getBitmap()` and carried on `InstalledExtension.icon`. GeckoView builds a new `Image` for every action update, so renders are coalesced (one per extension at a time, changes wait 300 ms) and a bitmap that looks the same as the last one is not published, together with the badge and the badge colours the extension chose. `TabRail` shows these buttons at its foot, as many as fit below room for two tabs and the new-tab button (at most four, and at least one, the rest behind an overflow button); compact windows list them in the More menu.
* **Popups**: `onOpenPopup`/`onTogglePopup` return a fresh `GeckoSession` that `ExtensionPopupDialog` shows in a `GeckoView`, sized to `ExtensionPopupState.contentWidth`/`contentHeight` when the popup helper has reported them (clamped to 200–800 × 160–600 dp, desktop Firefox's 800 × 600 being the upper limit) and 420 × 540 otherwise; it stays transparent until the first report or 700 ms; on windows with the rail the dialog's window is moved to the bottom corner on the rail's side, beside the extension buttons, and the page is not dimmed. The placement is set in both directions, because the activity handles resizing itself and an open popup can outlive the switch to a phone-sized layout.
* **Popup viewport helper**: popups written for desktop Firefox rarely carry `<meta name="viewport">`, and GeckoView then lays them out 980 CSS px wide and zooms them out to fit the popup (`Document::GetViewportInfo`). GeckoView has no per-session setting for this. RemBrowse ships a built-in extension (`app/src/main/assets/extensions/popup_viewport/`, installed with `WebExtensionController.ensureBuiltIn()` and hidden from the extension list) whose `addon_child` experiment API runs in the extension process. It watches `document-element-inserted` and, for top-level `moz-extension:` documents whose frame data says `viewType: "popup"`, inserts `width=device-width` (without an initial scale, so content wider than the popup is still scaled down to fit rather than scrolled sideways). A popup's own viewport tag is parsed later and takes precedence, and pages of extensions shown in tabs are not touched. The same script measures each popup's preferred size and reports it as the experiment event `popupViewport.onPopupSize`; the helper's background page passes it to the app with `runtime.sendNativeMessage("rembrowse", …)` (`nativeMessaging` and `geckoViewAddons` permissions), and `GeckoExtensionManager` receives it through `WebExtension.setMessageDelegate`, parses it (`PopupContentSize`) and puts it on the open popup's state. Desktop Firefox measures with `nsIDocumentViewer.getContentSize`, but that call resizes the document viewer itself, which the embedding panel corrects straight after on desktop and nothing would correct here, so the size comes from the page's layout instead: the content's full width when it is wider than the popup, otherwise the body's own outer width (which, for a page that fills whatever it is given, is the popup's width), and the body's bottom edge for the height. It is measured 100 ms after the page settles (load, resize, `ResizeObserver`, `MutationObserver`) and reported only when it changes by 2 px or more. Popups with their own viewport tag were made for Android's full-window popup: they report their current size, which keeps it. If the helper cannot be installed, popups open as before. On large screens `EngineConfig`'s lower `browser.viewport.desktopWidth` (see above) already makes that fallback layout follow the popup's width; the helper also covers phones, which keep 980, and does not depend on the configuration file being applied. `BuiltInExtensionAssetsTest` checks the package's id, files and API wiring.
* **Proxy settings on Android**: Firefox's `proxy.settings` code (`toolkit/components/extensions/parent/ext-proxy.js`) throws "proxy.settings is not supported on android." (Mozilla bug 1725981), which stops VPN extensions such as Proton VPN, since they claim the proxy setting before routing requests through `proxy.onRequest` (supported on Android). The `PatchGeckoOmniJa` build step in `app/build.gradle.kts` transforms the merged assets (`SingleArtifact.ASSETS`) and rewrites that script inside GeckoView's `omni.ja` (`PATCHES`): its two Android checks become `false`; `get()`'s read of `network.proxy.share_proxy_settings`, a preference only desktop Firefox defines (the read throws on Android), falls back to its desktop default `false`; and `set()`'s `Services.policies.isAllowed(...)` is guarded with `Services.policies &&`, as Gecko does elsewhere, since Android builds have no enterprise-policy service. Run in a simulated Android environment (platform `android`, no policy service, GeckoView's own default preferences), the patched script accepts Proton VPN's request and reports `controlled_by_this_extension`; with only the two Android checks removed, `set()` and `get()` both still throw. Every other entry is copied unchanged, in order, with the same compression method and no data descriptors. The step fails the build if either check is not found exactly once, and re-reads the archive to verify that only that script changed. Gecko discards its startup cache when the app's install folder changes, which every update does, so the patched script is the one that runs.
* **Private browsing permission**: `proxy.settings.set` also requires `privateBrowsingAllowed`. RemBrowse has no private mode (`usePrivateMode(false)`), so `GeckoExtensionManager` grants it on install (`PermissionPromptResponse(allow, allow)`) and, once per run, to installed extensions that lack it (`setAllowedInPrivateBrowsing`, which reloads the extension; tabs the extension opens during that restart are suppressed). If private browsing is ever added, this must become the user's choice.
* **Tabs from extensions**: `TabDelegate.onNewTab` adopts a new browser tab; `onOpenOptionsPage` opens the options page in a tab.
* **Default ad blocker**: on first launch `ExtensionsViewModel` installs AdGuard AdBlocker (`DefaultAdBlocker`) through `ExtensionManager.installTrusted()`. The permission review is skipped only for a package whose id matches the expected one, and the extension's welcome tab is suppressed for the first 30 seconds. `DefaultExtensionStore` records that this happened, so a blocker the user removes is not reinstalled; a failed first attempt is silent and retried on the next launch. It is installed from addons.mozilla.org rather than bundled, which keeps the APK small, always gives the current signed build and avoids redistributing its GPL-licensed code.

---

## 6. Design tokens

All colours, type and shapes come from `ui/theme/Theme.kt`; components read `MaterialTheme` roles and never hardcode values.

| Role | Used for |
| :--- | :--- |
| `background` | Window chrome: tab rail and toolbar (`#14161B` in the dark theme) |
| `surface` | Raised elements on the chrome: address pill, menus, dialogs, cards |
| `surfaceContainerLowest` | The page container behind web content and the start page |
| `surfaceContainerHigh` / `Highest` | Hover and selected fills |
| `primary` | Accent for text, icons, focus rings, progress and selection outlines |
| `primaryContainer` | Deep accent fill for selected and active items |
| `tertiaryContainer` | Soft accent tint for the start page backdrop |
| `outline` / `outlineVariant` | Control boundaries / decorative hairlines |

`AccentColor` offers six accents; each defines tones for both themes. `ThemeContrastTest` asserts WCAG contrast (4.5:1 text, 3:1 non-text) for every theme and accent combination.

Sizes shared by the chrome are in `RemDimens`. Interactive elements have a 48dp touch target even where the visual is 40dp, and windows narrower than 600dp switch to the compact layout.

---

## 7. State Persistence & Testing Strategy

* **Persistence**: `SharedPrefsBrowserStateRepository` stores `PersistedBrowserState` (the layout tree and the legacy layout fields, clamped split ratios, tabs, pane assignments, bookmarks and settings, including the chosen search engine) as JSON. Data written by older versions loads with defaults for missing fields. Split ratios are saved once per drag, not on every pointer move.
* **Unit tests (plain JVM)**: `UrlResolver`, `KeyboardShortcuts`, `ErrorPages`, `DownloadFileNames`, download request parsing, the engine configuration file (read back with GeckoView's real parser), `AmoJsonParser`, command palette results, theme contrast, and the three view models against the fakes in `BrowserFakes.kt` (including a fake download storage and a response body that can fail or be cancelled part-way).
* **Robolectric**: real `SharedPreferences` round trips for browser state, the downloads list and the browsing history.
* **Updates**: `AppUpdatesTest` runs `OkHttpUpdateSource` against canned HTTP responses (a damaged, truncated or oversized APK is deleted); `UpdatesViewModelTest` covers the daily check, closed notices, progress, cancel, retries and the install permission round trip with a fake source and installer.
* **Headless Compose UI tests**: `RemBrowseUiTest` (laptop-sized window) and `CompactWindowUiTest` (phone-sized) drive the real screen with a fake engine, including the extension buttons on the rail and in the More menu.
* **UI renders**: `UiSnapshotTest` writes PNGs of the main screens on request (see the Build & Test Guide).

Behaviour that depends on the real engine or the device (new-window adoption, crash recovery, prompts, extension delegates and popups, the popup viewport helper, the download callback, MediaStore and opening files, and the system installer) is written against the GeckoView 138 sources and compiled against the real library, but is not exercised by automated tests: it needs a device.
