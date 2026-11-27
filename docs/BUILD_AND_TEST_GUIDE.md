# RemBrowse Build & Test Guide

This guide covers building, testing, and signing **RemBrowse** (`com.rembrowser.rembrowse`) on Android Laptop / Googlebook OS (Debian Linux VM) or any Linux/macOS/Windows workstation.

---

## 1. Prerequisites

On Ubuntu 22.04 or later, one script sets up everything below (a JDK, the Android SDK's command-line tools, platform 37.2, build tools 36.0.0 and platform tools, and `ANDROID_HOME` in `~/.bashrc`). It is safe to run again:

```bash
bash scripts/setup-build-server.sh
```

Or by hand:

* **JDK 21** (OpenJDK 21; project compiles bytecode targeting Java 17):
  ```bash
  sudo apt-get update && sudo apt-get install -y openjdk-21-jdk-headless unzip
  ```
  A Java runtime without the compiler (`javac`) isn't enough: Gradle stops with "does not provide the required capabilities: [JAVA_COMPILER]". Without sudo, unpack a JDK 21 such as [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21) in your home folder and set `JAVA_HOME` to it, then run the script with `SKIP_APT=1`. The build server does this: its JDK is at `~/android-sdk/jdk-21`, set in `~/.bashrc`.
* **Android CLI** (`android`) & Android SDK 37.2 (API level 37's second minor release; GeckoView 161 needs it to compile against, set in `app/build.gradle.kts` as `release(37) { minorApiLevel = 2 }`):
  ```bash
  curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
  android sdk install platforms/android-37.2 build-tools/36.0.0 platform-tools
  ```
* **The build tools themselves** are fetched by Gradle; nothing to install: Gradle 9.9.2 (the wrapper) and Android Gradle Plugin 9.5.3, which compiles the Kotlin code itself with Kotlin 2.4.21 (there is no separate Kotlin Android plugin; the version is pinned in the root `build.gradle.kts`). Both need a JDK 17 or newer to run. Gradle 9 writes its problems report (compiler warnings) to `build/reports/problems/` at the repository root; it is scratch output, not a release file, and `.gitignore` leaves it out.
* Ensure `local.properties` points to your Android SDK directory:
  ```properties
  sdk.dir=/home/droid/Android/Sdk
  ```

---

## 2. Shared Storage (`virtiofs`) Cache Optimization

When working inside `/storage/emulated/10/...` on Android Laptop / Googlebook OS, always redirect Gradle's project cache to the VM's native `ext4` disk (`$HOME/.cache/projects/...`) to avoid FUSE lock contention and slow metadata I/O:

```bash
PROJECT_CACHE_DIR="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

> **Memory Tip (4 GB VM)**: Run `testDebugUnitTest` and `assembleDebug` as separate commands with `--no-daemon` so Robolectric's test worker and Gradle's packaging task do not compete for RAM simultaneously.

---

## 3. Running Automated Tests

Run all **688 tests** (JVM unit tests, Robolectric persistence and headless Jetpack Compose UI tests):

```bash
./gradlew testDebugUnitTest --no-daemon \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

Run a single test class:

```bash
./gradlew testDebugUnitTest --no-daemon \
  --tests "com.rembrowser.rembrowse.viewmodel.BrowserViewModelTest" \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

Test suites included:
* `MouseAffordancesUiTest` — the middle button on rail tabs, pinned sites, tab switcher cards, bookmarks and History; right-click menus on the cards, bookmarks and History rows; a finger's long press opening the same menus while a held mouse click and a quick tap stay a click; plain clicks unchanged.
* `NoticeLayoutUiTest` — a notice is as wide as its text on a wide window, stops at 520dp with its buttons kept, and is one polite live region with its buttons.
* `TouchHapticsUiTest` — haptics for a finger: Settings switches and Match case (on and off), a switcher card's long press, a tab picked up and dropped, a divider double-tapped; nothing for the same with a mouse, a screen reader or a touchpad click that arrives as a finger with a button down (which also doesn't open a card's long-press menu); a click held on a rail tab opens its menu with the long-press haptic.
* `SplitDividerMouseTest` / `PaneGeckoViewTest` — dragging a divider with a mouse (every step followed, side by side, stacked and nested, past a limit and back, one save per drag, a split that turns), double-click with and without a slip, the other mouse buttons, the resize cursor over the whole grip and during a drag, finger drag and double-tap; and a pane's web view giving up its cursor while the chrome has the pointer.
* `OneAndTwoLayoutUiTest` — the One left, two right layout from the Split menu.
* `TouchpadInputFilterTest` — touchpad input for a page's web view: a touchpad right click (a finger pointer from a mouse source) passed on as a mouse right click with its button and position, a touchpad click-and-drag kept a mouse drag, fingers resting mid-scroll or a small nudge never reaching the page, a scroll passed on whole (press, then the move past the slop) once it moves, and touchscreen input and real mouse clicks left alone.
* `AddressBarSelectionTest` / `AddressFieldTest` — the address bar with a mouse and with a touchpad (through the same filter as the window), on real text measurement: a click into the bar selects the address and a second click places the caret, double-click selects the word between punctuation (also straight away on the unfocused bar), drags select what they cover (also from the unfocused bar), triple-click selects all, focus from the keyboard selects all, and the right-click menu cuts, copies, pastes (on one line), pastes and goes, selects all and greys out what it cannot do; plus the word, paste and click-counting rules on their own.
* `TouchpadWindowsTest` — a touchpad click in a dialog reaches it as a mouse click.
* `PageContextMenuTest` / `PageContextMenuUiTest` — the right-click menu: the rows for a link, a picture or video, a linked picture and the page itself, what is not opened or copied (`javascript:`, `mailto:` links are only copied, `blob:` media), Back greyed out with nowhere to go, the copy texts; and on the real screen, opening it for a link, a video and the page, copying to the clipboard and reloading from it.
* `PageSurfaceTest` — with a real GeckoView, a texture-backed page keeps one compositor surface across frames (stock GeckoView swaps it on every frame) except for the one frame after GeckoView asks for a new surface, and each Page rendering choice sets the view up as described; plus the setting's storage and its radio group in Settings.
* `FindInPageTest` / `FindInPageUiTest` — find in page: only on web pages, searching as you type, answers for older text dropped, next, previous and match case, clearing, closing with Esc or ×, closing when another tab takes focus, the count after a new page, and how the count reads.
* `UnseenPanesTest` — panes under a fullscreen page, and every page while the window is away, are paused and resume when seen again; a background tab stays in the background; a page in a call keeps running wherever it is.
* `FullscreenWindowEffectTest` — every time a page goes fullscreen, the window is restored when it leaves (or when the screen goes away), and the update status never ends in two full stops.
* `PaneLayoutTest` — the layout tree: presets, splitting a pane on each side, replacing and moving tabs, the four-pane limit, removing panes, ratios, geometry and drop zones, and cleaning up invalid trees.
* `UrlResolverTest` — address vs. search detection, display form, favicon location, extension link validation.
* `DownloadFileNamesTest` / `DownloadRequestsTest` — file names from `Content-Disposition` or the address, sanitising hostile names, numbering taken names, size formatting, reading download headers.
* `KeyboardShortcutsTest` — shortcut mapping, key-repeat suppression, modifier handling.
* `ErrorPagesTest` — error page content and HTML escaping of the failed address.
* `EngineConfigTest` — the engine's startup configuration file: which devices get it, its contents as read back by GeckoView's own parser, and starting without it when anything goes wrong.
* `AmoJsonParserTest` — AMO v5 response parsing, locale fallbacks, duplicate and insecure entry filtering.
* `BrowserViewModelTest` — layouts, lazy session creation, tab closing, new-window requests, split ratios, prompts, bookmarks, settings (startup, new-tab position, desktop sites), handing downloads over, the page menu (only for a page on screen, closed by a new page or leaving the screen, links opened in the background next to their page or beside it in a split, Back, Reload, bookmark and find from it), and the settings page's tab (no engine session, typed `rem://` addresses, refusing them from pages).
* `ExtensionsViewModelTest` — catalog loading and cancellation, install validation, cancel and failure handling.
* `DownloadsViewModelTest` — asking first, saving, declining, failure and cancel clean-up, name collisions, progress, the list and its history.
* `BrowsingHistoryTest` / `BrowsingHistoryStoreTest` — what is recorded (web pages only, one entry per address, titles filled in, the cap), the Keep history switch, remove and clear, and the saved list.
* `AppUpdatesTest` — reading `update.json` (and rejecting unsafe entries), choosing the APK for the device, offering nothing to a device older than the release's `minSdk`, fetching it, and deleting a download that is damaged, truncated or too large.
* `NotificationIconClientTest` — the client that downloads a site notification's icon, against servers on the test machine's loopback address: a name that leads to this device or the local network is refused before anything connects, an address written in the URL is refused before it is requested, and so is a redirect to either; and the two facts about OkHttp that guard rests on (it asks the client's name lookup for every name, and connects to an address in a URL without asking).
* `SiteOriginsSiteTest` — a site as storage access is granted to it (the scheme and registrable domain), with OkHttp's list of public suffixes really read: it needs Robolectric and `OkHttp.initialize`, and fails if the list is missing.
* `GeckoImageNetworkClientTest` / `IconImageLoaderTest` — site and add-on icons through the engine, against a fake engine: only plain GET requests, sent anonymously and with the engine following no redirect; redirects followed only to web addresses, and not too many; the 1 MB cap (from the Content-Length or while reading) and the 15-second time-out; the loader built and local pictures loaded without asking for the engine, the app's own loader never starting one, and a site without an icon asked once and then answered from the disk cache.
* `UpdatesViewModelTest` / `UpdatesUiTest` — the daily check and its notice, checking on request, download progress and cancel, retrying, the install permission round trip, refused updates, and the controls in Settings → About.
* `CommandPaletteResultsTest` — what the command palette offers for a typed query.
* `ThemeContrastTest` — WCAG contrast of every theme and accent combination.
* `WindowSizeClassTest` — Material 3's window size classes at their edges (600, 840 and 1200dp wide, 480dp high).
* `MotionTest` — `RemMotion` inside the theme against Material 3's expressive motion scheme, and every spec a snap with animations off; Android's Remove animations setting followed while shown, and the find bar sliding in, or in place at once with animations off.
* `BrowserStorageRobolectricTest` — real Android `SharedPreferences` round trip, including data from v0.01, and the saved downloads list.
* `PopupContentSizeTest` — reading the popup helper's size reports, and ignoring anything else.
* `UploadCacheTest` — copying files picked for an upload button: names kept, same names apart, unreadable files left out, old copies removed, the MIME types offered to the picker.
* `BuiltInExtensionAssetsTest` — the bundled popup viewport helper: its id, the files its manifest names, its API and event wiring, and the messaging permissions and channel name the app listens on; and the page menu helper: its id, a content script in every frame, `nativeMessagingFromContent`, its channel name and message, and that it waits for the page and GeckoView to have answered; its version raised past those shipped; and its copying of a picture a page writes with `navigator.clipboard.write`: only a PNG on the page's own clipboard, only with the user's activation (checked as the page calls, the copy started there and then), one at a time, anything else left to the engine, the page's promise made with the `Promise` taken before the page runs and given only the outcome, and the app taking the `copyImage` message's base64 (no longer than 20 MB) without decoding it on the main thread.
* `RailExtensionSlotsTest` / `ExtensionPopupDialogTest` — how many extension buttons fit on the rail for its height; where an extension popup appears in its window (beside the collapsed and the expanded rail, left to right and right to left; centred and dimmed on a phone-sized window and after the window becomes phone-sized), that a later size grows it from its corner and a tap outside it closes it, how big it is (default, sized to its content, within desktop Firefox's limits), and when it is shown (once its reported size has stood for a moment, at once when that is the size it has, after 700 ms at the latest).
* `GoToAddressUiTest` — Go to address (Ctrl+G): opens with the page's address (empty on the start page), Enter goes there in the same tab, Alt+Enter in a new one, suggestions as you type, Esc and Ctrl+G again close it.
* `ClipboardImagesTest` — Copy image's parts that need no device: picture types sniffed from their first bytes, `data:` addresses, which formats are kept, and the 25 MB limit; and a page's own copied picture read from base64 (PNG only, 20 MB at most).
* `PageImageCopierTest` — a page's own copied picture going on the clipboard as a `content:` address: decoded off the main thread and answered on it, one at a time (another refused until the first is on the clipboard), and every failure, running out of memory or a worker that can't start included, answered with false.
* `ReadablePngTest` — the check a page's own copied picture passes before it goes on the clipboard, with Android's real decoder: a PNG (a large one read at a reduced size) passes; JPEG, WebP, a PNG's header alone and other bytes don't.
* `ComposingAwareInputConnectionTest` — the page input connection that notices a word being composed, for sending Enter past the keyboard app.
* `ConnectFailureMessageTest` — what a failed SSH connection says: refused (and the port), unreachable, blocked by the device, unknown host, timed out, with a Tailscale hint for tailnet addresses.
* `TerminalConnectionTest` / `KeyExchangeWatchdogTest` — a shell that ends while connecting shows as disconnected; a program's copy (OSC 52) let through only within 10 seconds of the user's input in that terminal, by the real clock (time asleep counts) (typing, or a paste or mouse report the view marks, never the emulator's own answers), with any selection target and either terminator, and never once no view shows the terminal or after a reconnect; an empty copy and a `?` query ignored and never answered; a 60,000-character copy arriving whole, and one too long to keep dropped without printing its base64, the terminal carrying on, and one that never ends given up after about a scrollback's worth; a reconnect starting with nothing half received from the last session; the key-exchange time limit (armed, paused, restarted, stopped).
* `SshEndToEndTest` — the real SSH client (`TrileadSshConnector`) against an Apache MINA SSHD server started in the test: password sign-in opening a shell at the terminal's size that echoes what is typed, a new server confirmed once and a changed key flagged, a wrong password asked again and giving up, a generated key signing in once the server has its public half, an imported key with a passphrase, a closed port explained, and only AES ciphers offered (never ChaCha20, which fails on Conscrypt).
* `TerminalScreenViewTest` — the terminal view on the real emulator and a fake shell: Ctrl and Alt keys, the browser's keys kept out, its size in cells, selecting and copying with the mouse, double-click words, the right-click menu, scrolling the history and full-screen programs; command suggestions (shown only for what the screen echoes, Tab and → taking them, nothing at a password prompt or in a full-screen program, lines edited with keys it can't follow left alone, Backspace followed, Enter remembering a command even with its last keys still on their way); copy and paste (a program's copy after a key press, a click or the wheel sent to tmux, or a paste, and not after 10 seconds, with the setting off, empty, as a query or with no view showing the terminal; Ctrl+C copying and clearing a selection on screen without sending anything (its key repeats too), and interrupting without one or with one pushed out of view; Ctrl+Shift+C and Ctrl+Insert never reaching the program; Ctrl+V pasting, bracketed when asked, never sending ^V even with nothing to paste, and sending it when Settings say so; copying as the button is let go after a drag, a double or triple click or a Shift+drag over tmux, never after a click that wobbles within its cell, nor with the setting off or for Select all; a selection too long to copy refused with a message, and kept by Ctrl+C); and themes, the accent cursor and the cursor style.
* `TerminalTouchSelectionTest` — selecting with a finger or a stylus, with Android's real floating toolbar: a long-press selecting a word (and nothing, with Paste and Select all, on blank space), dragging on to take in more a word at a time, a drag without a long-press scrolling, the handles moving cell by cell, swapping as they cross and scrolling the history at the edges, the toolbar hiding while a finger moves and coming back, a tap, typing, the mouse, a new size or another terminal putting it away, Copy and Paste, touchpads and programs that asked for the mouse never getting it, and the handles' contrast on every theme, the handles and a long-pressed word keeping to their text as output scrolls, and a long-press still to come cancelled by another terminal (Back putting a touch selection away is in `BackNavigationUiTest`). `TerminalScreenViewTest` also covers the on-screen keyboard's own Select all, Copy and Paste.
* `CommandSuggestionsTest` — suggestions from the history first, then common commands; only longer commands that start with exactly what was typed; what is remembered (once, most recent first, capped) and what is not (blank, a leading space, control characters, pasted scripts).
* `TerminalViewModelTest` / `TerminalStoreTest` / `TerminalModelsTest` — saved and asked-for passwords, host keys, key sign-in, a deleted host, output, titles and typing, reconnecting, closing a tab; the look, the copy and paste settings and the command history saved and restored (and forgotten when remembering is turned off); terminal addresses and typed `ssh` commands.
* `TerminalUiTest` — the terminal on the real screen: a new terminal connecting to a typed address with a password, a host added in Settings connecting after its key is trusted, a generated key's public half, an unreachable server, the theme menu in the terminal's bar, and the look, suggestion and copy and paste settings.
* `RemBrowseUiTest` / `CompactWindowUiTest` — the real Compose screen on a laptop-sized and a phone-sized window, including extension buttons on the rail (also on short windows) and in the More menu.

### Rendering the UI without a device

`UiSnapshotTest` renders the main screens (start page, browsing, tab switcher, split view, command palette, each settings section, the extensions manager, the downloads list, the download question and notice, extension buttons and an open popup, light theme, compact layout) to PNG files. It is skipped unless you name a target directory:

```bash
./gradlew testDebugUnitTest --tests "*UiSnapshotTest*" -Prembrowse.screenshots=build/ui-screenshots
```

`TerminalSnapshotTest` does the same for the terminal (the host picker, a session in several themes with a suggestion showing, split view, the host key, password, disconnected and failed screens, the theme menu, Settings → Terminal, and a touch selection with its handles and toolbar in the default theme and in Paper), against a fake shell:

```bash
./gradlew testDebugUnitTest --tests "*TerminalSnapshotTest*" -Prembrowse.screenshots=build/terminal-shots
```

`NoticeSnapshotTest` renders the notices over the bottom of the window (an update, a download finished and failed, a screenshot that failed) in both themes and on a phone, and `ScreenCaptureSnapshotTest` the screenshot overlay and its notice; both take the same `-Prembrowse.screenshots=<dir>`.

The images land in `app/build/ui-screenshots/` (or `terminal-shots/`). Web content is a placeholder, because the real engine cannot run on the JVM, and dialogs are drawn at the top-left corner without the dimmed backdrop a device shows.

---

## 4. Building the APKs

Every build produces **one APK per processor type** (`arm64-v8a` and `x86_64`).

### Publishing the "Download latest" APKs

```bash
./gradlew publishApk --no-daemon \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

This builds the release variant and copies the result to the repository's `build/` folder under stable names, so the README links never need to change:

```text
build/RemBrowse-arm64-v8a.apk
build/RemBrowse-x86_64.apk
build/VERSION.txt
build/update.json
```

To publish a new version: first `bash scripts/check-updates.sh`, which lists every library, plugin, the Gradle wrapper and GeckoView that has a newer stable release (alpha, beta and release candidates are ignored; GeckoView is compared on the same artifact, so the same channel; uBlock Origin's current version on addons.mozilla.org is shown for information only, as the app installs the latest copy from there). It needs only `curl` and standard tools, prints one line per item and a summary, and exits 0 when everything is current, 1 when something is outdated and 2 when a lookup failed. Update what it reports, or decide to wait, before building. Then `./gradlew publishApk` (above), then `bash scripts/virustotal-scan.sh`, then `bash scripts/publish-to-site.sh` (see "The download site" below), then commit, push, and create the GitHub release. The scan uploads both APKs to VirusTotal (or reuses an existing analysis of the same SHA-256), waits up to about 20 minutes per file, and writes `build/virustotal.json` (read by the download page) and `build/virustotal.md` (the release-notes block). It exits non-zero if any engine reports malicious or suspicious, because APKs sometimes get false positives: open the report links, and rerun with `--allow-detections` only after looking. `--dry-run` writes fixture output without a key or network (the site refuses that file). **The API key** is read from the `VT_API_KEY` environment variable, else from the `VT_API_KEY=` line in the repo-root `.env` (gitignored, mode 600; read as text, not sourced), else from `~/.config/virustotal/api_key` (mode 600). It is passed to curl from a private temp file and never printed. The free API allows 4 requests a minute and 500 a day, so a scan takes a few minutes.

To publish a new version, then, run `bash scripts/publish-to-site.sh` (`--skip-virustotal` to publish without a scan), commit `VERSION.txt`, `update.json`, `virustotal.json` and `virustotal.md` to keep the record (the APKs are not committed since 0.14.0; `.gitignore` leaves them out), push, and attach all three release files to a GitHub release: `gh release create v$VERSION build/RemBrowse-arm64-v8a.apk build/RemBrowse-x86_64.apk build/update.json --title "RemBrowse $VERSION" --notes-file <(cat <the CHANGELOG entry> ; echo ; cat build/virustotal.md)` (the notes carry `build/virustotal.md`). `update.json` holds the version, the oldest Android the APKs install on (`minSdk`) and each APK's size and SHA-256; copies of RemBrowse already installed read it from the latest GitHub release for **Settings → About → Updates** and the daily check (`https://github.com/sakifmostafa/RemBrowse/releases/latest/download/update.json`, which GitHub redirects to the newest release's copy), and fetch the APKs from the same release, so a release must carry all three files and never be edited to swap one. The download page reads the site's own copy. A build that looks for updates elsewhere: `./gradlew publishApk -Prembrowse.updateUrl=https://example.com/rembrowse/`. Release APKs are about 104–109 MB: the native engine is stored compressed, GeckoView's `omni.ja` is stored uncompressed so the engine can read it in place, Gecko's crash-report tools (`libcrashtools.so`) are left out, and unused library code and resources are removed (shrink only; nothing is renamed or optimised, see `app/proguard-rules.pro`; `app/proguard-defaults.pro` is Android Gradle Plugin's standard keep rules, copied because AGP 9 only offers them with `-allowaccessmodification`, which would make private code public).

### The download site

`https://rembrowse.com/` is served from the build server:

* **The page** is `site/` (plain HTML, CSS and JavaScript; no build step, no third-party requests, fonts included). Its look is Apple-like: one typeface (Inter, included), large tight headlines, the live split-view window as the hero, a bento of highlights and pill buttons, with the logo's gold as the only accent. It follows the system's dark or light setting; every colour, size, gap and duration in `site/assets/site.css` is a token at the top of the file, and the window in the demo keeps the app's own dark colours in both. The split-view demo moves with transforms and fades only, stops on hover, focus, when scrolled away or with reduced motion, and has a pause button. Sections below the first screen rise into view as you scroll (never with reduced motion or without JavaScript). It reads `update.json` to show the version, each download's size and SHA-256, and which file suits the visitor's device, so it needs no edit for a new release. Without JavaScript, the buttons still link to the stable file names. The screenshots in `site/assets/img/` are renders from `UiSnapshotTest` (see "Rendering the UI without a device"); the terminal on the page is drawn in HTML. **For search engines** the page has a title and description with the terms people look for, Open Graph and Twitter tags, `WebSite` and `FAQPage` JSON-LD blocks (no version or file size in them, so no edit per release; no app markup, as the page is the site, not the app), a visible FAQ section whose answers must stay in step with the README and user guide, favicons of 48 px multiples (`favicon.ico`, `favicon-96.png`), `robots.txt` and `sitemap.xml`. `keys.html`, `lna.html` and `update.json` are kept out of the index (`noindex` tag, and an `X-Robots-Tag` header that `nginx.conf` sets from a map of those paths, so no location needs its own headers). `lna.html`, the local network test page, may also connect to any address typed into it: its Content Security Policy's `connect-src` comes from a second map, which keeps every other page to the site itself.
* **The server** is `deploy/site-host/`: nginx (unprivileged, read-only) serving `~/RemBrowse/www`, reachable only through its own Cloudflare Tunnel (`rembrowse-site`), which provides HTTPS. Start it with `cd deploy/site-host && docker compose up -d`; the tunnel token is in `deploy/site-host/.env` (private, gitignored). Port 8474 on 127.0.0.1 serves the same files for local checks. APKs are sent as `application/vnd.android.package-archive`. The page, its CSS and JavaScript, `update.json` and the APKs are sent with `Cache-Control: no-cache`, so browsers and Cloudflare check for a newer copy every time (fonts and images may be kept for a day). A Cloudflare cache rule for this host makes Cloudflare follow those headers; without it, the zone's 4-hour browser cache setting would replace them.
* **Publishing**: `bash scripts/publish-to-site.sh` after `./gradlew publishApk`. It refuses a release whose `update.json` doesn't match the APKs (size, SHA-256), whose APKs aren't `com.rembrowser.rembrowse` at that version and `minSdk`, or aren't signed with the RemBrowse key only, and one older than what the site has (`--allow-older` overrides that). Then it copies the page, each APK and `update.json` last, each under a temporary name renamed into place, and checks what nginx serves. It also requires `build/virustotal.json` to match the release (version and SHA-256s) and publishes it just before `update.json`; `--skip-virustotal` publishes without it (and removes an older one from the site). `--site-only` updates just the page; the page shows "Scanned by VirusTotal: N of M engines flagged it" under each download only when `virustotal.json` matches `update.json`.

### The engine patch

Every build runs `patch<Variant>GeckoOmniJa`, which rewrites one script inside GeckoView's `omni.ja` so extensions may use `proxy.settings` on Android (see the Architecture Guide). After a GeckoView update the build stops with "Expected `…` once in …ext-proxy.js" if Mozilla changed that script. Read the new `toolkit/components/extensions/parent/ext-proxy.js` for that version, update `PATCHES` in `app/build.gradle.kts`, and check that each change still only removes an Android-specific failure (the refusals, the desktop-only preference read, the missing policy service). To see the patched script in an APK:

```bash
unzip -p app/build/outputs/apk/debug/RemBrowse-*-x86_64-debug.apk assets/omni.ja > /tmp/omni.ja && unzip -p /tmp/omni.ja chrome/toolkit/content/extensions/parent/ext-proxy.js | grep -n RemBrowse
```

### Debug builds

```bash
./gradlew assembleDebug --no-daemon \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```

Output (about 200–225 MB each, uncompressed for fast incremental builds):
```text
app/build/outputs/apk/debug/RemBrowse-v0.08-arm64-v8a-debug.apk
app/build/outputs/apk/debug/RemBrowse-v0.08-x86_64-debug.apk
```

### Verifying an APK

```bash
APK="build/RemBrowse-x86_64.apk"
"$HOME/Android/Sdk/build-tools/36.0.0/aapt2" dump badging "$APK" | grep -E 'package:|targetSdkVersion:|application-label:|native-code:'
"$HOME/Android/Sdk/build-tools/36.0.0/apksigner" verify --verbose "$APK"
```

---

## 5. Signing & Versioning Rules

* **Persistent keystore**: releases are signed with `signing/rembrowse.jks` (alias `rembrowse`). Its passwords come from `signing/keystore.properties`:
  ```properties
  storePassword=...
  keyPassword=...
  ```
  or, when that file doesn't have them, from `REMBROWSE_STORE_PASSWORD` and `REMBROWSE_KEY_PASSWORD`. There is no default password in the build. Both files are gitignored: never commit, paste or upload them, and keep a copy of each off the build machine. The keystore is PKCS12, so the key password is always the same as the store password. `bash scripts/change-keystore-password.sh` changes it (it asks for the new one without showing it, or makes a random one with `--generate`) and writes `keystore.properties`. The key itself doesn't change, so later builds still update installed copies. Releases from 0.09 on are signed with the key whose certificate has the SHA-256 fingerprint `F1:A5:30:6A:47:E4:BE:78:AD:7F:B0:C1:41:88:71:E3:A6:C9:A8:35:13:49:E2:42:39:DB:20:B4:75:B2:BE:06`; check a build with `apksigner verify --print-certs`. Keeping this keystore stable lets installed builds upgrade in place.
* **Without the key or its passwords**, a release build (`assembleRelease`, `publishApk`) stops at once with an error saying what is missing. It never signs a release with another key, because such an APK can't update installed copies. Tests, lint and debug builds still work: debug builds are signed with that machine's debug key. Android won't install a debug build like that over a copy signed with a different key, so uninstall the old copy first. With the key set up, debug builds are signed with it too.
* **Version increments**: for each new release, update `versionName` and **always increment `versionCode`** (`15` → `16` …) in `app/build.gradle.kts` to avoid `INSTALL_FAILED_VERSION_DOWNGRADE`. A small fix or change is a patch version (`0.13` → `0.13.1` → `0.13.2`); a bigger update moves to the next minor version (`0.14`). The in-app update check compares `versionCode` only, so any of these is offered as long as the code went up. APK names and `build/VERSION.txt` follow `versionName` automatically; update the version shown in the README's download section.
* **Processor types**: `arm64-v8a` covers ARM phones, tablets and laptops; `x86_64` covers Intel/AMD Android laptops.
