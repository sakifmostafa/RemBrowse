# RemBrowse Build & Test Guide

This guide covers building, testing, and signing **RemBrowse** (`com.rembrowser.rembrowse`) on Android Laptop / Googlebook OS (Debian Linux VM) or any Linux/macOS/Windows workstation.

---

## 1. Prerequisites

* **JDK 21** (OpenJDK 21; project compiles bytecode targeting Java 17):
  ```bash
  sudo apt-get update && sudo apt-get install -y openjdk-21-jdk-headless unzip
  ```
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

Run all **323 tests** (JVM unit tests, Robolectric persistence and headless Jetpack Compose UI tests):

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
* `BrowserViewModelTest` — layouts, lazy session creation, tab closing, new-window requests, split ratios, prompts, bookmarks, settings (startup, new-tab position, desktop sites), handing downloads over, and the settings page's tab (no engine session, typed `rem://` addresses, refusing them from pages).
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
* `BuiltInExtensionAssetsTest` — the bundled popup viewport helper: its id, the files its manifest names, its API and event wiring, and the messaging permissions and channel name the app listens on.
* `RailExtensionSlotsTest` / `ExtensionPopupDialogTest` — how many extension buttons fit on the rail for its height; where an extension popup's window goes (beside the rail, centred after the window becomes phone-sized, right-to-left) and how big it is (default, sized to its content, within desktop Firefox's limits).
* `RemBrowseUiTest` / `CompactWindowUiTest` — the real Compose screen on a laptop-sized and a phone-sized window, including extension buttons on the rail (also on short windows) and in the More menu.

### Rendering the UI without a device

`UiSnapshotTest` renders the main screens (start page, browsing, tab switcher, split view, command palette, each settings section, the extensions manager, the downloads list, the download question and notice, extension buttons and an open popup, light theme, compact layout) to PNG files. It is skipped unless you name a target directory:

```bash
./gradlew testDebugUnitTest --tests "*UiSnapshotTest*" -Prembrowse.screenshots=build/ui-screenshots
```

The images land in `app/build/ui-screenshots/`. Web content is a placeholder, because the real engine cannot run on the JVM, and dialogs are drawn at the top-left corner without the dimmed backdrop a device shows.

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

Commit those four files together to publish a new version. `update.json` holds the version and each APK's size and SHA-256; copies of RemBrowse already installed read it for **Settings → About → Updates** and the daily check, so it must always describe the APKs next to it. Release APKs are about 85–89 MB: the native engine is stored compressed, GeckoView's `omni.ja` is stored uncompressed so the engine can read it in place, Gecko's crash-report analyser is left out, and unused library code and resources are removed (shrink only; nothing is renamed or optimised, see `app/proguard-rules.pro`). Each file must stay under GitHub's 100 MB limit.

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

* **Persistent keystore**: when `signing/rembrowse.jks` exists (alias `rembrowse`; passwords from `REMBROWSE_STORE_PASSWORD` / `REMBROWSE_KEY_PASSWORD`, otherwise the defaults in `app/build.gradle.kts`), debug and release builds are signed with it. Releases from 0.09 on are signed with the key whose certificate has the SHA-256 fingerprint `F1:A5:30:6A:47:E4:BE:78:AD:7F:B0:C1:41:88:71:E3:A6:C9:A8:35:13:49:E2:42:39:DB:20:B4:75:B2:BE:06`; check a build with `apksigner verify --print-certs`. Keeping this keystore stable lets installed builds upgrade in place. It is intentionally not committed.
* **Without the keystore** (a fresh clone, CI, a cloud build), builds are signed with that machine's debug key so they remain installable. Android will not install such a build over one signed with a different key: uninstall the old copy first. **Publish from a machine that has the keystore** if you want downloads to update in place.
* **Version increments**: for each new release, update `versionName` and **always increment `versionCode`** (`15` → `16` …) in `app/build.gradle.kts` to avoid `INSTALL_FAILED_VERSION_DOWNGRADE`. A small fix or change is a patch version (`0.13` → `0.13.1` → `0.13.2`); a bigger update moves to the next minor version (`0.14`). The in-app update check compares `versionCode` only, so any of these is offered as long as the code went up. APK names and `build/VERSION.txt` follow `versionName` automatically; update the version shown in the README's download section.
* **Processor types**: `arm64-v8a` covers ARM phones, tablets and laptops; `x86_64` covers Intel/AMD Android laptops.
