<p align="center">
  <img src="docs/assets/rembrowse-logo.jpg" alt="RemBrowse" width="520">
</p>

# RemBrowse (`v0.13.8`)

**RemBrowse** (`com.rembrowser.rembrowse`) is a minimal, tiled web browser for Android laptops (Googlebook OS) and Android devices. It is written in Kotlin and Jetpack Compose on top of **Mozilla GeckoView**, and runs real Firefox **WebExtensions (`.xpi`)**.

## Download latest

**Version 0.13.8** — pick the file that matches your device's processor, open it on the device and confirm the install. Downloads and in-app updates come from [browse.example.com](https://browse.example.com/).

> **Updating from 0.08 or earlier:** from 0.09 on, RemBrowse is signed with a different key, so Android will not install it over 0.08 or older. Uninstall RemBrowse first; this removes its tabs, bookmarks and extensions. Updating from 0.09 works normally.

| Your device | Download |
| :--- | :--- |
| Intel or AMD processor (most Android laptops) | **[RemBrowse-x86_64.apk](https://browse.example.com/RemBrowse-x86_64.apk)** · 89 MB |
| ARM processor (phones, tablets, ARM laptops) | **[RemBrowse-arm64-v8a.apk](https://browse.example.com/RemBrowse-arm64-v8a.apk)** · 84 MB |

Requires Android 8.0 or newer. If Android refuses to install over an existing copy, uninstall the old one first: builds signed with different keys cannot update each other (see the [Build & Test Guide](docs/BUILD_AND_TEST_GUIDE.md#5-signing--versioning-rules)).

---

## What it does

* **Quiet, single-row chrome**: a tab rail on the left (pinned sites, open tabs, new tab) and one toolbar (back, reload, address, bookmark, menu, split view). Dark by default, with a light theme and six accent colours.
* **Split view, your way**: up to four pages in any arrangement. Drag a tab from the rail onto the edge of a page to split it (left, right, above or below), or onto its middle to show it there. Every split has its own draggable divider, and side by side, stacked, grid, one left and two right, swap, sizes and closing a pane are in the **Split** menu. Pages are laid out at their pane's own width, so text keeps its normal size in a narrow pane. The one address bar at the top always acts on the focused pane.
* **Fullscreen video**: a page's fullscreen button fills the whole screen, even from a split, and Back or Esc returns to the same layout.
* **Tab switcher**: a searchable grid of every open tab.
* **Right-click menus**: right-click a link, picture or the page itself, with a mouse or the touchpad, to open a link in a new tab or beside the page, copy a picture or an address, go back, reload, bookmark or find. Sites with their own menus keep them.
* **SSH terminal tabs**: open a terminal like a tab (`Ctrl+Shift+T` or **More → New terminal**), connect to a saved host or any `user@host`, and keep it beside your pages in a split. Hosts, keys (generate or import) and trusted servers live in Settings → Terminal, with passwords and private keys encrypted by the device's keystore. Seven themes (including a green-on-navy Midnight Green), two bundled coding fonts, cursor styles, and fish-style suggestions from your own commands and common ones, taken with Tab.
* **Find in page** (`Ctrl+F`): a floating bar on the page you are searching, with every match highlighted, a running count, next and previous, and match case.
* **Go to address** (`Ctrl+G`): the address pops up over a blur of the page, ready to type over, with suggestions from your tabs and history.
* **Command palette** (`Ctrl+K`): search the web, open an address, jump to a tab or run a browser command from the keyboard.
* **Native start page**: an instant, offline new tab with a clock, instead of loading a website.
* **Its own settings page**: Settings open in a tab at `rem://settings`, with sections for appearance, search engine, tabs, privacy, history, downloads, extensions and about.
* **History** (`Ctrl+H`): the pages you have visited, grouped by day, searchable, and suggested in the command palette. It stays on the device and can be cleared or switched off.
* **Updates itself**: Settings → About checks for a new version, downloads the one for your processor, verifies it and hands it to Android's installer, keeping your tabs and settings. A daily check (which you can turn off) lets you know when one is out.
* **Uploads**: a page's upload button (Google Drive, Proton Drive, X…) opens the system file picker, for one file or several.
* **Downloads**: files a page offers are saved to the device's Downloads folder. You are asked first, with the file's name, size and site; recent downloads are listed in Settings → Downloads (`Ctrl+J`).
* **Ad blocking out of the box**: uBlock Origin is set up automatically on first launch and can be switched off or removed at any time (Settings → Privacy). Copies that came with AdGuard AdBlocker move to uBlock Origin once, with AdGuard kept but switched off.
* **Real WebExtensions, managed in Settings**: browse and install add-ons from Firefox Add-ons (addons.mozilla.org), or install a signed `.xpi` from a link or a file. Permission requests are reviewed on install, on update and when an extension asks for more. Extension buttons sit at the bottom of the tab rail with their icons and badges, and popups open beside them at full size, sized to their content as in desktop Firefox. VPN and proxy extensions can take control of the browser's proxy setting, which the stock Android engine refuses them.
* **Built for a laptop**: freeform window resizing, hardware keyboard shortcuts, pointer hover states and resize cursors, touchpad right-clicks and two-finger scrolling that behave as on a desktop, with a compact layout for phone-sized windows.
* **Private by default**: tracking protection that does not break sites, an optional enhanced level, no cloud backup of the browser profile, and only ordinary web links are accepted from other apps.

---

## Project Structure

```text
RemBrowse/
├── README.md
├── CHANGELOG.md
├── build/                      # Published APKs (./gradlew publishApk) linked from "Download latest"
├── docs/
│   ├── assets/                 # Logo artwork
│   ├── ARCHITECTURE.md         # State, engine lifecycle, terminal, extensions and design tokens
│   ├── BUILD_AND_TEST_GUIDE.md # Toolchain, tests, UI renders, publishing and signing
│   └── USER_GUIDE.md           # The interface, split view, settings, extensions, downloads, terminal
├── app/
│   ├── build.gradle.kts        # Android app config (compileSdk/targetSdk 35)
│   └── src/
│       ├── main/java/com/rembrowser/rembrowse/
│       │   ├── RemBrowseApplication.kt
│       │   ├── MainActivity.kt # Activity + BrowserSessionHolder (retains live tabs)
│       │   ├── model/          # Layout, tab, settings, extension and page-prompt models
│       │   ├── util/           # UrlResolver, KeyboardShortcuts, ErrorPages, DownloadFileNames
│       │   ├── engine/         # BrowserEngine interfaces + GeckoView implementations
│       │   ├── data/           # AMO catalog client, state store, download storage and history, terminal store
│       │   ├── terminal/       # SSH connection, terminal view, themes, command suggestions
│       │   ├── viewmodel/      # BrowserViewModel, ExtensionsViewModel, DownloadsViewModel, TerminalViewModel
│       │   └── ui/             # RemBrowseScreen, theme tokens and components
│       ├── main/java/com/termux/ # Termux's terminal emulator and renderer (Apache 2.0, vendored)
│       └── test/               # JVM unit, Robolectric and headless Compose UI tests
├── signing/                    # Signing keystore and its passwords (never committed)
├── scripts/                    # Build-server setup, keystore password, publishing to the site
├── site/                       # The download page at browse.example.com
├── deploy/site-host/           # The site's server: nginx + Cloudflare Tunnel (Docker)
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
