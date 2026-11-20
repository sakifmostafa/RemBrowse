# Changelog

All notable changes to **RemBrowse** (`com.rembrowser.rembrowse`) are documented in this file.

## v0.09 (2026-11-20) — Extension buttons on the rail, readable popups

### Changed
* **Extension buttons moved to the bottom of the tab rail.** They used to be text entries in the More menu. Each now shows the extension's own icon, which updates when the extension changes it (a VPN showing it is connected, for example), and its badge in the colours the extension chose. Select one to open its popup; right-click or long-press it for **Manage extensions**. With more than four extensions, the last button lists the rest. On a phone-sized window, where there is no rail, they stay in the More menu, now with their icons.
* **Popups open beside their button.** On windows with the rail, an extension's popup appears at the bottom-left next to the extension buttons, the way a desktop toolbar popup drops from its button, and the page behind it is no longer dimmed. The popup's header shows the extension's icon, and its button stays marked while the popup is open.

### Fixed
* **Extension popups were shrunk to under half size.** Popups written for desktop Firefox, such as Proton VPN's, usually leave out the viewport setting a phone browser relies on, so the engine laid them out 980 pixels wide and zoomed them out to fit the popup. v0.08's narrower layout width already covers this on tablets and laptops, but phones keep 980, and v0.08 depends on a startup setting being applied. RemBrowse now also includes a small built-in helper that gives such popups that viewport setting on every device, so they are laid out at the popup's own width at full size. Popups that already set it, and extension pages opened in a tab, are unchanged. The helper is part of the browser and is not listed among your extensions.

### Known limits
* The popup fix has been checked against the GeckoView 138 sources and by automated tests of the app, but not yet on a device. If the helper cannot start, popups open as they did before.

### Testing
175 automated tests.

## v0.08 (2026-11-19) — Pages keep their size in a narrow pane

### Fixed
* **Pages were drawn small in a narrow split pane.** A tab that asks for desktop sites was always laid out at least 980 px wide and then shrunk to fit its pane, so in a pane narrower than that the whole page, text included, became small. On tablet and laptop screens a page is now laid out at the width of its pane, as in a desktop browser whose window has been made narrow: text keeps its normal size and the page rearranges itself, or scrolls sideways if it cannot get narrower. Panes wider than 980 px looked right before and are unchanged.
* Phones are deliberately left as they were: a desktop site there is still fitted to the screen.

### How
* The engine has a preference for this width but GeckoView offers no way to set it, so it is passed in the startup configuration file GeckoView reads. The file is checked with GeckoView's own parser first, and the browser starts without it if anything is wrong.

### Testing
167 automated tests. The change itself is in how the engine lays pages out, which the automated tests cannot run; it was made against the Firefox 138 source and needs a look on a device.

## v0.07 (2026-11-19) — Split controls move into the Split menu

### Changed
* **No bar above a split.** The row that appeared over the panes (linked tabs on the left, buttons on the right) is gone, so the pages get that height back. You focus a pane by selecting it, or its tab in the rail.
* **The Split menu holds the controls.** While a split is open, the **Split** button beside More opens: **Swap panes**, **Size** (50 / 50 or 70 / 30, with the current one ticked), **Layout** (Side by side, Stacked, Grid) and **Close split**. With one page on screen it still offers Split right, Split left, Stacked and Grid.
* **Size presets on small windows.** They were hidden on windows narrower than 720dp; in the menu they are always available.

### Removed
* **Expand focused pane.** It did exactly what **Close split** does (the focused page goes back to full size), so only Close split remains.
* The close button on each linked tab. Close a page in a split with `Ctrl+W`, More → Close tab, or the tab's menu in the rail.

### Testing
161 automated tests.

## v0.06 (2026-11-19) — Downloads, and Tabs settings

### Added
* **Downloads.** A link to a file the browser cannot show used to do nothing. It is now saved to the device's Downloads folder. RemBrowse asks first, naming the file, its size and the site it comes from, and nothing is fetched until you answer. A line at the bottom of the window says when a download starts and finishes, with **Open**.
* **Settings → Downloads.** Turn the question off with **Ask before downloading**, see where files go, open the folder, and manage recent downloads: cancel one that is running, open a finished one, remove entries or clear the list. More → Downloads, `Ctrl+J` and the command palette open it.
* **Settings → Tabs.**
  * **When RemBrowse starts**: continue where you left off (the default), or open a new tab and close last time's tabs.
  * **Open new tabs**: at the end of the tab list (the default) or next to the current tab. A tab opened by a page goes next to that page.
  * **Ask for desktop sites**: the default for new tabs, previously always on. More → Desktop site still changes a single tab.

### Security
* A downloaded file's name is never taken as given: directories, reserved characters and leading dots are removed, so a server cannot place a file outside the Downloads folder or hide it. An existing file is never overwritten; the new one is numbered.
* Unanswered download questions are limited to five at a time. A page that asks for more has the rest declined.

### Changed
* A tab that a link opened only to hand over a file is closed once that download is over, instead of staying behind as an empty page.

