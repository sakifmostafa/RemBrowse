# RemBrowse User Guide (`v0.08`)

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
* **Address bar** — type an address (`github.com`, `localhost:3000`, `about:config`) or a search and press Enter. Searches use Google unless you choose another engine in Settings. Selecting the bar selects the whole address; Esc cancels the edit. After Enter or Esc, keyboard focus moves to the page. A lock means the connection is verified secure; an open red lock means it is not.
* **Star** — bookmarks the current page.
* **More (⋯)** — new tab, close tab, command palette, bookmarks, downloads, extensions, desktop site, settings.
* **Split** — opens the current tab beside another one. While a split is open the button is filled, and its menu holds the split's controls.

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

Everything about the split is in the **Split** menu, beside **More** in the toolbar. Nothing is added above the pages, so they keep the full height of the window.

With one page on screen, the menu offers:

| Choice | What you get |
| :--- | :--- |
| **Split right** | The current tab on the left, another tab on the right |
| **Split left** | The current tab on the right, another tab on the left |
| **Stacked** | Two panes, top and bottom |
| **Grid** | Four panes |

"Another tab" is one of your background tabs if you have any, otherwise a new start page. Focus stays on the page you were on.

While a split is open, the **Split** button is filled and the same menu holds its controls:

* **Swap panes** — exchanges the focused pane with the next one. Focus follows your page.
* **Size** — **50 / 50** or **70 / 30** for the first pane. The current size is ticked. Offered for two panes, not for the grid.
* **Layout** — **Side by side**, **Stacked** or **Grid**. This changes the arrangement only; the pages stay in their panes.
* **Close split** — back to a single page: the focused one. Your other tabs stay open in the rail.

On a phone-sized window the same menu is under **More → Split view**.

Working in a split:

* **One address bar.** The address bar and the back, reload and bookmark buttons at the top always act on the **focused pane**, the one with the accent outline. Select anywhere inside a pane to focus it, or select its tab in the rail. Tabs that are on screen are highlighted in the rail.
* **Pages fit their pane.** On a tablet or laptop screen a page is laid out at the width of its pane, as in a desktop browser whose window has been made narrow: text keeps its normal size and the page rearranges itself, or scrolls sideways if it cannot get any narrower. (On a phone, a desktop site is still fitted to the screen as a whole.)
* **Resize**: drag the grip between the panes (20%–80%), or **double-tap** it for an even split.
* **Rearrange**: use **Swap panes**, or **Show in pane…** from a tab's menu in the rail.
* **Close a page in the split**: `Ctrl+W`, **More → Close tab**, or **Close tab** from its menu in the rail. Its pane is refilled with another tab.
* `Ctrl+\` toggles the split; `Ctrl+1`–`4` pick single, side by side, stacked and grid.

---

## 4. Command palette

Press `Ctrl+K` (or **More → Command palette**), then type:

* words to **search the web**, or an address to **open it**;
* part of a tab's title to **switch to that tab**;
* a command name: new tab, close tab, split view, bookmarks, downloads, extensions, settings.

Use ↑/↓ to move, Enter to run, Esc to close.

---

## 5. Settings

Settings are a page of their own. **More → Settings** (or `Ctrl+,`) opens them in a tab, or switches to that tab if it is already open; an empty new tab is reused. Changes apply as soon as you make them, and you close the page like any other tab.

Sections are listed down the left side (across the top on a phone-sized window):

* **Appearance** — theme colour (six choices) and **Use dark theme**.
* **Search** — the search engine used when you type words into the address bar or the command palette: Google (the default), DuckDuckGo, Bing, Brave Search or Startpage.
* **Tabs**
  * **When RemBrowse starts** — **Continue where you left off** (the default) reopens last time's tabs and layout. **Open a new tab** starts on a single empty tab instead: the previous tabs are closed, including when Android stopped the browser in the background. Bookmarks and settings are kept either way.
  * **Open new tabs** — **At the end of the tab list** (the default) or **Next to the current tab**. A tab that a page opens goes next to that page.
  * **Ask for desktop sites** — on by default. New tabs ask websites for their desktop version; turn it off to get mobile sites. It applies to new tabs and to tabs with no site in them yet. To change a single tab, use **More → Desktop site**.
* **Privacy**
  * **Block ads and trackers** — turns the built-in ad blocker (AdGuard AdBlocker) on or off. See [Ad blocking](#6-ad-blocking).
  * **Enhanced privacy** — standard tracking protection is always on. This adds blocking of known fingerprinters, cryptominers and email trackers. It is off by default; turn it off again if a site stops working.
* **Downloads** — whether to ask before saving a file, where files go, and your recent downloads. See [Downloads](#8-downloads).
* **Extensions** — your installed extensions and the Firefox Add-ons catalog. See [Extensions](#7-extensions-xpi).
* **About** — version information.

Each section has an address, shown in the address bar: `rem://settings/appearance`, `rem://settings/search`, `rem://settings/tabs`, `rem://settings/privacy`, `rem://settings/downloads`, `rem://settings/extensions` and `rem://settings/about`. Typing one takes you straight there. If a website is open in the current tab, it stays open and Settings appear in a tab of their own. Websites and extensions cannot open these addresses.

