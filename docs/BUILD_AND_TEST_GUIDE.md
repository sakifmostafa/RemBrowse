# RemBrowse Build & Test Guide

This guide covers building, testing, and signing **RemBrowse** (`com.rembrowser.rembrowse`) on Android Laptop / Googlebook OS (Debian Linux VM) or any Linux/macOS/Windows workstation.

---

## 1. Prerequisites

On Ubuntu 22.04 or later, one script sets up everything below (a JDK, the Android SDK's command-line tools, platform 35, build tools 35.0.0 and platform tools, and `ANDROID_HOME` in `~/.bashrc`). It is safe to run again:

```bash
bash scripts/setup-build-server.sh
```

Or by hand:

* **JDK 21** (OpenJDK 21; project compiles bytecode targeting Java 17):
  ```bash
  sudo apt-get update && sudo apt-get install -y openjdk-21-jdk-headless unzip
  ```
  A Java runtime without the compiler (`javac`) isn't enough: Gradle stops with "does not provide the required capabilities: [JAVA_COMPILER]". Without sudo, unpack a JDK 21 such as [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21) in your home folder and set `JAVA_HOME` to it, then run the script with `SKIP_APT=1`. The build server does this: its JDK is at `~/android-sdk/jdk-21`, set in `~/.bashrc`.
* **Android CLI** (`android`) & Android SDK 35:
  ```bash
  curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
  android sdk install platforms/android-35 build-tools/35.0.0 platform-tools
  ```
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

Run all **557 tests** (JVM unit tests, Robolectric persistence and headless Jetpack Compose UI tests):

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
* `AppUpdatesTest` — reading `update.json` (and rejecting unsafe entries), choosing the APK for the device, fetching it, and deleting a download that is damaged, truncated or too large.
* `UpdatesViewModelTest` / `UpdatesUiTest` — the daily check and its notice, checking on request, download progress and cancel, retrying, the install permission round trip, refused updates, and the controls in Settings → About.
* `CommandPaletteResultsTest` — what the command palette offers for a typed query.
* `ThemeContrastTest` — WCAG contrast of every theme and accent combination.
* `BrowserStorageRobolectricTest` — real Android `SharedPreferences` round trip, including data from v0.01, and the saved downloads list.
* `PopupContentSizeTest` — reading the popup helper's size reports, and ignoring anything else.
* `UploadCacheTest` — copying files picked for an upload button: names kept, same names apart, unreadable files left out, old copies removed, the MIME types offered to the picker.
* `BuiltInExtensionAssetsTest` — the bundled popup viewport helper: its id, the files its manifest names, its API and event wiring, and the messaging permissions and channel name the app listens on; and the page menu helper: its id, a content script in every frame, `nativeMessagingFromContent`, its channel name and message, and that it waits for the page and GeckoView to have answered.
* `RailExtensionSlotsTest` / `ExtensionPopupDialogTest` — how many extension buttons fit on the rail for its height; where an extension popup's window goes (beside the rail, centred after the window becomes phone-sized, right-to-left) and how big it is (default, sized to its content, within desktop Firefox's limits).
* `GoToAddressUiTest` — Go to address (Ctrl+G): opens with the page's address (empty on the start page), Enter goes there in the same tab, Alt+Enter in a new one, suggestions as you type, Esc and Ctrl+G again close it.
* `ClipboardImagesTest` — Copy image's parts that need no device: picture types sniffed from their first bytes, `data:` addresses, which formats are kept, and the 25 MB limit.
* `ComposingAwareInputConnectionTest` — the page input connection that notices a word being composed, for sending Enter past the keyboard app.
* `ConnectFailureMessageTest` — what a failed SSH connection says: refused (and the port), unreachable, blocked by the device, unknown host, timed out, with a Tailscale hint for tailnet addresses.
* `TerminalConnectionTest` / `KeyExchangeWatchdogTest` — a shell that ends while connecting shows as disconnected; the key-exchange time limit (armed, paused, restarted, stopped).
* `SshEndToEndTest` — the real SSH client (`TrileadSshConnector`) against an Apache MINA SSHD server started in the test: password sign-in opening a shell at the terminal's size that echoes what is typed, a new server confirmed once and a changed key flagged, a wrong password asked again and giving up, a generated key signing in once the server has its public half, an imported key with a passphrase, a closed port explained, and only AES ciphers offered (never ChaCha20, which fails on Conscrypt).
* `TerminalScreenViewTest` — the terminal view on the real emulator and a fake shell: Ctrl and Alt keys, the browser's keys kept out, its size in cells, selecting and copying with the mouse, double-click words, the right-click menu, scrolling the history and full-screen programs; command suggestions (shown only for what the screen echoes, Tab and → taking them, nothing at a password prompt or in a full-screen program, lines edited with keys it can't follow left alone, Backspace followed, Enter remembering a command even with its last keys still on their way); and themes, the accent cursor and the cursor style.
* `CommandSuggestionsTest` — suggestions from the history first, then common commands; only longer commands that start with exactly what was typed; what is remembered (once, most recent first, capped) and what is not (blank, a leading space, control characters, pasted scripts).
* `TerminalViewModelTest` / `TerminalStoreTest` / `TerminalModelsTest` — saved and asked-for passwords, host keys, key sign-in, a deleted host, output, titles and typing, reconnecting, closing a tab; the look and the command history saved and restored (and forgotten when remembering is turned off); terminal addresses and typed `ssh` commands.
* `TerminalUiTest` — the terminal on the real screen: a new terminal connecting to a typed address with a password, a host added in Settings connecting after its key is trusted, a generated key's public half, an unreachable server, the theme menu in the terminal's bar, and the look and suggestion settings.
* `RemBrowseUiTest` / `CompactWindowUiTest` — the real Compose screen on a laptop-sized and a phone-sized window, including extension buttons on the rail (also on short windows) and in the More menu.

