# RemBrowse User Guide (`v0.04`)

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
* **Address bar** — type an address (`github.com`, `localhost:3000`, `about:config`) or a search (DuckDuckGo) and press Enter. Selecting the bar selects the whole address; Esc cancels the edit. After Enter or Esc, keyboard focus moves to the page. A lock means the connection is verified secure; an open red lock means it is not.
* **Star** — bookmarks the current page.
* **More (⋯)** — new tab, close tab, command palette, bookmarks, extensions, desktop site, settings.
* **Split** — opens the current tab beside another one, or picks another layout.

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

Select **Split** in the toolbar:

| Choice | What you get |
| :--- | :--- |
| **Split right** | The current tab on the left, another tab on the right |
| **Split left** | The current tab on the right, another tab on the left |
| **Stacked** | Two panes, top and bottom |
| **Grid** | Four panes |
| **Close split** | Back to a single page. Your other tabs stay open in the rail |

"Another tab" is one of your background tabs if you have any, otherwise a new start page. Focus stays on the page you were on.

While a split is open, a strip appears above the panes:

* **Linked tabs** (after the link icon) — the tabs that make up the split. The focused pane's tab is underlined. Select a tab to focus its pane; **×** closes that tab.
* **Swap panes** — exchanges the focused pane with the next one. Focus follows your page.
* **50/50** and **70/30** — preset sizes for the first pane.
* **Expand focused pane** — returns that page to the full window.
* **Close split** — back to a single page.

Working in a split:

* **One address bar.** The address bar and the back, reload and bookmark buttons at the top always act on the **focused pane**. It is the one with the accent outline, and its tab is underlined in the strip. Select anywhere inside a pane to focus it.
* **Resize**: drag the grip between the panes (20%–80%), or **double-tap** it for an even split.
* **Rearrange**: use **Swap panes**, or **Show in pane…** from a tab's menu in the rail.
* `Ctrl+\` toggles the split; `Ctrl+1`–`4` pick single, side by side, stacked and grid.

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
* **Privacy**
  * **Block ads and trackers** — turns the built-in ad blocker (AdGuard AdBlocker) on or off. See [Ad blocking](#6-ad-blocking).
  * **Enhanced privacy** — standard tracking protection is always on. This adds blocking of known fingerprinters, cryptominers and email trackers. It is off by default; turn it off again if a site stops working.
* **About** — version information.

---

## 6. Ad blocking

RemBrowse sets up **AdGuard AdBlocker** for you. The first time the browser starts with an internet connection it downloads the extension from addons.mozilla.org and turns it on; no prompt is shown. If you were offline, it tries again on the next start.

* **Turn it off or on**: **More → Settings → Privacy → Block ads and trackers**.
* **Its own settings and per-site controls**: open **AdGuard AdBlocker** from the **More** menu.
* **Remove it**: **More → Extensions → Installed**. A removed blocker stays removed; flipping the switch in Settings installs it again.

Like any content blocker, AdGuard can read and change the pages you visit in order to filter them. If a site misbehaves, switch it off for that site from AdGuard's own menu.

---

## 7. Extensions (`.xpi`)

Open **More → Extensions**:

1. **Discover** — search add-ons from addons.mozilla.org (uBlock Origin, Dark Reader, Bitwarden…). Select **Add**, review the permissions, then confirm.
2. **From file** — paste an `https://` link to a `.xpi`, or choose a `.xpi` file already on the device.
3. **Installed** — turn an extension on or off, open its popup or options, or remove it (you are asked to confirm).

Enabled extensions with a toolbar button also appear in the **More** menu, with their badge.

You are asked again whenever an update needs **new permissions** or an extension requests **additional permissions**.

> **Signing note**: extensions must be signed by Mozilla (everything on addons.mozilla.org is).

---

## 8. Keyboard shortcuts

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
