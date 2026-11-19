# RemBrowse User Guide (`v0.02`)

RemBrowse is a minimal, tiled web browser with Firefox extension support, built for Android laptops (Googlebook OS) and Android devices.

---

## 1. The window at a glance

**Tab rail (left edge)**, top to bottom:

* **Tab switcher** — opens a grid of every open tab.
* **Pinned sites** — your first six bookmarks. Select one to open it.
* **Open tabs** — one tile per tab. The focused tab has a filled tile and a marker on its left edge. Right-click or long-press a tab for **Close tab**, **Close other tabs** and, in split view, **Show in pane…**.
* **+** — opens a new tab.

**Toolbar (top)**, left to right:

* **Back**, and **Forward** when there is somewhere to go forward to.
* **Reload** (becomes **Stop** while a page loads).
* **Address bar** — type an address (`github.com`, `localhost:3000`, `about:config`) or a search (DuckDuckGo) and press Enter. Selecting the bar selects the whole address; Esc cancels the edit. A lock means the connection is verified secure; an open red lock means it is not.
* **Star** — bookmarks the current page.
* **More (⋯)** — new tab, close tab, command palette, bookmarks, extensions, desktop site, settings.
* **Split view** — choose a layout.

On a phone-sized window the rail is replaced by a **tab counter** in the toolbar, and reload, bookmark and split view move into the **More** menu.

**Start page** — a new tab shows a clock and the date instead of loading a website. Just start typing in the address bar.

---

## 2. Tabs

* **Tab switcher**: select the top icon of the rail (or the tab counter). Type to filter by title or address, press Enter to open the first match, select a card to switch to it, or use **×** on a card to close it.
* `Ctrl+Tab` / `Ctrl+Shift+Tab` move to the next or previous tab.
* Only the tabs you can see are kept running. Tabs in the background are paused and restored when you return to them, including after a restart.
* If a tab's page crashes, it is reloaded for you. After repeated crashes it shows a **Reload** button instead.

---

## 3. Split view

Open the **Split view** menu in the toolbar (or `Ctrl+1`–`4`):

| Layout | What you get |
| :--- | :--- |
| **Single pane** | One page. Your other tabs stay open in the rail |
| **Side by side** | Two panes, left and right |
| **Stacked** | Two panes, top and bottom |
| **Grid** | Four panes |

* **Focus**: select anywhere inside a pane. The focused pane has an accent outline, and the address bar and toolbar act on it.
* **Resize**: drag the handle between panes (20%–80%). **Double-tap** the handle, or choose **Reset split** in the menu, for an even split.
* **Rearrange**: **Swap with pane…** in the split-view menu, or **Show in pane…** from a tab's menu in the rail.
* `Ctrl+\` toggles between a single pane and side by side.

---

## 4. Command palette

Press `Ctrl+K` (or **More → Command palette**), then type:

* words to **search the web**, or an address to **open it**;
* part of a tab's title to **switch to that tab**;
* a command name: new tab, close tab, split view, bookmarks, extensions, settings.

Use ↑/↓ to move, Enter to run, Esc to close.

---

## 5. Settings

**More → Settings** (or `Ctrl+,`):

* **Appearance** — theme colour (six choices) and **Use dark theme**.
* **Privacy** — **Enhanced privacy** blocks trackers, fingerprinters and cryptominers on every site. Turn it off if a site you trust stops working.
* **About** — version information.

---

## 6. Extensions (`.xpi`)

Open **More → Extensions**:

1. **Discover** — search add-ons from addons.mozilla.org (uBlock Origin, Dark Reader, Bitwarden…). Select **Add**, review the permissions, then confirm.
2. **From file** — paste an `https://` link to a `.xpi`, or choose a `.xpi` file already on the device.
3. **Installed** — turn an extension on or off, open its popup or options, or remove it (you are asked to confirm).

Enabled extensions with a toolbar button also appear in the **More** menu, with their badge.

You are asked again whenever an update needs **new permissions** or an extension requests **additional permissions**.

> **Signing note**: extensions must be signed by Mozilla (everything on addons.mozilla.org is).

---

## 7. Keyboard shortcuts

| Shortcut | Action |
| :--- | :--- |
| `Ctrl + T` | New tab |
| `Ctrl + W` | Close tab |
| `Ctrl + Tab` / `Ctrl + Shift + Tab` | Next / previous tab |
| `Ctrl + L` or `Alt + D` | Focus the address bar |
| `Ctrl + K` | Command palette |
| `Ctrl + R` or `F5` | Reload / stop |
| `Alt + ←` / `Alt + →` | Back / forward |
| `Ctrl + D` | Bookmark this page |
| `Ctrl + ,` | Settings |
| `Ctrl + \` | Toggle split view |
| `Ctrl + 1` … `Ctrl + 4` | Single pane, side by side, stacked, grid |