### Rendering the UI without a device

`UiSnapshotTest` renders the main screens (start page, browsing, tab switcher, split view, command palette, each settings section, the extensions manager, the downloads list, the download question and notice, extension buttons and an open popup, light theme, compact layout) to PNG files. It is skipped unless you name a target directory:

```bash
./gradlew testDebugUnitTest --tests "*UiSnapshotTest*" -Prembrowse.screenshots=build/ui-screenshots
```

`TerminalSnapshotTest` does the same for the terminal (the host picker, a session in several themes with a suggestion showing, split view, the host key, password, disconnected and failed screens, the theme menu, and Settings → Terminal), against a fake shell:

```bash
./gradlew testDebugUnitTest --tests "*TerminalSnapshotTest*" -Prembrowse.screenshots=build/terminal-shots
```

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

To publish a new version, put those three files (`update.json` and both APKs) on the download site, `https://browse.example.com/`, at its top level, replacing the previous ones; commit them too, to keep the record. `update.json` holds the version and each APK's size and SHA-256; copies of RemBrowse already installed read it from the site for **Settings → About → Updates** and the daily check, so it must always describe the APKs next to it: upload the APKs first and `update.json` last. The site should not let `update.json` be cached for long (the app asks with `no-cache` and a changing query string anyway). A build that looks for updates elsewhere: `./gradlew publishApk -Prembrowse.updateUrl=https://example.com/rembrowse/`. Release APKs are about 85–89 MB: the native engine is stored compressed, GeckoView's `omni.ja` is stored uncompressed so the engine can read it in place, Gecko's crash-report analyser is left out, and unused library code and resources are removed (shrink only; nothing is renamed or optimised, see `app/proguard-rules.pro`). Each file must stay under GitHub's 100 MB limit.

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

Output (about 190–210 MB each, uncompressed for fast incremental builds):
```text
app/build/outputs/apk/debug/RemBrowse-v0.08-arm64-v8a-debug.apk
app/build/outputs/apk/debug/RemBrowse-v0.08-x86_64-debug.apk
```

### Verifying an APK

```bash
APK="build/RemBrowse-x86_64.apk"
"$HOME/Android/Sdk/build-tools/35.0.0/aapt2" dump badging "$APK" | grep -E 'package:|targetSdkVersion:|application-label:|native-code:'
"$HOME/Android/Sdk/build-tools/35.0.0/apksigner" verify --verbose "$APK"
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
