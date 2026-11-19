<p align="center">
  <img src="docs/assets/rembrowse-logo.jpg" alt="RemBrowse" width="520">
</p>

# RemBrowse (`v0.07`)

**RemBrowse** (`com.rembrowser.rembrowse`) is a minimal, tiled web browser for Android laptops (Googlebook OS) and Android devices. It is written in Kotlin and Jetpack Compose on top of **Mozilla GeckoView**, and runs real Firefox **WebExtensions (`.xpi`)**.

## Download latest

**Version 0.07** — pick the file that matches your device's processor, open it on the device and confirm the install.

| Your device | Download |
| :--- | :--- |
| Intel or AMD processor (most Android laptops) | **[RemBrowse-x86_64.apk](build/RemBrowse-x86_64.apk?raw=true)** · 90 MB |
| ARM processor (phones, tablets, ARM laptops) | **[RemBrowse-arm64-v8a.apk](build/RemBrowse-arm64-v8a.apk?raw=true)** · 84 MB |

Requires Android 8.0 or newer. If Android refuses to install over an existing copy, uninstall the old one first: builds signed with different keys cannot update each other (see the [Build & Test Guide](docs/BUILD_AND_TEST_GUIDE.md#5-signing--versioning-rules)).

---

## What it does

* **Quiet, single-row chrome**: a tab rail on the left (pinned sites, open tabs, new tab) and one toolbar (back, reload, address, bookmark, menu, split view). Dark by default, with a light theme and six accent colours.
* **Split view**: open the current tab beside another one, on either side, with a draggable divider. Swap, 50/50 and 70/30 sizes, the stacked and four-pane layouts and Close split are all in the **Split** menu beside More, so the panes keep the full height of the window. The one address bar at the top always acts on the focused pane.
* **Tab switcher**: a searchable grid of every open tab.
* **Command palette** (`Ctrl+K`): search the web, open an address, jump to a tab or run a browser command from the keyboard.
* **Native start page**: an instant, offline new tab with a clock, instead of loading a website.
* **Its own settings page**: Settings open in a tab at `rem://settings`, with sections for appearance, search engine, tabs, privacy, downloads, extensions and about.
* **Downloads**: files a page offers are saved to the device's Downloads folder. You are asked first, with the file's name, size and site; recent downloads are listed in Settings → Downloads (`Ctrl+J`).
* **Ad blocking out of the box**: AdGuard AdBlocker is set up automatically on first launch and can be switched off or removed at any time (Settings → Privacy).
* **Real WebExtensions, managed in Settings**: browse and install add-ons from Firefox Add-ons (addons.mozilla.org), or install a signed `.xpi` from a link or a file. Permission requests are reviewed on install, on update and when an extension asks for more.
* **Built for a laptop**: freeform window resizing, hardware keyboard shortcuts, pointer hover states and resize cursors, with a compact layout for phone-sized windows.
* **Private by default**: tracking protection that does not break sites, an optional enhanced level, no cloud backup of the browser profile, and only ordinary web links are accepted from other apps.

---

## Project Structure

```text
RemBrowser/
├── README.md
├── CHANGELOG.md
├── build/                      # Published APKs (./gradlew publishApk) linked from "Download latest"
├── docs/
│   ├── assets/                 # Logo artwork
│   ├── ARCHITECTURE.md         # State, engine lifecycle, extensions and design tokens
│   ├── BUILD_AND_TEST_GUIDE.md # Toolchain, tests, UI renders, publishing and signing
│   └── USER_GUIDE.md           # The interface, split view, settings, extensions, downloads
├── app/
│   ├── build.gradle.kts        # Android app config (compileSdk/targetSdk 35)
│   └── src/
│       ├── main/java/com/rembrowser/rembrowse/
│       │   ├── RemBrowseApplication.kt
│       │   ├── MainActivity.kt # Activity + BrowserSessionHolder (retains live tabs)
│       │   ├── model/          # Layout, tab, settings, extension and page-prompt models
│       │   ├── util/           # UrlResolver, KeyboardShortcuts, ErrorPages, DownloadFileNames
│       │   ├── engine/         # BrowserEngine interfaces + GeckoView implementations
│       │   ├── data/           # AMO catalog client, state store, download storage and history
│       │   ├── viewmodel/      # BrowserViewModel, ExtensionsViewModel, DownloadsViewModel
│       │   └── ui/             # RemBrowseScreen, theme tokens and components
│       └── test/               # JVM unit, Robolectric and headless Compose UI tests
├── signing/                    # Your signing keystore (not committed)
├── gradle/                     # Gradle 8.11.1 wrapper
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## Documentation & Guides

* [User Guide](docs/USER_GUIDE.md) — the interface, split view, tab switcher, command palette, settings, extensions, downloads and keyboard shortcuts.
* [Architecture Guide](docs/ARCHITECTURE.md) — engine abstraction, tab lifecycle, extensions and the design tokens.
* [Build & Test Guide](docs/BUILD_AND_TEST_GUIDE.md) — toolchain setup, running the tests, rendering the UI without a device, publishing the APKs.
* [Changelog](CHANGELOG.md) — release history.

---

## Quick Build Commands

```bash
# Run the automated tests (unit + Robolectric + headless Compose UI)
./gradlew testDebugUnitTest
```

```bash
# Build the installable APKs and copy them to build/ for the "Download latest" links
./gradlew publishApk
```

```bash
# Build debug APKs (app/build/outputs/apk/debug/, one per processor type)
./gradlew assembleDebug
```
