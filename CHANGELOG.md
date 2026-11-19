# Changelog

All notable changes to **RemBrowse** (`com.rembrowser.rembrowse`) are documented in this file.

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
