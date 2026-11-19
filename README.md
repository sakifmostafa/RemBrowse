# RemBrowse (`v0.01`)

**RemBrowse** (`com.rembrowser.rembrowse`) is a native Kotlin + Jetpack Compose Android & Googlebook OS web browser powered by **Mozilla GeckoView**, featuring **native WebExtensions (`.xpi`)** and **customizable, resizable tiled tab layouts (`1x1`, `1x2`, `2x1`, `2x2`)**.

---

## Key Features

* **Real Native WebExtensions (`.xpi`)**:
  * Built on Mozilla GeckoView's `WebExtensionController` (`GeckoView 138.0`).
  * Live **addons.mozilla.org (AMO)** v5 API browser & search for 1-tap extension installation (e.g., *uBlock Origin*, *Dark Reader*, *Tampermonkey*, *Bitwarden*).
  * Direct `.xpi` URL install and local device `.xpi` file import via Android Storage Access Framework.
  * Interactive Material 3 permission review prompts, enable/disable toggles, uninstall, and toolbar extension action popups.
* **Customizable Resizable Tiled Layouts**:
  * Instant switching between **`1x1`**, **`1x2`** (side-by-side), **`2x1`** (top/bottom), and **`2x2`** (quad grid) layouts.
  * Draggable split dividers (clamped 20%–80%) with **double-tap to reset** to 50/50.
  * All visible panes stay compositor-active simultaneously with zero dark/black inactive panes.
  * Per-pane controls: back, reload, swap with any other pane, maximize to `1x1`, and close tab.
* **Desktop Tab Bar & Session Persistence**:
  * Persistent top Tab Bar showing every open tab (with `P1`–`P4` pane badges in multi-pane mode).
  * Collapsing from `2x2` → `1x1` keeps your focused tab on screen and keeps all other open tabs right in the Tab Bar.
  * Tabs, layout mode, split ratios, desktop/mobile user-agent mode, and bookmarks persist across restarts.
* **Googlebook OS / Desktop Ready**:
  * Freeform window resizing (`android:resizeableActivity="true"`) without restarting active sessions.
  * Hardware keyboard shortcuts (`Ctrl+T`, `Ctrl+W`, `Ctrl+L`, `Ctrl+R`, `Ctrl+1`–`4`).

---

## Project Structure

```text
RemBrowser/
├── README.md
├── CHANGELOG.md
├── docs/
│   ├── ARCHITECTURE.md         # MVVM, GeckoView compositor & WebExtension architecture
│   ├── BUILD_AND_TEST_GUIDE.md # Toolchain, Gradle ext4 cache, TDD & signing guide
│   └── USER_GUIDE.md           # Layouts, tab bar, extensions & keyboard shortcuts
├── app/
│   ├── build.gradle.kts        # Android app config (compileSdk/targetSdk 35, v0.01)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/            # Custom 2x2 tiled adaptive launcher icon & dark theme
│       │   └── java/com/rembrowser/rembrowse/
│       │       ├── RemBrowseApplication.kt
│       │       ├── MainActivity.kt
│       │       ├── model/      # LayoutModels, ExtensionModels
│       │       ├── util/       # UrlResolver
│       │       ├── engine/     # BrowserEngine interfaces + GeckoBrowserEngine
│       │       ├── data/       # OkHttpAmoApiClient, SharedPrefsBrowserStateRepository
│       │       ├── viewmodel/  # BrowserViewModel, ExtensionsViewModel
│       │       └── ui/         # RemBrowseScreen, BrowserTopBar, TiledBrowserGrid, ExtensionsSheet, TabsAndBookmarksDrawer
│       └── test/               # 28 automated JVM unit, Robolectric & Compose UI tests
├── signing/                    # Persistent 4096-bit RSA signing keystore
├── gradle/                     # Gradle 8.11.1 wrapper
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── local.properties
```

---

## Documentation & Guides

* [User Guide](docs/USER_GUIDE.md) — Tiled layouts, Tab Bar, installing `.xpi` extensions, and keyboard shortcuts.
* [Architecture Guide](docs/ARCHITECTURE.md) — Engine abstractions, multi-pane GeckoView lifecycle, and WebExtension popups.
* [Build & Test Guide](docs/BUILD_AND_TEST_GUIDE.md) — Setting up JDK 21 + Android CLI, building signed APKs, and running all 28 tests.
* [Changelog](CHANGELOG.md) — Release history starting at `v0.01`.

---

## Quick Build Commands

```bash
# Run all 28 automated tests (unit + Robolectric + headless Compose UI)
./gradlew testDebugUnitTest --no-daemon \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"

# Build signed debug APK (outputs app/build/outputs/apk/debug/RemBrowse-v0.01-debug.apk)
./gradlew assembleDebug --no-daemon \
  --project-cache-dir="$HOME/.cache/projects/$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)/gradle"
```