Back, reload and bookmark do nothing on the settings page. Typing a web address or a search into the address bar while on it loads that page in the same tab.

---

## 6. Ad blocking

RemBrowse sets up **AdGuard AdBlocker** for you. The first time the browser starts with an internet connection it downloads the extension from addons.mozilla.org and turns it on; no prompt is shown. If you were offline, it tries again on the next start.

* **Turn it off or on**: **More → Settings → Privacy → Block ads and trackers**.
* **Its own settings and per-site controls**: open **AdGuard AdBlocker** from the **More** menu.
* **Remove it**: **Settings → Extensions → Installed**. A removed blocker stays removed; flipping the switch in Settings installs it again.

Like any content blocker, AdGuard can read and change the pages you visit in order to filter them. If a site misbehaves, switch it off for that site from AdGuard's own menu.

---

## 7. Extensions (`.xpi`)

Extensions are managed in **Settings → Extensions** (`rem://settings/extensions`). **More → Extensions** opens the same place.

1. **Installed** — turn an extension on or off, open its popup or options, or remove it (you are asked to confirm).
2. **Firefox Add-ons** — search the catalog at addons.mozilla.org (uBlock Origin, Dark Reader, Bitwarden…). Select **Add**, review the permissions, then confirm. Add-ons you already have are marked **Added**.
3. **From file** — paste an `https://` link to a `.xpi`, or choose a `.xpi` file already on the device.

Enabled extensions with a toolbar button also appear in the **More** menu, with their badge.

You are asked again whenever an update needs **new permissions** or an extension requests **additional permissions**.

> **Signing note**: extensions must be signed by Mozilla (everything on addons.mozilla.org is).

---

## 8. Downloads

When a page offers a file the browser cannot show (an archive, an installer, a document), RemBrowse asks **Download this file?** and names the file, its size and the site it comes from. Choose **Download** to save it or **Cancel** to drop it. Nothing is fetched until you answer. A PDF's own save button skips the question, since you have already chosen to save.

* **Where files go**: the device's **Downloads** folder. If a file with the same name is already there, the new one is numbered (`report (1).pdf`); nothing is overwritten. On Android 9 and older, files go to RemBrowse's own Download folder instead.
* **While it downloads**: a line at the bottom of the window says the download has started, and again when it has finished, with **Open**. It disappears by itself after a few seconds.
* **The list**: **More → Downloads** (`Ctrl+J`) opens **Settings → Downloads**, which shows recent downloads with their progress. From there you can **cancel** one that is running (what was saved so far is removed), **Open** a finished one, or take an entry off the list. **Clear list** removes every finished entry. Removing an entry never deletes the file.
* **Open folder** shows the device's downloaded files (Android 10 and newer).
* **Turn the question off**: **Settings → Downloads → Ask before downloading**. Files are then saved as soon as a page offers them.

Good to know:

* Downloads run while RemBrowse is open. If you close it, or Android stops it in the background, a download that was still running ends and is marked **Couldn't download**; start it again from the page.
* A download that receives nothing for 30 seconds is treated as failed.
* A link that opens an empty tab only to hand over a file has that tab closed once the download is over.

---

## 9. Keyboard shortcuts

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
| `Ctrl + J` | Downloads |
| `Ctrl + \` | Toggle split view |
| `Ctrl + 1` … `Ctrl + 4` | Single pane, side by side, stacked, grid |