### Known limits
* Downloads run inside the browser. One that is still running when RemBrowse is closed, or stopped by Android in the background, ends and has to be started again.

### Testing
161 automated tests.

## v0.05 (2026-11-19) — Settings page

### Added
* **Settings are a page of their own.** They open in a tab at `rem://settings` instead of a dialog, with sections down the side (across the top on a phone-sized window): **Appearance**, **Search**, **Privacy**, **Extensions** and **About**. Each section has its own address, the tab reopens on the same section after a restart, and an empty new tab is reused rather than left behind. More → Settings, `Ctrl+,` and the command palette all go there.
* **Extensions are managed in Settings.** The extensions dialog is gone; **Settings → Extensions** has **Installed**, **Firefox Add-ons** (the addons.mozilla.org catalog, with add-ons you already have marked **Added**) and **From file**. More → Extensions opens it directly.
* **Search engine setting.** Settings → Search chooses the engine for words typed into the address bar or the command palette: Google (default), DuckDuckGo, Bing, Brave Search or Startpage.

### Changed
* **Searches use Google by default.** Earlier versions always searched with DuckDuckGo. After updating, typed searches go to Google until you choose another engine in Settings → Search.

### Security
* The settings page is drawn by the app and never loaded by the web engine. Websites and extensions cannot open a `rem://` address; the request is refused and no tab is created.

### Fixed
* **The address bar could show the end of a long address instead of its start.** After a page changed, the bar scrolled to the end of the new address, hiding the site name in a narrow window. It now always shows the start while you are not typing in it.

### Testing
119 automated tests.

## v0.04 (2026-11-19) — Split view redesign

### Changed
* **Split button.** The toolbar now has a labelled **Split** button. **Split right** and **Split left** open the current tab beside another tab (a background tab if there is one, otherwise a new start page) on the side you choose, and keep focus on the page you were on. Stacked and Grid are in the same menu.
* **Linked-tabs strip.** While a split is open, a strip above the panes lists the tabs that are part of it. The focused pane's tab is underlined; selecting a tab focuses its pane, and each has a close button.
* **Split controls** on that strip: **Swap panes** (focus follows your page), **50/50** and **70/30** presets, **Expand focused pane** and **Close split**.
* **Grip divider.** The divider is now a visible grip in the accent colour, with a wider grab area for touch.
* **One address bar.** There are no address bars inside the panes: the bar at the top always shows, and acts on, the focused pane, which is marked by its outline and its underlined tab in the strip.
* `Ctrl+2` and `Ctrl+\` now use the same "split the current tab" behaviour.

### Testing
105 automated tests.

## v0.03 (2026-11-19) — Ad blocker by default, new logo, two fixes found on device

### Added
* **AdGuard AdBlocker is set up by default.** On first launch the browser installs it from addons.mozilla.org (the current Mozilla-signed build) without a permission prompt, and turns it on. **Settings → Privacy → Block ads and trackers** switches it off and on; it can also be removed under Extensions, and once removed it is not reinstalled unless you flip the switch again. Its "thanks for installing" tab is suppressed.
* **New logo.** The gold RemBrowse mark is now the app icon (including Android 13 themed icons), the icon of the start page's tab, and appears in Settings → About and the README.

### Fixed
* **Pressing Enter in the address bar opened the tab switcher.** Focus was simply cleared, Android handed it to the first button in the window, and the Enter key's release pressed that button. Focus is now handed to the page (the same applies after Esc, and when the tab switcher closes), and a submitting Enter's key release is no longer delivered to anything else.
* **Sites behind Cloudflare's bot check failed with "Incompatible browser extension or network configuration".** v0.02 switched on Gecko's strictest blocking level for everyone. That level blocks its "content" list, which contains general-purpose hosts such as `cloudflare.com`, `gstatic.com`, `google.com` and `cdn.jsdelivr.net`. Standard tracking protection is now the default, the optional **Enhanced privacy** level no longer uses that list, and installs that had the strict mode switched on by v0.02 are returned to standard.

### Testing
98 automated tests.

## v0.02 (2026-11-19) — New interface, lighter builds, reliability and security fixes

### New interface
* Replaced the three-row top bar with a **left tab rail** (pinned sites, open tabs, new tab) and a **single toolbar** (back, reload, address pill, bookmark, menu, split view).
* New design tokens: `#14161B` chrome with a maroon/rose accent, dark by default, plus a **light theme** and **six accent colours** (Settings → Appearance). Every theme is checked against WCAG contrast minimums by a unit test.
* **Native start page** with a clock: new tabs open instantly and offline instead of loading a search engine.
* **Tab switcher**: searchable grid of all open tabs.
* **Command palette** (`Ctrl+K`): search, open an address, switch tabs or run commands.
* **Settings panel**: theme colour, dark theme, enhanced privacy.
* Split view panes no longer carry header bars; the focused pane is outlined, and swapping panes or resetting the split lives in the split-view menu.
* Phone-sized windows get a compact toolbar with a tab counter.
* Touch targets are at least 48dp, every icon button has an accessible name and tooltip, and selection states never rely on colour alone.

