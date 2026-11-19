# RemBrowse User Guide (`v0.01`)

Welcome to **RemBrowse**, a tiled multi-pane web browser with native Mozilla WebExtension (`.xpi`) support built for Android and Googlebook OS.

---

## 1. Tab Bar & Navigation

* **Top Tab Bar**: Shows all open tabs across your workspace.
  * Tap any tab to switch to it (in tiled layouts, tapping a tab already on screen focuses its pane; tapping a hidden tab loads it into your active pane).
  * In multi-pane layouts (`1x2`, `2x1`, `2x2`), tabs currently visible in a pane show a **`P1`–`P4`** badge.
  * Tap **`×`** on the right edge of a tab to close it, or **`+`** at the end of the strip to open a new tab.
* **Omnibox**: Type any URL (`github.com`, `localhost:3000`, `about:config`) or search query (defaults to DuckDuckGo) and press Enter.
  * When in multi-pane mode, a **`P1`–`P4`** badge inside the omnibox indicates which pane you are navigating.
* **Desktop / Mobile Mode**: Tap the **monitor/phone icon** next to the omnibox to toggle Desktop User-Agent mode for the active pane.
* **Bookmarks & Tabs Manager**: Tap the **tab-count pill** (far right) to view all open tabs, send any tab directly to Pane 1/2/3/4, or browse/delete saved bookmarks.

---

## 2. Customizable Tiled Layouts (`1x1`, `1x2`, `2x1`, `2x2`)

Switch layouts anytime from the layout strip below the address bar:

| Mode | Layout | Behavior |
| :--- | :--- | :--- |
| **`1x1`** | Single full-screen pane | Shows your focused pane's tab; all other open tabs remain in the top Tab Bar |
| **`1x2`** | Side-by-side split (2 columns) | Left + Right panes with a draggable vertical divider |
| **`2x1`** | Top / Bottom split (2 rows) | Upper + Lower panes with a draggable horizontal divider |
| **`2x2`** | Quad 4-pane grid | Four simultaneous live panes with both horizontal and vertical dividers |

### Resizing & Pane Controls
* **Drag Dividers**: Grab any vertical or horizontal handle between panes to resize them smoothly (20%–80% range).
* **Reset Split**: **Double-tap** any divider to snap ratios back to 50/50.
* **Focus Pane**: Tap anywhere inside a pane (or its header bar) to make it the active pane (highlighted with a cyan border).
* **Pane Header Actions**: Each tiled pane header offers **Back**, **Reload**, **Swap Pane** (exchange contents with another pane), **Maximize** (jump straight to `1x1` with that tab), and **Close**.

---

## 3. Installing & Managing WebExtensions (`.xpi`)

Tap the **amber puzzle icon** in the top bar to open **RemBrowse WebExtensions**:

1. **Browse AMO (Recommended)**:
   * Fetches live Android-compatible add-ons from `addons.mozilla.org`.
   * Search for extensions such as **uBlock Origin**, **Dark Reader**, **Tampermonkey**, **Bitwarden**, or **Privacy Badger**.
   * Tap **Add** → review permissions in the prompt dialog → tap **Add Extension**.
2. **Install `.xpi` Tab**:
   * **Direct URL**: Paste any Mozilla-signed `.xpi` download URL and tap **Install from URL**.
   * **Local File**: Tap **Choose `.xpi` File from Device** to import a signed `.xpi` file from local storage.
3. **Installed Tab & Extension Popups**:
   * Toggle any extension on/off, uninstall it, or open its popup/options view.
   * Enabled extensions with toolbar actions also appear as quick amber chips along the layout bar—tap a chip to open the extension's interactive popup window.

> **Signing note**: Standard GeckoView requires `.xpi` packages to be signed by Mozilla (all add-ons from `addons.mozilla.org` are signed).

---

## 4. Hardware Keyboard Shortcuts

Designed for Googlebook OS / external keyboards:

| Shortcut | Action |
| :--- | :--- |
| `Ctrl + T` | Open new tab in active pane |
| `Ctrl + W` | Close active tab |
| `Ctrl + L` | Focus address bar (omnibox) |
| `Ctrl + R` | Reload / stop active pane |
| `Ctrl + 1` | Switch to `1x1` layout |
| `Ctrl + 2` | Switch to `1x2` layout |
| `Ctrl + 3` | Switch to `2x1` layout |
| `Ctrl + 4` | Switch to `2x2` layout |
