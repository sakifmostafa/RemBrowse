# RemBrowse User Guide (`v0.13.7`)

RemBrowse is a minimal, tiled web browser with Firefox extension support, built for Android laptops (Googlebook OS) and Android devices.

---

## 1. The window at a glance

**Tab rail (left edge)**, top to bottom:

* **Tab switcher** — opens a grid of every open tab.
* **Pinned sites** — your first six bookmarks. Select one to open it.
* **Open tabs** — one tile per tab. The focused tab has a filled tile and a marker on its left edge. Right-click or long-press a tab for **Close tab**, **Close other tabs** and, in split view, **Show in pane…** and **Close pane**. Drag a tab onto the pages to build a split (see [Split view](#5-split-view)).
* **+** — opens a new tab.
* **Extensions (bottom)** — the buttons of your extensions, such as AdGuard AdBlocker or a VPN, with their icon and badge (an ad blocker's count, for example). Select one to open its popup, which appears beside the button. Right-click or long-press one for **Manage extensions**. With more than fit (four at most; fewer on a short window, such as a phone in landscape), the last button lists the rest.

**Toolbar (top)**, left to right:

* **Back**, and **Forward** when there is somewhere to go forward to.
* **Reload** (becomes **Stop** while a page loads).
* **Address bar** — type an address (`github.com`, `localhost:3000`, `about:config`) or a search and press Enter. Searches use Google unless you choose another engine in Settings. Clicking into the bar selects the whole address, ready to replace; click again to place the cursor, double-click a word to select it (it stops at dots, slashes and hyphens, so double-clicking `example` in `www.example.com` selects just `example`), drag to select part of the address, or triple-click to select all of it. Right-click the bar for **Cut**, **Copy**, **Paste**, **Paste and go** and **Select all**. All of this works the same with a mouse and with the touchpad. Esc cancels the edit. After Enter or Esc, keyboard focus moves to the page. A lock means the connection is verified secure; an open red lock means it is not.
* **Star** — bookmarks the current page.
* **More (⋯)** — new tab, new terminal, close tab, command palette, find in page, bookmarks, history, downloads, extensions, desktop site, settings.
* **Split** — opens the current tab beside another one. While a split is open the button is filled, and its menu holds the split's controls.

On a phone-sized window the rail is replaced by a **tab counter** in the toolbar, and reload, bookmark, split view and the extension buttons move into the **More** menu.

**Start page** — a new tab shows a clock and the date instead of loading a website. Just start typing in the address bar.

### Go to address (`Ctrl+G`)

`Ctrl+G` opens the address in the middle of the window, over a blur of everything behind it (a darkened window where the device has blur turned off, as in battery saver). It starts with the current page's address selected, so you can type over it. As you type, it offers the address or search, open tabs and pages from your history; Up and Down choose, **Enter** goes there in the focused pane, **Alt+Enter** opens it in a new tab, and Esc, a click outside or `Ctrl+G` again closes it. It is also in the command palette as **Go to address…**. In a terminal, use `Ctrl+Shift+G`.

---

## 2. Find in page

Press `Ctrl+F` (or **More → Find in page**) to search the page you are on. A bar drops into the top corner of that page, in a split on the page you are searching, and every match is highlighted as you type.

* **The count** — "3/12" is the third of twelve matches. It turns red with **No results** when nothing matches.
* **Next and previous** — Enter or `F3` for the next match, Shift+Enter or `Shift+F3` for the one before, or the arrows in the bar. (`Ctrl+G` is now [Go to address](#go-to-address-ctrlg).) Past the last match it starts again from the top.
* **Aa** — match upper and lower case exactly.
* **Close** — Esc or the **×**; the highlighting goes with it. Moving to another tab closes it too. `Ctrl+F` while the bar is open selects its text, ready for a new search.

Only web pages can be searched, not the start page or Settings.

---

## 3. Right-click menus

Right-click on a page, with a mouse or the touchpad (a two-finger tap, or a click in the pad's bottom-right corner), for a menu of what is under the pointer:

* **A link** — **Open link in new tab** (it opens in the background, next to the page it came from, and starts loading), **Open link in split view** (beside this page, which keeps focus; offered while there is room for another pane), **Copy link** and **Copy link text**.
* **A picture, video or sound** — **Open image in new tab**, **Copy image** and **Copy image address** (or video, or audio, which have no Copy). **Copy image** puts the picture itself on the clipboard, ready to paste into a document, a chat or an image editor; it is fetched again as the page loaded it, with the site's sign-in, and WebP and AVIF pictures are turned into PNG so other apps can paste them. A linked picture gets both the link's and the picture's rows.
* **Anywhere else on the page** — **Back**, **Forward**, **Reload**, **Bookmark this page**, **Copy page address** and **Find in page**.

The menu opens where you clicked; select a row, or click anywhere else (or press Esc) to close it. Sites with their own right-click menus, such as Google Docs, keep showing theirs. With text selected, a right-click shows the toolbar for the selection (Copy, Select all and so on) instead. In a page's text fields, right-click does nothing; use `Ctrl+C`, `Ctrl+X` and `Ctrl+V` (the address bar has its own right-click menu). On a touchscreen, long-press a link or picture for the same menu; long-pressing text selects it, as in any Android browser.

**The touchpad is a mouse everywhere.** Touchpad clicks work like mouse clicks in pages and in RemBrowse's own fields and buttons: double-click selects a word, click-and-drag selects text, triple-click selects a line, and the pointer shows hover highlights and resize cursors.

**Touchpad scrolling.** Two fingers resting on the pad, or moving only a little, never count as a press on the page: scrolling then pausing no longer opens menus or selects words, and a small nudge no longer clicks whatever is under the pointer. A page starts to scroll once the fingers have moved about 2.5 mm.

---

## 4. Tabs

* **Tab switcher**: select the top icon of the rail (or the tab counter). Type to filter by title or address, press Enter to open the first match, select a card to switch to it, or use **×** on a card to close it.
* `Ctrl+Tab` / `Ctrl+Shift+Tab` move to the next or previous tab.
* Only the tabs you can see are kept running. Tabs in the background are paused and restored when you return to them, including after a restart.
* If a tab's page crashes, it is reloaded for you. After repeated crashes it shows a **Reload** button instead.

---

## 5. Split view

Up to four pages can share the window, in any arrangement: side by side, stacked, a grid, or for example one tall page beside two stacked ones. Nothing is added above the pages, so they keep the full height of the window.

### Drag tabs to build a layout

Drag a tab from the rail onto the pages. A highlight shows where it will land before you let go:

* **Near an edge of a page** (its outer quarter) — that page is split, and the tab opens **left**, **right**, **above** or **below** it. To put a third page under YouTube, drag the tab to the bottom edge of the YouTube page.
* **In the middle of a page** — the tab is **shown there** instead; the page that was there stays open in the rail.

With a mouse or touchpad, press on a tab and drag it. With a finger, long-press the tab until its menu opens, then drag without lifting (the menu closes); a quick swipe still scrolls the rail. Dragging a tab that is already on screen moves it, and the place it left closes up. With four pages on screen, only the middle of a page is offered, unless you are moving one of the four.

### The Split menu

The **Split** menu, beside **More** in the toolbar, has buttons for the common layouts. With one page on screen it offers:

| Choice | What you get |
| :--- | :--- |
| **Split right** | The current tab on the left, another tab on the right |
| **Split left** | The current tab on the right, another tab on the left |
| **Stacked** | Two panes, top and bottom |
| **Grid** | Four panes |
| **One left, two right** | The current tab full height on the left, two more stacked on the right |

"Another tab" is one of your background tabs if you have any, otherwise a new start page. Focus stays on the page you were on.

While a split is open, the **Split** button is filled and the same menu holds its controls:

* **Swap panes** — exchanges the focused pane with the next one. Focus follows your page.
* **Size** — **50 / 50** or **70 / 30** for the first pane. The current size is ticked. Offered when there are two panes.
* **Layout** — **Side by side**, **Stacked**, **Grid** or **One left, two right**, ticked when the current arrangement is one of them. Choosing a two-pane layout while three or four pages are on screen keeps the first two and the focused one.
* **Close this pane** — the focused pane goes and its neighbour takes the room. Its tab stays open in the rail.
* **Close split** — back to a single page: the focused one. Your other tabs stay open in the rail.

On a phone-sized window the same menu is under **More → Split view**.

Working in a split:

* **One address bar.** The address bar and the back, reload and bookmark buttons at the top always act on the **focused pane**, the one with the accent outline. Select anywhere inside a pane to focus it, or select its tab in the rail. Tabs that are on screen are highlighted in the rail.
* **Pages fit their pane.** On a tablet or laptop screen a page is laid out at the width of its pane, as in a desktop browser whose window has been made narrow: text keeps its normal size and the page rearranges itself, or scrolls sideways if it cannot get any narrower. (On a phone, a desktop site is still fitted to the screen as a whole.)
* **Resize**: every split has its own grip; drag it (20%–80%), or **double-tap** or **double-click** it to even out that split alone. With a mouse or touchpad the resize arrows show over the whole grip and stay while you drag, even when the pointer runs past a limit; the grip moves again once the pointer comes back to it.
* **Rearrange**: drag tabs from the rail, use **Swap panes**, or **Show in pane…** from a tab's menu in the rail.
* **Close a pane, keep its tab**: **Close pane** from the tab's menu in the rail, or **Split → Close this pane**.
* **Close a page in the split**: `Ctrl+W`, **More → Close tab**, or **Close tab** from its menu in the rail. Its pane is refilled with another tab.
* `Ctrl+\` toggles the split; `Ctrl+1`–`5` pick single, side by side, stacked, grid, and one left, two right.
* Your layout, its sizes and which tab is where come back the next time RemBrowse starts.

On a phone-sized window there is no rail to drag from, but any layout is shown, and panes can be closed from the menu.

### Fullscreen video

A page's fullscreen button (a video's, for example) gives it the whole screen: the rail, the toolbar, the other panes and Android's status and navigation bars are hidden. On a Googlebook with Android 15 or newer, a RemBrowse window that is not already full-screen is enlarged to fill the screen, and returns to its size afterwards. Swipe from the edge of the screen to see the system bars for a moment.

To leave, press **Back** or **Esc**, or the page's own button. The split comes back exactly as it was.

---

## 6. Command palette

Press `Ctrl+K` (or **More → Command palette**), then type:

* words to **search the web**, or an address to **open it**;
* part of a tab's title to **switch to that tab**;
* part of a page's title or address to **open it again from your history** (up to five matches, below the open tabs);
* a command name: new tab, close tab, find in page, split view, bookmarks, history, downloads, extensions, settings.

Use ↑/↓ to move, Enter to run, Esc to close.

---

## 7. Settings

Settings are a page of their own. **More → Settings** (or `Ctrl+,`) opens them in a tab, or switches to that tab if it is already open; an empty new tab is reused. Changes apply as soon as you make them, and you close the page like any other tab.

Sections are listed down the left side (across the top on a phone-sized window):

* **Appearance** — theme colour (six choices), **Use dark theme**, and **Page rendering**, how web pages are put on the screen:
  * **Standard** (the default) — pages are drawn inside the window, set up once rather than again for every frame as before 0.13.3.
  * **Direct (experimental)** — pages go straight to the screen, which may scroll more smoothly still; a pane may flicker while it is resized or opens.
  * **As in 0.13.2 and earlier** — the old way, to compare against if scrolling is still not smooth, or if pages ever stay blank on the others.

  Open pages switch at once.
* **Search** — the search engine used when you type words into the address bar or the command palette: Google (the default), DuckDuckGo, Bing, Brave Search or Startpage.
* **Tabs**
  * **When RemBrowse starts** — **Continue where you left off** (the default) reopens last time's tabs and layout. **Open a new tab** starts on a single empty tab instead: the previous tabs are closed, including when Android stopped the browser in the background. Bookmarks and settings are kept either way.
  * **Open new tabs** — **At the end of the tab list** (the default) or **Next to the current tab**. A tab that a page opens goes next to that page.
  * **Ask for desktop sites** — on by default. New tabs ask websites for their desktop version; turn it off to get mobile sites. It applies to new tabs and to tabs with no site in them yet. To change a single tab, use **More → Desktop site**.
* **Privacy**
  * **Block ads and trackers** — turns the built-in ad blocker (AdGuard AdBlocker) on or off. See [Ad blocking](#8-ad-blocking).
  * **Enhanced privacy** — standard tracking protection is always on. This adds blocking of known fingerprinters, cryptominers and email trackers. It is off by default; turn it off again if a site stops working.
* **History** — the web pages you have visited, newest first, grouped by day. See [History](#history) below.
* **Downloads** — whether to ask before saving a file, where files go, and your recent downloads. See [Downloads](#10-downloads).
* **Extensions** — your installed extensions and the Firefox Add-ons catalog. See [Extensions](#9-extensions-xpi).
* **Terminal** — your SSH hosts and keys, the servers you trust, and how terminals look and suggest commands. See [Terminal](#11-terminal).
* **About** — the version you have, and updates. See [Updates](#updates) below.

Each section has an address, shown in the address bar: `rem://settings/appearance`, `rem://settings/search`, `rem://settings/tabs`, `rem://settings/privacy`, `rem://settings/history`, `rem://settings/downloads`, `rem://settings/extensions`, `rem://settings/terminal` and `rem://settings/about`. Typing one takes you straight there. If a website is open in the current tab, it stays open and Settings appear in a tab of their own. Websites and extensions cannot open these addresses.

Back, reload and bookmark do nothing on the settings page. Typing a web address or a search into the address bar while on it loads that page in the same tab.

### History

**More → History** (`Ctrl+H`, or **History** in the command palette) opens **Settings → History**: every web page you have opened, newest first, under a heading for each day, with the time of your last visit. A page visited again moves to the top rather than being listed twice. The newest 1,000 pages are kept.

* **Open a page**: select it. It opens in a new tab.
* **Search**: type in **Search history**; pages whose title or address contain every word you type are listed.
* **Remove one page**: its **×** button. **Clear history** removes everything, after asking.
* **Stop recording**: turn off **Keep browsing history**. What is already listed stays until you clear it.

The start page, RemBrowse's own pages and extension pages are never recorded. History is kept only on this device.

### Updates

RemBrowse can update itself. **Settings → About** shows the version you have and, under **Updates**:

* **Check for updates** looks for a newer version straight away.
* **Download and install** fetches the new version for your device's processor, with its progress and a **Cancel** button. Once it has arrived and checks out, Android's installer asks you to confirm. Your tabs, bookmarks, extensions and settings are kept.
* The first time, Android asks you to **allow RemBrowse to install apps**. Allow it and come back: the update continues by itself.
* **Check for updates automatically** (on by default) checks once a day when RemBrowse starts. When there is a new version, a notice at the bottom of the window offers it: **Update** downloads it, **×** hides the notice until the next version. Nothing is downloaded until you choose to.

Updates come from the same place as the [download links in the README](../README.md#download-latest), the RemBrowse repository on GitHub. Before installing, RemBrowse checks that the file is exactly the one published (its size and SHA-256 fingerprint), that it is RemBrowse, newer than your copy, and signed with the same key. A file that fails any check is deleted and not installed.

---

## 8. Ad blocking

RemBrowse sets up **AdGuard AdBlocker** for you. The first time the browser starts with an internet connection it downloads the extension from addons.mozilla.org and turns it on; no prompt is shown. If you were offline, it tries again on the next start.

* **Turn it off or on**: **More → Settings → Privacy → Block ads and trackers**.
* **Its own settings and per-site controls**: open **AdGuard AdBlocker** from the **More** menu.
* **Remove it**: **Settings → Extensions → Installed**. A removed blocker stays removed; flipping the switch in Settings installs it again.

Like any content blocker, AdGuard can read and change the pages you visit in order to filter them. If a site misbehaves, switch it off for that site from AdGuard's own menu.

---

## 9. Extensions (`.xpi`)

Extensions are managed in **Settings → Extensions** (`rem://settings/extensions`). **More → Extensions** opens the same place.

1. **Installed** — turn an extension on or off, open its popup or options, or remove it (you are asked to confirm).
2. **Firefox Add-ons** — search the catalog at addons.mozilla.org (uBlock Origin, Dark Reader, Bitwarden…). Select **Add**, review the permissions, then confirm. Add-ons you already have are marked **Added**.
3. **From file** — paste an `https://` link to a `.xpi`, or choose a `.xpi` file already on the device.

Enabled extensions with a toolbar button appear at the bottom of the tab rail, with their icon and badge; on a phone-sized window they are in the **More** menu.

**Popups.** An extension's popup opens beside its button and closes when you select anything outside it, press Esc or use **×**. Most popups are written for desktop Firefox and leave out the setting that tells a phone browser how wide to lay them out, which used to make them appear shrunk to under half size. RemBrowse now lays them out at the popup's own width at full size; a popup that already sets its own width is left as it is. Like desktop Firefox, RemBrowse sizes each such popup to its content, up to 800 × 600 and within the window: a narrow popup shrinks to fit and a wide one (Proton Pass) gets its full width instead of being shrunk. Popups made for phones keep the standard size.

**VPN and proxy extensions.** Extensions such as Proton VPN work by taking control of the browser's proxy setting. Firefox's engine refuses that on Android; RemBrowse ships the engine with that refusal removed, so these extensions can connect. Extensions are also allowed in private browsing, which Firefox requires for this: RemBrowse has no private windows, so the permission exposes nothing, and an extension's "allow in private windows" hint can be ignored.

You are asked again whenever an update needs **new permissions** or an extension requests **additional permissions**.

> **Signing note**: extensions must be signed by Mozilla (everything on addons.mozilla.org is).

---

## 10. Downloads

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


**Uploading files.** A page's upload button (Google Drive, Proton Drive, X…) opens the system file picker. Pick one file, or several when the page allows it; the picker shows the kinds of file the page accepts. Cancelling sends nothing. RemBrowse hands the page a private copy of each file, which is removed the next time the browser starts. Uploading a whole folder is not supported yet.

---

## 11. Terminal

A terminal tab connects to a server over SSH, like a page in its own tab. It sits in the tab rail, splits beside pages, and keeps its connection while you work in other tabs.

### Opening one

**More → New terminal** (or **New terminal** in the command palette, or the address `rem://terminal`) opens a new terminal. It lists your saved hosts; select one to connect, or type an address in **Connect to**: `user@host`, `user@host:2222`, or a whole `ssh user@host -p 2222` command. Saved hosts are added and edited in **Settings → Terminal**.

The first time you connect to a server, RemBrowse shows its **key fingerprint** (`SHA256:…`, as `ssh-keygen -lf` prints it) and asks whether to trust it. If a trusted server's key ever changes, you are warned before anything is sent and nothing connects until you choose. A server that asks for a password gets a password box, with **Remember on this device**.

### Working in it

* Everything you type goes to the server. The shell keeps its own keys (`Ctrl+C`, `Ctrl+W`, `Ctrl+R`, `Ctrl+L`, Tab completion…), so the browser's shortcuts move to `Ctrl+Shift` while a terminal has the keyboard; see [Keyboard shortcuts](#12-keyboard-shortcuts).
* **Select** by dragging, double-click a word or path, and copy with `Ctrl+Shift+C`. Paste with `Ctrl+Shift+V` or `Shift+Insert`. Right-click for **Copy**, **Paste**, **Select all**, and **Disconnect** or **Reconnect**.
* **Scroll back** through earlier output with the mouse wheel, two fingers on the touchpad, or `Shift+Page Up` / `Shift+Page Down`. Typing returns to the bottom. In full-screen programs (`less`, `man`, `htop`, `vim`), the wheel scrolls the program instead.
* The **bar above the terminal** shows the host and whether it is connected (green), connecting (yellow) or disconnected (red), with buttons for the theme and text size (one menu), disconnecting or reconnecting, and the terminal's settings.
* When the connection ends, the last screen stays. Press **Enter** or **Reconnect** to connect again.
* Closing the tab disconnects.

### Command suggestions

As you type at the prompt, dimmed text after the cursor suggests how the command might end: first from the commands you have run before (most recent first), then from a built-in list of common ones (`git status`, `docker compose up -d`, `sudo systemctl restart`…). A small **Tab** key after it shows how to take it: press **Tab** or **→**. With no suggestion showing, Tab is the shell's own completion, as usual.

Suggestions only appear when the screen shows what you typed, so nothing is suggested at a password prompt or inside full-screen programs, and what you type at a password prompt is never remembered. As in most shells, a command you type with a **space in front** is not remembered either. Remembered commands stay on the device. Both settings can be turned off in **Settings → Terminal → Suggestions**, where **Forget remembered commands** clears the list.

### How it looks

Pick a **theme** from the palette button on the terminal's bar (its menu also has the text size), or in **Settings → Terminal → Appearance**: **RemBrowse** (the default, with the cursor in your accent colour), **Midnight Green** (green on navy, like Ubuntu's terminal), **Dracula**, **Nord**, **Solarized Dark**, **Gruvbox Dark**, or **Paper** (light). Every terminal changes at once. Appearance also has the **font** (JetBrains Mono, the default; Fira Mono; or the system's monospace font), the **cursor** (block, bar or underline; programs such as vim can still change it while they run), and the **text size**.

### Hosts and keys

In **Settings → Terminal**:

* **Hosts** — **Add host** with a name, the address, port and username, and whether it signs in with a **password** (saved on the device or asked each time) or a **key**. Each host can be connected to, edited or deleted from here.
* **Keys** — **Generate key** makes an Ed25519 key; **Public key** shows the line to add to the server's `~/.ssh/authorized_keys`, with a button to copy it. **Import a private key…** takes a pasted OpenSSH (`BEGIN OPENSSH PRIVATE KEY`) or PEM private key (Ed25519, ECDSA or RSA), with its passphrase if it has one. The passphrase is needed only then: the key is kept encrypted on the device, as passwords are (below).
* **Trusted servers** — the servers whose keys you accepted, with their fingerprints. **Forget** one to be asked again next time.

Passwords and private keys are encrypted with a key held by Android's keystore, which never leaves the device; they are not backed up and cannot be read from a copy of the app's data. Host names, public keys and fingerprints are stored as ordinary settings.

### Limits

* SSH only: no local shell, Mosh, Telnet or serial connections.
* No port forwarding, SFTP file transfer or SSH agent forwarding yet.
* Commands you run through the shell's own history or Tab completion are not remembered for suggestions, because the terminal can't see the finished line.
* Not yet tried on a device.

---

## 12. Keyboard shortcuts

| Shortcut | Action |
| :--- | :--- |
| `Ctrl + T` | New tab |
| `Ctrl + W` | Close tab |
| `Ctrl + Tab` / `Ctrl + Shift + Tab` | Next / previous tab |
| `Ctrl + L` or `Alt + D` | Focus the address bar |
| `Ctrl + G` | Go to address (a popup over the page) |
| `Ctrl + K` | Command palette |
| `Ctrl + F` | Find in page |
| `F3` / `Shift + F3` | Next / previous match |
| `Ctrl + R` or `F5` | Reload / stop |
| `Alt + ←` / `Alt + →` | Back / forward |
| `Ctrl + D` | Bookmark this page |
| `Ctrl + ,` | Settings |
| `Ctrl + H` | History |
| `Ctrl + J` | Downloads |
| `Ctrl + \` | Toggle split view |
| `Ctrl + 1` … `Ctrl + 5` | Single pane, side by side, stacked, grid, one left and two right |

These work anywhere in the window, also while you are typing in a web page's text box.

**In a terminal**, the shell keeps `Ctrl` and `Alt`, so the browser's shortcuts take `Ctrl+Shift` instead:

| Shortcut | Action |
| :--- | :--- |
| `Ctrl + Shift + T` / `Ctrl + Shift + W` | New tab / close tab |
| `Ctrl + Tab` / `Ctrl + Shift + Tab` | Next / previous tab |
| `Ctrl + Shift + L` / `Ctrl + Shift + G` / `Ctrl + Shift + K` | Address bar / Go to address / command palette |
| `Ctrl + Shift + ,` / `Ctrl + Shift + \` | Settings / toggle split view |
| `Ctrl + Shift + C` / `Ctrl + Shift + V` (or `Shift + Insert`) | Copy / paste |
| `Shift + Page Up` / `Shift + Page Down` | Scroll back through earlier output |
| `Tab` or `→` | Take the suggested command |