### Lighter
* Release APKs are now **84–90 MB instead of 382 MB**: one APK per processor type, compressed native libraries, unused library code removed.
* Only tabs shown in a pane get a live engine session at launch; background tabs are restored when first shown.
* The AMO catalog requests a single language, which shrinks each response considerably.
* Switching layouts resizes the existing web views instead of destroying and recreating them.

### Fixed
* Live tabs were **duplicated and leaked** whenever Android re-created the activity; sessions are now retained across re-creation and closed exactly once.
* A link shared from another app was **re-opened** after every activity re-creation.
* `window.open` / `target=_blank` lost `window.opener` (breaking popup sign-in and payment flows); the new tab now adopts the session the page asked for.
* JavaScript `alert`/`confirm`/`prompt`, HTTP sign-in and all **`<select>` dropdowns** did nothing; they now show dialogs.
* Failed navigations (offline, unknown host, bad certificate…) silently did nothing; an error page with a retry link is shown.
* A tab whose content process crashed or was killed by the OS stayed blank; it is now restored with its history, with a manual reload after repeated crashes.
* Dragging a split divider after resizing the window used the old window size, and wrote to storage on every pointer move.
* Holding `Ctrl+T`/`Ctrl+W` repeated the action for every key-repeat tick.
* Closing a tab in split view shuffled the other panes.
* A page without a `<title>` kept showing the previous page's title.
* Cancelling an extension's permission prompt showed an error banner; a second prompt could overwrite the first and leave an install hanging.
* One tab's extension badge (for example a blocker's counter) leaked onto every other tab.
* Importing a local `.xpi` blocked the main thread and never deleted its temporary copy.
* Numbers such as `3.14` or `v1.2` were treated as web addresses.
* `./gradlew` was not executable.

### Security & privacy
* Links from other apps are only opened when they are `http(s)`; `file:`, `javascript:`, `about:` and extension URLs are ignored.
* Extension **updates that request new permissions** and **optional permission requests** are no longer approved silently.
* Extension packages are only accepted over `https://` (or from a local file).
* The browser profile is excluded from cloud backup and device-to-device transfer.
* Page console output is no longer written to logcat in release builds.
* The address bar's lock icon reflects the engine's verified connection state rather than the URL scheme.

### Added shortcuts
`Ctrl+K` command palette · `Ctrl+,` settings · `Ctrl+\` split view · `Ctrl+Tab` / `Ctrl+Shift+Tab` next/previous tab · `Ctrl+D` bookmark · `Alt+←` / `Alt+→` back/forward · `Alt+D` address bar · `F5` reload.

### Testing
88 automated tests (was 28), plus an opt-in task that renders the main screens to PNG without a device.

## v0.01 (2026-11-19) — Initial Release

* **Browser Engine**: Integrated Mozilla **GeckoView `138.0`** with `BACKEND_TEXTURE_VIEW`, `arm64-v8a` and `x86_64` 64-bit native ABIs, desktop/mobile user-agent toggle, and tracking protection.
* **Native WebExtensions (`.xpi`)**:
  * Wired `WebExtensionController` with interactive permission prompt dialog (`PromptDelegate`).
  * Integrated live **addons.mozilla.org (AMO)** v5 REST API search & 1-tap `.xpi` installation.
  * Added direct `.xpi` URL install and local `.xpi` file picker via Storage Access Framework.
  * Added enable/disable/uninstall controls and extension toolbar popups (`ActionDelegate`).
* **Customizable Resizable Tiled Layouts**:
  * Added `1x1`, `1x2`, `2x1`, and `2x2` split/grid layouts with draggable split dividers (double-tap to reset to 50/50).
  * Separated pane visibility (`session.setActive(visible)`) from WebExtension focus (`setTabActive`) so all visible panes stay actively rendered without going dark when switching focus.
  * Added per-pane header controls (back, reload, swap pane, maximize, close) and click-to-focus on inactive panes.
* **Desktop Tab Bar & Persistence**:
  * Added persistent horizontal Tab Bar at the top showing all open tabs (with `P1`–`P4` pane badges in multi-pane mode).
  * Collapsing from `2x2` → `1x1` keeps the focused pane's tab active while all other tabs remain accessible in the Tab Bar.
  * Added persistent local storage for tabs, layout mode, split ratios, and bookmarks.
* **Desktop / Googlebook OS Integration**:
  * Added freeform window resizing support (`resizeableActivity="true"`, `configChanges`) and hardware keyboard shortcuts (`Ctrl+T`, `Ctrl+W`, `Ctrl+L`, `Ctrl+R`, `Ctrl+1`–`4`).
  * Designed custom 2×2 tiled-grid + extension adaptive launcher icon.
* **Testing**: Verified with 28 automated JVM unit, Robolectric persistence, and headless Jetpack Compose UI tests.

> Note: `versionCode` is set to `3` in `app/build.gradle.kts` to remain monotonically increasing for in-place Android upgrades over early development prototypes.
