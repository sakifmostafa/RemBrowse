# Changelog

All notable changes to **RemBrowse** (`com.rembrowser.rembrowse`) are documented in this file.

## Unreleased

### Fixed
* **The active tab is always in sight on the rail.** On a short window, such as a phone held sideways, five pinned sites could fill the rail on their own, so no open tab showed until you scrolled. The rail now scrolls to the active tab when you open the window, and again whenever the window or the rail's extension buttons leave it out of sight. It also follows you to another tab: one you choose with `Ctrl+Tab`, the tab switcher or the command palette, and a new tab at the end of a long list. It moves no further than it has to, so the pinned sites stay in view when there is room for them. A list you scroll by hand stays where you leave it.
* **No stray dot at the end of the loading line.** While a page loaded on a phone (or any page with square corners), a small accent-coloured dot sat at the top right of the window, Material 3's "end of the track" mark. The loading line is a plain line again.

### Changed
* **Notices look like Android's own.** The notices over the bottom of the window (a download started, finished or failed, a new version, a screenshot copied) are now Material 3 snackbars: dark in the light theme and light in the dark theme, so they stand out from any page, with the button (**Open**, **Show**, **Update**) in the accent's matching tone. Each is as wide as what it says, as before, so on a wide window or in split view it covers no more of the page than it needs. They come and go as before: a download's notice and a screenshot's leave by themselves, the update's stays until answered. A failure is still told by its icon and its words, not by colour.
* **Motion as in current Android apps.** The find bar, the permission card, the pinned sites' open dot, the swipe arrow, the screenshot overlay and the print preview move with springs instead of fixed-length curves, so they settle naturally and can be interrupted midway without a jump. They are Material 3 Expressive's springs, the ones Material's own switches, menus and buttons now use as well: things that move or grow overshoot a touch and settle, fades and colour changes never do. With **Remove animations** on (Android's Accessibility settings), the find bar now appears and leaves at once too, like the rest, and so do Material's own switches and menus; turning the setting on or off takes effect straight away, without reopening anything.
* **Light or dark, as your device is set.** **Settings → Appearance → Theme** now offers **System**, **Light** and **Dark** instead of the dark theme switch, as one row of Material 3 Expressive buttons (a connected button group): the chosen one is filled with the accent, fully round and ticked. With System, RemBrowse turns light or dark when the device does, at once, without restarting. **New installs start on System** instead of always dark; when you update, your current choice is kept (Dark if the switch was on, Light if it was off).
* **A launch screen in the toolbar's colour.** RemBrowse opens on a launch screen with its icon on the toolbar's colour. On Android 12 and later it is light or dark as chosen in Settings, even when that differs from the device, so there is no flash of the other colour, and the text selection toolbar over a page, autofill and other pieces Android draws follow the same theme. On Android 8 to 11, Android can't be told the app's theme before it starts: the launch screen stays dark, as in earlier versions, and with the light theme the browser turns light as it opens; the text selection toolbar over a page follows the theme there too.
* **Android's Back gesture shows where it goes (predictive back, Android 13 and later).** Swipe slowly from the edge: with nothing left to go back to on a phone, Android's preview of the home screen plays and you can still change your mind; with the tab switcher open, it shrinks under your finger and closes when you let go, or stays if you move back. What Back closes first is unchanged: the on-screen keyboard, then the screenshot overlay, a fullscreen video, the tab switcher, the find bar, a tab a link opened (back to the page that opened it), then the page's own history. On a laptop, Back with nothing to go back to still does nothing. Esc closes the find bar and the screenshot overlay in one press, as before. On a keyboard whose Esc key arrives as Back, that key now goes through Android's Back like the gesture: while the on-screen keyboard or Gboard's toolbar is up, its first press may only put that away, and the second closes the find bar. Still to be checked on a Googlebook.
* **A new About line:** "A private, native browser made for Googlebook, where SSH is just another tab." (Settings → About, the README, the site and the GitHub description.)
* **Built on current Material 3** (Material 3 1.5, Compose 1.13). Menus, switches and loading spinners move the way current Android apps do. Tooltips on the tab rail open beside the rail, so they no longer cover the tab above. Keyboard focus moves between the toolbar, pages and terminals as before: the newer Compose handling of it stays off until it has been tried on a Googlebook. Touchpad gestures (two-finger scrolling and swipes, pinch) now always go through Compose's own touchpad handling, which Compose 1.13 no longer lets an app turn off; RemBrowse's swipe back and forward and its touchpad clicks read the touchpad's events as before, but this is still to be tried on a Googlebook. In split view, a two-finger scroll or pinch on a pane still makes it the active one, as a click does, so what you type next, the address bar, `Ctrl+R` and `Alt+←` go to the page you just scrolled. Two-finger scrolling in the tab rail, Settings and dialogs follows the fingers from their first movement, where it used to start only once they had gone a tenth of an inch. During a two-finger scroll a page sees the mouse where the cursor is, as before, even when something on screen changes size midway.
* **The window's header matches the toolbar in a desktop window** (a Googlebook, or desktop windowing on a tablet). The strip with the minimise, maximise and close buttons now shows the toolbar's colour instead of a colour of its own, with its buttons dark in the light theme and light in the dark theme. Checked on an Android 16 tablet emulator in desktop mode; still to be confirmed on a Googlebook.
* **A roomier toolbar on phones.** On a phone-sized window the address bar is 48dp tall (was 40dp) and the toolbar 56dp (was 52dp), so the address bar is a full-size touch target. Tablets and laptops keep the desktop sizes.
* **Larger text (Settings → Display → Font size) fits.** At 200% the letters on the tab rail's tiles and the tab counter's number no longer spill out of their tiles: like the icons they stand in for, they keep their size. With very large text the More menu leaves out its key hints, so names such as "Take screenshot" stay on one line (the keys are in **Keyboard shortcuts**). From 150% the rows of every menu also leave out the extra space above and below their names, so they stay as tall as at 100% and the More menu fits below the toolbar on a laptop at 200%. A menu that is still too long for the room below its button, as on a phone, opens below the toolbar anyway and scrolls, instead of moving up over the toolbar and the **More** button.
* **Screen readers announce headings** for the Settings sections and their groups ("Theme color", "Page rendering"…), dialog titles and the tab switcher's tab count, so you can jump between them.

* **Menus in Material 3 Expressive's style.** Every menu's rows (More, Split, the tab rail's and the tab switcher's, History, bookmarks, the address bar's, the page's right-click menu and the terminal's) are set slightly in from the menu's edges with rounded corners for the pointer's highlight and the keyboard's focus ring. Rows that are on or off, or one of a set, are drawn as such: **More → Desktop site** while on, the **Split** menu's current size and layout, and the current theme in the terminal's theme menu are filled with the soft accent colour, with rounder corners and a tick. Screen readers announce Desktop site as a checkbox and the split's sizes, layouts and the terminal themes as radio buttons, with their state.

* **The terminal's font and cursor, and a host's sign-in, are button rows.** **Settings → Terminal → Appearance → Font** and **Cursor**, and **Sign in with** (Password or Key) when adding or editing a host, are connected button groups like the theme's, the chosen one filled and ticked; when the names don't fit beside each other (a phone, or large text), the theme's, the font's, the cursor's and the sign-in's buttons come apart into separate round buttons that go on to a second line, instead of a joined row broken in two with square corners at the break, or running off the side of the page.

* **Settings in grouped rows.** Settings that go together (the startup choice, where new tabs open, the search engine, page rendering, the privacy switches, the terminal's suggestions…) are now shown as Material 3 Expressive's grouped lists: each setting on its own rounded tile, 2dp from the next, the group rounded at its top and bottom, instead of rows between dividers. The chosen one of a set of choices is filled and rounder besides its radio button; a switch's row stays as it is and the switch shows whether it is on. The whole tile is still the touch target, and a finger flipping a switch still gets the light tap.

* **Loading and progress in Material 3 Expressive's style.** A tab that is loading shows Material's loading indicator in the rail (shapes that morph into one another, in the accent colour) instead of a spinning ring, as do the add-on catalogue and the print preview while they load. With Android's animations turned off (**Remove animations**), it holds still: an hourglass in the accent colour in its place (the print preview says "Loading…" instead, as before). A download in progress and a RemBrowse update being downloaded show a wavy line that ripples while the file comes in and flattens as it nears the end. The loading line across the top of a page stays a plain thin line.

### Added
* **An expanded tab rail, with tab titles.** The menu button (☰) now at the top of the tab rail widens it to show each tab's title beside its icon, and the names of the pinned sites, the extensions, **Tab switcher** and **New tab**, as Android's newer apps do with Material 3's expanded navigation rail; select it again to go back to the narrow strip of icons. **Expand tab rail** and **Collapse tab rail** in the command palette do the same. On a wide window (a laptop's, or a tablet held sideways) the page moves over to make room and RemBrowse remembers the choice, also in **Settings → Tabs → Show tab titles**. On a narrower window, such as a phone held sideways, it opens over the dimmed page for a moment instead, and a click outside it, Esc, Back or choosing a tab closes it. Like a dialog, it then keeps the keyboard (`Tab` and `Shift+Tab` go round it, never into the dimmed page) and screen readers (which find the rail and **Close the tab rail**, not the page under it); in the dark theme a thin outline marks its edge and the page is dimmed more, so it no longer blends into the dark toolbar. Everything on the rail works as before when it is expanded: right-click and long-press menus, middle click, dragging tabs onto the pages, the pinned sites' open dots, the extensions' badges and the keyboard's focus ring. The rail is collapsed until you expand it. On a short window the tab switcher button now scrolls with the tabs, so they keep their room under the new menu button.
* **Colours from your wallpaper (Material You), if you want them.** On Android 12 and later, **Settings → Appearance → Use system colors** takes the toolbar, tabs, buttons and highlights from your wallpaper's colours, in light and dark. It is off by default: maroon and the other five theme colours stay RemBrowse's own look, and while it is on they are shown dimmed and kept for when you turn it off. Contrast is checked the same way as for the built-in themes.
* **A focus ring for the keyboard.** `Tab` and `Shift+Tab` move through the toolbar, the tabs in the rail, the tab switcher, Settings, history, bookmarks, the terminal's pages and dialog choices, and the one with the focus gets Material 3's focus ring, as on the web's `:focus-visible`: two thin lines just inside its edge, the accent colour outside and a contrasting one inside, so it shows on any background, a selected (accent-filled) tile included. Material's own buttons, switches, checkboxes and menus draw the same ring. Controls with an outline of their own (the **Split** button, the rail's tabs and buttons, the tab switcher's cards, the Settings sections, the command palette's rows and the terminal's theme cards) get a 2dp accent ring just outside the outline instead, with a small gap, so the outline never hides half of it, and a control already outlined in the accent (a split open, the active tab, the section shown, the highlighted command) still changes clearly when it takes the focus. It shows only while you are using the keyboard (touching the screen hides it), and nothing moves when it appears.
* **RemBrowse's shortcuts in Android's Keyboard Shortcuts sheet** (`Meta+/`), in five groups: Tabs, Navigation, Page, Split view and RemBrowse. When a keyboard is attached it also opens from **More → Keyboard shortcuts** and from **Keyboard shortcuts** in the command palette (`keys` or `help` find it too). While a terminal has the keyboard it lists the keys that work there, `Ctrl+Shift+L`, `Ctrl+Shift+G`, `Ctrl+Shift+K`, `Ctrl+Shift+,` and `Ctrl+Shift+\` included; the shell keeps the rest (`Ctrl+R`, `Ctrl+L`, `Alt+←`…).
* **A mouse's Forward button goes forward**, as `Alt+→` does.
* **Middle-click and more right-click menus.** A mouse's middle button closes a tab on the rail or a card in the tab switcher, and opens a pinned site, a bookmark or a page in History in a new tab, as in a desktop browser. The tab switcher's cards now have the rail's menu (**Close tab**, **Close other tabs**), the bookmarks list **Open**, **Open in new tab** and **Remove bookmark**, and History **Open** and **Remove from history**; on a touchscreen a long press opens the same menus.
* **A three-finger tap on the touchpad is a middle click** in these places too: Android sends that tap as the middle button, so on a Googlebook it closes the tab or card under the pointer, and opens a pinned site, a bookmark or a page in History in a new tab, as Chrome does on a Chromebook. Other touchpad clicks and taps behave as before.
* **A light tap you can feel on a touchscreen** when you flip a switch in Settings (and Match case in the find bar), long-press a card in the tab switcher, a bookmark or a page in History, pick up a tab to drag it and drop it on a page, or double-tap a divider to even out a split. It is meant for fingers: a mouse, the touchpad and the keyboard stay silent, except that a click held on a tab in the rail until its menu opens plays the same tap as a long press, and a pen held on a card, a bookmark or a page in History gets it like a finger. Devices without a vibration motor feel nothing.
* **Icons in the More menu**, the same ones the command palette shows for each command, so the two read alike.

### Build
* **Current Android build tools.** Android Gradle Plugin 9.5.1 (was 8.7.3), which now compiles the Kotlin code itself, Gradle 9.9.1 (was 8.11.1), Kotlin 2.4.21 (was 2.1.20), compiled against Android SDK 37.1 (was 35), which Compose 1.13 requires. A build machine needs SDK platform 37.1 and build tools 36.0.0; see the Build & Test Guide. Release builds are still shrink-only: unused code is removed, nothing is renamed, optimised or made public. AGP 9 offers its standard keep rules only together with a setting that lets R8 make private code public, so the build carries its own copy of them without it (`app/proguard-defaults.pro`).
* **Libraries:** Compose BOM 2026.11.01 (Material 3 1.5.0, Compose 1.13.0; was 2026.06.01), activity-compose 1.13.0 (was 1.9.3), current with Compose 1.13, core-ktx 1.19.1 (was 1.15.0), lifecycle 2.10.0 (was 2.8.7; Compose 1.13 already brought it in), and Robolectric 4.18.1 for the tests (was 4.14.1, which stops at API level 35; 4.18.1 knows 36 and 37).
* **The release build checked on the new tools:** both APKs signed with the RemBrowse key as before (APK Signature Scheme v2, certificate SHA-256 `F1:A5:30:6A:…:B2:BE:06`), 0.5 MB larger each than 0.14.0 (85.1 MB arm64-v8a, 90.3 MB x86_64), all of it newer library and app code; the engine is unchanged, its `omni.ja` with the `proxy.settings` patch byte for byte as in 0.14.0, and the resources are 30 KB smaller. A release build without the key or with another one still stops with the same errors.
* **Targets Android 16 (targetSdk 36, was 35).** Nothing changes in use, because RemBrowse already did what Android 16 now requires of apps that target it: it draws edge to edge (the opt-out it no longer allows was never used), it had already opted in to predictive back (above), so on Android 13 and later Back already reached it through its Back handlers rather than as a key, and its window was already resizable with no fixed orientation or aspect ratio (on tablets and laptops Android 16 now ignores those anyway). None of the other Android 16 changes for such apps apply: no `elegantTextHeight`, no `scheduleAtFixedRate`, no body sensors, no `MediaStore` version, no Bluetooth; GeckoView's engine processes are ordinary services, and there is no foreground service. Android 16's stricter intent matching is opt-in and not turned on, and its local network permission is not enforced yet (when it is, the terminal's connections to servers on the local network and pages on local addresses will need it).
* **Lint runs every Compose check again.** The eight checks that had to be off because the old lint crashed on them are back, among them the ones that catch state created without `remember` and coroutines started during composition, and Compose UI's own checks, which the old lint skipped, run too. What they found is fixed: the print preview takes the language from the configuration, so it follows a change of device language, and from Android 15 Go to address measures the window itself instead of reading the configuration's screen size. Up to Android 14 it keeps the configuration's size, which leaves out the status and navigation bars: measured with them it offered one or two suggestion rows more than fit, and in a window just under 600dp wide it could take its wide form where it used to take its narrow one.

## v0.14.0 (2026-11-23) — Pasting pictures into Telegram

### Fixed
* **A screenshot or copied picture pasted into Telegram is attached.** With a picture on the clipboard, `Ctrl+V` in Telegram Web's message box did nothing. Telegram reads pasted files with `webkitGetAsEntry()`, which the engine leaves out on Android (Chrome and desktop Firefox have it), so its paste code stopped with an error after cancelling the normal paste. RemBrowse now turns it on, and the picture is attached as in Chrome. Other sites that read pasted files the same way work too.

### Changed
* **RemBrowse has its own address: [rembrowse.com](https://rembrowse.com/),** with the download page, and **updates come from [GitHub Releases](https://github.com/sakifmostafa/RemBrowse/releases)**, where every release's APKs and `update.json` are attached. browse.example.com is gone. Copies of 0.13.19 and older still look for updates there, so install 0.14.0 once by hand; after that, updates arrive as before. The APKs are no longer committed to the repository.

### Added
* **Every release is scanned by VirusTotal.** Both APKs are checked by about 60 antivirus engines before they go out; the result and a link to the full report are shown under each download on [rembrowse.com](https://rembrowse.com/) and in the GitHub release. 0.14.0: 0 of 63 engines flagged either file.

### Testing
1051 automated tests (72 of them screenshot renders that run only on request), including the engine setting on phones and large screens, with and without a mouse. On an Android tablet emulator, with a copy of Telegram's paste code: before, a screenshot pasted with `Ctrl+V` failed with `item.webkitGetAsEntry is not a function`; after, the PNG was attached (1218×686). Checked by an independent review against Firefox's source: only this paste API and `webkitEntries` on file inputs are added, no local paths are exposed, and folder upload stays off.

## v0.13.19 (2026-11-23) — Commands and servers from Go to address

### Added
* **Go to address (Ctrl+G) runs commands too.** Type `ter` or `terminal` and Enter opens a new terminal; `pri` or `pdf` prints, `screenshot` takes one, `set` opens Settings, and so on. When what you type is the start of a word in a command's name (three letters or more) or one of its keywords, and not an address, that command comes first; searching the web for the word is one row down. Two-letter words stay searches, and commands that close or rearrange things (Close tab, split view) are never put first, so typing "close" can't close your tab.
* **Connect to a saved server by name.** Typing the start of a saved SSH host's name (or `ssh`) puts **Connect to** it first, from Go to address or the command palette; a match with its user or address is listed below the search. An address such as 192.168.1.1 or localhost still opens the web page first.

### Changed
* **The command palette (Ctrl+K) ranks the same way**, so `ter` + Enter opens a terminal there too instead of searching the web.

### Testing
1050 automated tests (72 of them screenshot renders that run only on request), including the ranking against the real command list (close, split, web, search, ter, pdf, to, in…), keywords, text with spaces, addresses and localhost, and saved hosts (root, home, master, ssh), Ctrl+G and the palette showing the same order, and Enter on a host connecting. On an Android tablet emulator: Ctrl+G, `ter` showed New terminal first and highlighted, and Enter opened a terminal. Checked by an independent review; its findings were fixed.

## v0.13.18 (2026-11-23) — Screenshots to the clipboard

### Added
* **Ctrl+Alt+S takes a screenshot** and puts it on the clipboard, ready to paste into a chat. The window dims and the pointer becomes a crosshair: drag around any part of it (pages, toolbar, tabs) or click a page to capture all of it; Esc cancels. A small notice with a thumbnail confirms the copy. Paste it with Ctrl+V into Telegram, WhatsApp or any page in RemBrowse, or into another app. Also **More → Take screenshot**, the command palette and the page's right-click menu. Only RemBrowse's own window is captured, and only the latest screenshot is kept on the device.

### Testing
1028 automated tests (72 of them screenshot renders that run only on request), including the selection geometry, the shortcut, the clipboard file and its type, the overlay's states, the menus, and contrast of the selection over any page. On an Android tablet emulator: a dragged area was copied at exactly its size, a click captured a whole page in both the Standard and the Direct page-rendering modes (the page's real pixels, not a black hole), the More menu's button opened the capture, and Ctrl+V into a page in RemBrowse delivered the picture as a PNG file the page could show. Checked by an independent review; its findings were fixed.

## v0.13.17 (2026-11-23) — Site permissions, notifications and a clearer tab rail

### Added
* **Sites can ask for your camera, microphone, location and notifications.** Until now RemBrowse turned every such request down without asking, so Telegram couldn't record a voice message or notify you, and video calls and maps couldn't work. A card now drops in at the top of the site's tab, in RemBrowse's style: "**web.telegram.org** wants to Show notifications", with **Block**, **Allow** and, for the camera, microphone and location, **Allow this time** (until the tab leaves the site). Allow and Block are remembered. Protected (DRM) video and cookies for embedded sites ask the same way. The first time, Android also asks whether RemBrowse may use the device.
* **Notifications from sites** appear as Android notifications with the site's name. Tapping one opens that site's tab and lets the page react (Telegram opens the chat); swiping it away tells the page.
* **Settings → Site permissions** lists every site you answered, with a switch per decision, **×** to forget one and **Reset** per site. Blocking a camera or microphone there stops a page that is using it. A notice says when Android's notifications are off for RemBrowse.

### Changed
* **Pinned sites and open tabs no longer look alike.** Your pinned sites (the first six bookmarks) now sit together on a soft shelf at the top of the tab rail, with slightly smaller icons, and a small dot marks the ones open in a tab. Open tabs below each have their own tile; a tab shown in a split pane is a step brighter and the focused tab is brightest, with its marker. A pinned Telegram bookmark could pass for a second Telegram tab before.
* **A pinned site takes you to its open tab** instead of opening another copy each time: to a tab on the bookmarked page or a page under it (preferring one already on screen in split view), or back to the bookmarked page when you are already in that tab elsewhere on it. Only when none is open does it open the site. **Open in new tab** in its menu still gives you another. The bookmarks list works the same way.

### Fixed
* **Sites stayed blocked from before.** Earlier versions' refusals were stored by the engine as permanent "no"s; they are cleared once, so sites ask properly.

### Security
* The card names the site in the address bar, even when the request comes from something embedded in the page, and a decision belongs to that site: a site you allowed can't be embedded in another site's page to use your camera unasked.
* Site names are shown no more readably than the address bar shows them, so a name made of look-alike characters can't pose as another site in the card.
* The card's buttons ignore taps for half a second after it appears or moves, so a click meant for the page can't answer it.
* Notification icons are not fetched from your own device or home network, and oversized pictures are refused before they are decoded.

### Known limits
* Blocking location doesn't stop a page that is already following it until it reloads.
* A blocked site can still bring up Android's own camera or microphone question once, because GeckoView asks Android first.

### Testing
969 automated tests (63 of them screenshot renders that run only on request), including every answer and when it ends, whose decision a request is, the card's text and its half-second guard, Settings, the notification text, icon limits and the tap and swipe routing. On an Android tablet emulator: notifications (the card, Android's question, a real notification, tapping it reaches the page), the microphone (allowed, then granted without asking), location (blocked and remembered, forgotten in Settings and asked again, allowed this time), DRM (Widevine granted), and a page embedding an allowed site: the card asked for the page's own site, and Block refused the frame. The new rail: which tab a pinned site or bookmark matches (a page under it on a "/" boundary; a bookmark with a query, such as one YouTube video, only that page), what a click does in each case, the open dot, and its colours and labels in every theme; on the emulator, pins jumped to their open tabs, took a tab back to the pinned page and opened no extra tabs. Checked by independent reviews; their findings were fixed.

## v0.13.16 (2026-11-22) — Telegram chats that never loaded

### Fixed
* **A Telegram chat could load forever** (a spinner where the messages should be) with Telegram open in more than one tab. Telegram Web A keeps its connection in one tab, in a background worker, and the other tabs ask that tab for everything. Gecko on Android (unlike desktop Firefox) puts a background tab's page to sleep: its timers stop and its workers' replies are held until the tab is shown again. So the tab on screen waited for an answer that never came. Background tabs now keep running, as in desktop Firefox: their timers fire at most once a second, while workers and network carry on normally. Other sites that share work between their tabs (chat and mail apps, editors) benefit too.

### Changed
* **Pages keep running while RemBrowse is minimised**, as long as it is the last app you used, so a chat keeps receiving messages. Once you use other apps, Android freezes RemBrowse until you come back. This can use a little more battery with many busy tabs open.

### Known issue
* **If Android closes the Telegram tab that holds the connection** (to free memory), the other Telegram tabs wait for it again. Show that tab, or reload Telegram, to bring it back. Telegram only hands the connection over when a tab is closed normally.

### Testing
736 automated tests (50 of them screenshot renders that run only on request), including that background tabs are kept running on phones and large screens alike. On an Android tablet emulator, a test page that works like Telegram (a background tab answering through its worker, the tab on screen sending it a request every second) got no answer to any of 54 requests before the change, and an answer to all 75 within 3 ms after it. Also on the emulator: with RemBrowse minimised the page kept running while it was the last app used, and all of RemBrowse's processes were frozen once other apps were opened. Telegram itself was not signed in on the emulator. Checked by an independent review.

## v0.13.15 (2026-11-22) — A print screen of its own, filled-in PDF forms print, Esc closes Find

### Added
* **RemBrowse's own print screen.** Print (Ctrl+P, the **…** menu, the page's right-click menu, or a page's own Print button) now opens a RemBrowse screen instead of Android's print dialog straight away: the pages as they will print, the document's name, page count and paper size, and a file name you can change. **Save as PDF** puts the file in Downloads with no further dialog, listed in RemBrowse's downloads like any other. **Print…** sends it to a printer through Android's dialog. On a wide window the pages sit beside the options; on a narrow one above them.

### Fixed
* **A filled-in PDF form printed blank.** Printing a PDF laid out RemBrowse's PDF viewer again, which lost what was typed into the form. A PDF now prints from the viewer's own copy of the document, with the form as you filled it in (the same copy its Download button saves), at full quality.
* **A page that prints and then closes itself** (a receipt or a boarding pass that calls print and closes) keeps its print screen open until you are done with it.
* **Esc did not close the find bar (Ctrl+F) on a laptop keyboard.** The keyboard app or Android could keep Esc (or turn it into Back) before the find bar saw it, and after a search the focus could be on the page instead. Esc is now read before the keyboard app: with the find bar open it closes it, wherever the focus is, and puts you back on the page (a terminal keeps its own Esc). Back closes the find bar too, before it goes back in the page, as in Chrome.
* **A page cannot keep opening the print screen.** While the screen is open, and for a few seconds after it closes, a page's own print requests are ignored; Print from the menu or Ctrl+P always works.

### Known issue
* **The buttons inside a PDF form (such as "Print Form" or "Clear Form") do nothing.** The PDF viewer built into GeckoView does not run the form's own scripts. Use Ctrl+P or **… → Print…** instead, which prints the form as filled in.

### Testing
735 automated tests (50 of them screenshot renders that run only on request), including who owns the laid-out PDF in every case (saved, sent to a printer, cancelled, replaced, the app closing), what a page may and may not do with the print screen, the saved file's name and bytes, and the screen itself (its actions, its first focus, and that a 200-page document renders only the pages in view). Tried on an Android tablet emulator with the Ontario parking permit form filled in: the preview showed the typed values, Save as PDF wrote the file to Downloads, a page's Print button opened the screen, and Print… opened Android's printer dialog. Checked by an independent review.

## v0.13.14 (2026-11-22) — Printing, Ctrl+click opens a new tab

### Fixed
* **Printing did nothing.** A page's own Print button (such as "Print Form" in a PDF form) was ignored: GeckoView's built-in printing only works once the app sets it up, and RemBrowse never had. Pages now print through Android's print dialog, which can also save the page as a PDF, named after the page's title. If a page can't be printed, a message says so.
* **Ctrl+click on a link did nothing.** Gecko leaves Ctrl+click and middle click to the browser, and desktop Firefox opens the tab from its own front end, which RemBrowse did not have. Now Ctrl+click or a middle click opens the link in a new tab in the background, and Ctrl+Shift+click opens it in front, as on a desktop. A web app that handles Ctrl+click itself keeps doing so.

### Added
* **Print… (Ctrl+P)** in the **…** menu, the page's right-click menu and the command palette (Ctrl+K). It is greyed out on RemBrowse's own pages and in terminals.

### Testing
716 automated tests (50 of them screenshot renders that run only on request), including which Ctrl+click and middle-click messages may open a tab (web addresses only, never script, `data:`, `file:` or the browser's own pages), where the new tab opens, Ctrl+P and the Print rows, and the print dialog's document (its name, and the PDF handed over byte for byte, then deleted). Both changes were checked by an independent review. Neither could be tried on a device here.

## v0.13.13 (2026-11-22) — Ctrl+A typing in chat boxes, Back on X

### Fixed
* **Back skipped pages on sites like X.** Back from a post on X went to the site you were on before X, skipping X's home feed. Gecko's Back skips pages it thinks you never interacted with, and on sites that change pages without reloading it gets that wrong. RemBrowse turns that skipping off, so Back (the arrow, Alt+Left, the swipe) always goes to the page you were on before. A site that adds extra history entries can now take an extra Back to leave.
* **After Ctrl+A, typing put the cursor before the new letter in web chat boxes such as Telegram's.** Found by reading GeckoView's and Gecko's source: Gecko counts a hidden line break at the start of every paragraph, and these editors keep their text in a paragraph. GeckoView typed the letter over the selection and then set the cursor by a position counted in the old text, which landed one place too early, in front of the letter, so the next letters went in front of it. RemBrowse now types over a selection without naming a cursor position, and the page puts the cursor after the letter itself. The key test page (browse.example.com/keys.html) has a new box 3 that works like these editors, to check it without signing in anywhere.

### Testing
688 automated tests (50 of them screenshot renders that run only on request), including typing over a selection with a stand-in for GeckoView's input connection (the selection replaced, the caret left after the new text, GeckoView's own commit not used, and every other case passed on unchanged) and the startup settings read back by GeckoView's own parser. Both causes were traced through GeckoView's and Gecko's source and checked by an independent review; neither could be run on a device here.

## v0.13.12 (2026-11-22) — Swipes follow your fingers

### Fixed
* **Two-finger swipes went the wrong way with the touchpad's reverse scrolling.** Android reports a touchpad scroll as a finger moving the way the page scrolls, which is against the fingers when Settings → Touchpad → Reverse scrolling is on, so swiping left went back. RemBrowse now reads that setting and goes by your fingers: right is Back, left is Forward. **Settings → Tabs → Swipe the other way** flips it, in case it still feels backwards.

### Testing
679 automated tests (50 of them screenshot renders that run only on request), including swipes with the touchpad's reverse scrolling on and off and with the new switch, in every combination: Back and Forward follow the fingers while the page is still asked about the way it would actually scroll.

## v0.13.11 (2026-11-22) — Two-finger swipes, Back that works, terminal tabs

### Added
* **Two-finger swipes go back and forward.** On a touchpad, swipe two fingers right to go back and left to go forward, as in Chrome. A round arrow slides in from the side of the page and fills with the accent colour once letting go will navigate (or flick); lift early and nothing happens. It only takes a swipe the page has no use for: a wide table, a carousel or a map keeps it until it reaches its own edge, and up-and-down scrolling is never affected. In a tab a link opened, swiping back returns to the page it came from. On by default; **Settings → Tabs → Swipe with two fingers to go back** turns it off. (Three-finger swipes belong to Android and never reach apps.)

### Fixed
* **Ctrl+T and Ctrl+W did nothing in a terminal.** The terminal gave every plain Ctrl key to the shell, so opening and closing tabs took Ctrl+Shift+N and Ctrl+Shift+W there. Ctrl+T now opens a new tab and Ctrl+W closes the terminal's tab, as everywhere else. In the shell, delete the previous word with Alt+Backspace instead of Ctrl+W; Ctrl+R, Ctrl+L, Ctrl+D, Ctrl+C and the rest still belong to the shell.
* **Back did nothing after a link that opened a new tab, and a three-finger swipe then minimized RemBrowse.** Links that open in a new tab (`target=_blank`, which many sites use) open in the same pane, so the new tab had no history and the Back arrow was greyed out; the system's Back (a touchpad's three-finger swipe) then had nothing to do and Android sent the app away. Now Back in such a tab closes it and shows the page it came from, where it was, as Chrome does. And on a laptop (a mouse or touchpad connected), Back with nothing left to go back to does nothing instead of minimizing RemBrowse; phones keep Android's usual Back.

### Testing
667 automated tests (50 of them screenshot renders that run only on request), including the swipe logic with real touch events (taking a swipe only when the page leaves it, the page getting one cancel and nothing more, arming, flicks, late and stale page answers), the arrow, the setting, Back in a tab a link opened, and Ctrl+T and Ctrl+W in a terminal. Not testable here: whether the Googlebook's touchpad reports two-finger swipes as expected, and the three-finger Back, which need the device.

## v0.13.10 (2026-11-21) — Pages see a laptop pointer, not a phone

### Fixed
* **Sites treated the Googlebook as a phone or tablet.** Measured with a key test page (browse.example.com/keys.html) against Chrome on the same Googlebook: RemBrowse told pages the main pointer was a finger that can't hover (`pointer: coarse`, `hover: none`), where Chrome says a fine pointer that hovers. Some sites then behave as on a phone: a web chat box starts a new line on Enter instead of sending, for one, although the page receives a real Enter since 0.13.9. Now, with a touchpad or mouse connected when RemBrowse starts, pages are told the pointer is fine and hovers, as in Chrome; touch keeps working, and phones without a mouse are unchanged. Not yet confirmed on a device: after updating, keys.html's device lines should show `pointer: fine` and `hover: true`, and Enter should send in such a chat box. Typing after Ctrl+A in ProseMirror editors such as Telegram's may follow, since it worked in the test page's own box.

### Testing
625 automated tests (50 of them screenshot renders that run only on request), including the engine setting written with and without a touchpad and read back by GeckoView's own parser, and the Enter routing with real key events (Ctrl+Enter and numpad Enter included, repeats and releases following the press). Whether a touchpad is detected can't be simulated in the tests; the device check is keys.html.

## v0.13.9 (2026-11-21) — uBlock Origin built in, Enter sends in chat boxes, Ctrl+Shift+T for a terminal

### Changed
* **`Ctrl+Shift+T` opens a new terminal**, from a web page or from inside another terminal, as in terminal apps. It is shown next to **New terminal** in the More menu and the command palette. Inside a terminal, a new browser tab moves from `Ctrl+Shift+T` to `Ctrl+Shift+N`.
* **uBlock Origin is the built-in ad blocker**, instead of AdGuard AdBlocker. It is a smaller package (6.4 MB against 22.6 MB), blocks search ads that AdGuard lets through by default, and unmasks trackers that hide behind a site's own address. Where AdGuard was on, the update installs uBlock Origin once and switches AdGuard off; AdGuard stays installed with its settings, so it can be switched back on or removed in Settings → Extensions. Where blocking was off or AdGuard removed, nothing is installed. Not yet tried on a device: the move from AdGuard on an existing copy.

### Fixed
* **The text cursor (I-beam) showed over the terminal's cards and banners**, such as "Couldn't connect" and its buttons, the password and server-key questions, and the Connecting and Disconnected bars. They now show the normal pointer; the I-beam is only over the terminal's text. A pane divider's resize cursor also wins over a terminal beside it now, as it already did over web pages.
* **Enter in a web chat box added a new line instead of sending**, with a physical keyboard. 0.13.8 meant to send Enter to the page as a key, but checked first whether the page view was a text editor, which GeckoView never says, so it never did: the keyboard app turned Enter into a typed line break. Enter now goes to the page through the browser engine's own key path; a word still being typed is committed first. Chinese, Japanese and Korean keyboards keep Enter while composing. Not yet confirmed on a device.
* **After Ctrl+A in a rich text box, typing put one character in and the cursor before it** (web chat boxes such as Telegram's, with Gboard and a physical keyboard). Gboard starts each word as "being composed", and starting one over a selection made those editors rewrite the box. The selection is now deleted first, so the word starts at a plain cursor, as in an empty box; Gboard's autocorrect is kept. Not yet confirmed on a device.

### Testing
595 automated tests (50 of them screenshot renders that run only on request), including the move from AdGuard to uBlock Origin in every state the app can be in, the Ctrl+Shift+T keys in and out of a terminal, the cursor claim over terminal cards, and the order of calls when a word starts over selected text. Gecko itself can't run in the tests, so the Enter and typing fixes are covered up to the point they hand the key or text to GeckoView.

## v0.13.8 (2026-11-21) — Terminal: Tailscale SSH, and clearer connection errors

### Changed
* **Updates come from [browse.example.com](https://browse.example.com/)**, RemBrowse's own download site, instead of the GitHub repository, which is now private. Copies of 0.13.7 and older still look on GitHub and will not find this version: download it from the site once, and updates arrive as before from then on.

### Fixed
* **Servers that sign you in without a password or key, such as Tailscale SSH** (`tailscale up --ssh`), failed with "doesn't accept passwords". The terminal now asks the server first whether it needs anything, as OpenSSH's client does.
* **"Refused the connection" was shown for every connection failure.** Android reports a refused connection, an unreachable network and a connection blocked by a VPN or firewall the same way. The terminal now says which it was, points at the port when a server refuses one other than 22, and for a Tailscale address (100.64.0.0/10 or a `.ts.net` name) reminds you to check that Tailscale is connected and not excluding RemBrowse.

### Building
* **A release build now needs the signing key and its passwords** (`signing/keystore.properties` or `REMBROWSE_STORE_PASSWORD` / `REMBROWSE_KEY_PASSWORD`), and stops with an error when either is missing. Before, it fell back to a password written in the build file, or, with no keystore, signed with the machine's debug key, which makes an APK that can't update installed copies. `scripts/change-keystore-password.sh` changes the keystore password safely. Debug builds, tests and lint don't need the key.
* **The download site is in the repository**: the page (`site/`), its server (`deploy/site-host/`: nginx behind a Cloudflare Tunnel) and `scripts/publish-to-site.sh`, which checks a release (sizes, SHA-256, version, signing key) before putting the APKs up and `update.json` last. The page shows the current version, sizes and SHA-256 from `update.json`, suggests the file for the visitor's device, and follows the system's dark or light setting. Tried in Chromium at 360 to 1440 px wide, in both themes, in landscape and with reduced motion; not yet opened in RemBrowse itself or on a real phone.

### Testing
558 automated tests, including sign-in to an SSH server that needs no password or key, as Tailscale SSH does.

## v0.13.7 (2026-11-21) — SSH terminal tabs; selecting text with the touchpad, and a desktop address bar

### Fixed
* **"Sign in with Google" (and other sign-in popups) left a tab open that never closed.** A page that opens a window can close it again when it is done (`window.close()`), and RemBrowse ignored that. Such a tab now closes, and the page that opened it comes back in its place.
* **Enter started a new line instead of sending in chat boxes** (web chats and messaging sites) with a physical keyboard. The keyboard app kept the word being typed as unfinished text and turned Enter into a typed line break, which the page cannot tell from Shift+Enter. In a page's text box a physical Enter now reaches the page as the key it is, after the word is committed, so Enter sends and Shift+Enter starts a new line, as on a desktop. Chinese, Japanese and Korean keyboards keep Enter while composing.
* **Shortcuts did nothing while typing in a page's text box.** With a text box focused, keys reach the keyboard app (input method) before the browser, and one that handles physical keys can type them itself or hand them straight to the page, so `Ctrl+T` (and the other shortcuts) never got to RemBrowse. Shortcuts are now read before the keyboard app sees them.
* **Selecting text with the touchpad did nothing in RemBrowse's own fields.** In the address bar, the find bar, the command palette and the search fields, a touchpad double-click, triple-click or click-and-drag selected nothing. The interface took touchpad clicks for finger taps, the same mix-up 0.13.6 fixed for web pages. Touchpad clicks now count as mouse clicks across the whole window and in dialogs, so text selects as it does with a mouse. The pointer also gets the interface's hover highlights and resize cursors.
* **Resting two fingers mid-scroll on the tab rail could open a tab's menu**, as on web pages before 0.13.6. A touchpad scroll now has to move before anything sees it, everywhere in the window.
* **Dragging across the unfocused address bar started the selection at the beginning of the address.** Clicking into the bar still selects the whole address, but a press that drags now selects what it covers.

### Changed
* **Double-clicking a word in the address bar selects just that word**, stopping at dots, slashes and hyphens as desktop browsers do: `example` in `www.example.com`, `some` in `some-path`. It used to select `www.example.com` or `some-path` whole.

### Added
* **Go to address (`Ctrl+G`).** The address pops up in the middle of the window over a blur of the page, with the current address selected to type over, and suggestions from open tabs and history as you type. Enter goes there, Alt+Enter opens a new tab, Esc closes. Find next and previous stay on `F3`/`Shift+F3` and Enter in the find bar.
* **Copy image** in the right-click menu for pictures: the picture itself goes on the clipboard, ready to paste into other apps. It is fetched as the page loaded it, with the site's sign-in; WebP and AVIF become PNG.
* **Terminal tabs.** **More → New terminal** (or `rem://terminal`) opens an SSH terminal in a tab of its own: it sits in the tab rail, splits beside web pages, and keeps its connection while you work elsewhere. Pick a saved host or type `user@host`, `user@host:2222` or a whole `ssh user@host -p 2222` command.
  * **Hosts and keys in Settings → Terminal.** Save hosts with a password (remembered or asked each time) or a key. Generate an Ed25519 key and copy its public half for `authorized_keys`, or import an OpenSSH or PEM private key, passphrase and all. Passwords and private keys are encrypted with a key that never leaves Android's keystore.
  * **Servers are checked.** The first connection shows the server's `SHA256:` fingerprint to trust; a changed key is flagged before anything is sent. Trusted servers are listed in Settings, where they can be forgotten.
  * **A real terminal**: 256 and true colours, full-screen programs (`htop`, `vim`, `less`, `tmux`), mouse support for programs that ask for it, 5,000 lines of scrollback with the wheel or touchpad, selecting with drag and double-click, `Ctrl+Shift+C`/`V` to copy and paste, and a right-click menu. While a terminal has the keyboard the shell keeps `Ctrl` and `Alt`, and the browser's shortcuts move to `Ctrl+Shift`. Keyboard layouts that type characters with AltGr (`@`, `{`, `}` on a German keyboard) and accents typed with dead keys work as in any terminal, and a paste from an on-screen keyboard's clipboard goes in as a paste, not as lines typed one by one.
  * **Command suggestions.** As you type, dimmed text suggests how the command ends, with a small Tab key after it, from the commands you have run (most recent first) and a list of common ones; **Tab** or **→** takes it, and without a suggestion Tab is the shell's own completion. Nothing is suggested or remembered at a password prompt, a command typed with a leading space is never remembered, and both can be turned off.
  * **Make it yours**: seven themes (RemBrowse, Midnight Green, Dracula, Nord, Solarized Dark, Gruvbox Dark and the light Paper), JetBrains Mono, Fira Mono or the system font, a block, bar or underline cursor, and the text size, from the palette menu on the bar above the terminal or Settings → Terminal → Appearance.
* **Right-click menu in the address bar:** Cut, Copy, Paste, Paste and go, and Select all. It keeps the bar's selection and any address you were typing.

### Known limits
* Not yet tried on a device.
* Other text fields keep Android's word rules for double-click, and have no right-click menu yet.
* The terminal is SSH only (no local shell, Mosh or Telnet), with no port forwarding, SFTP or agent forwarding yet. Commands finished with the shell's own Tab completion or history are not remembered for suggestions.
* The Enter fix for chat boxes follows from how GeckoView and keyboard apps handle physical keys; it has not been tried on a Googlebook yet. AltGr characters and dead-key accents in the terminal could not be simulated in the automated tests.

### Testing
550 automated tests. The SSH client was also tried against a real OpenSSH 9.6 server: password and key sign-in (Ed25519 with a passphrase, RSA, and a key the app generated), the terminal type, size and resizing, and the shell exiting.

## v0.13.6 (2026-11-21) — Right-click menus, and a touchpad that behaves

### Fixed
* **Right-clicking with the touchpad did nothing on web pages.** Android reports a touchpad as a mouse whose pointer is a finger, and the engine (GeckoView) took every touchpad event for a finger on a touchscreen: a two-finger tap or a click in the pad's bottom-right corner reached the page as a plain tap, without its right button, so pages never saw a right click (at most a left click). Touchpad clicks now reach pages as the mouse clicks they are, right button included, and click-and-drag with the touchpad selects text instead of scrolling.
* **Scrolling with two fingers and pausing opened menus.** Two-finger scrolling reaches the engine as one pretend finger dragged across the page. Fingers that rested before moving far enough counted as a finger held still, and after half a second the engine treated it as a long press: a site's own right-click menu opened, or the word under the pointer was selected with the Copy / Select all toolbar. A small nudge counted as a tap and clicked whatever was under the pointer. A touchpad scroll now reaches the page only once the fingers have moved about 2.5 mm, and then scrolls (and glides on) as before; resting or nudging fingers do nothing.
* **A touchpad right click next to a split divider** started a resize instead of reaching the page, and could pick up a tab in the rail. Only the main button drags them now, whatever reports it.

### Added
* **Right-click menus on pages.** RemBrowse had no context menu at all: right-clicking a link or picture did nothing, because the engine leaves that menu to the browser. Right-click (or long-press on a touchscreen) a **link** for Open link in new tab (in the background, next to its page), Open link in split view, Copy link and Copy link text; a **picture, video or sound** for Open in new tab and Copy address; or **anywhere else on the page** for Back, Forward, Reload, Bookmark this page, Copy page address and Find in page. The menu opens where you clicked. Sites with their own right-click menus, such as Google Docs, keep theirs; selected text keeps its Copy toolbar.

### Known limits
* Not yet tried on a device. If a touchpad right click still does nothing, or scrolling feels late to start, please say so with the device and Android version.
* No page menu in text fields (use `Ctrl+C`/`Ctrl+X`/`Ctrl+V`), on `about:` pages, on addons.mozilla.org or in the PDF viewer, where extensions cannot run; links and pictures there still get their menu.
* With a menu open, the first click elsewhere only closes it.
* No **Save link** or **Save image** yet.

### Testing
386 automated tests.

## v0.13.5 (2026-11-21) — Updates show up at once

### Changed
* **Updates show up as soon as they are published.** GitHub keeps its copy of the update information for up to 5 minutes, so "Check for updates" could still say "up to date" just after a release. RemBrowse now asks GitHub for the latest published version directly and downloads the files of exactly that version. If GitHub cannot answer that question, the check works as before. This takes effect from the next update after 0.13.5.

### Testing
325 automated tests.

## v0.13.4 (2026-11-21) — Find in page

### Added
* **Find in page** (`Ctrl+F`, or More → Find in page, or the command palette). A bar drops into the top corner of the page you are searching (in a split, that page), highlights every match as you type and counts them ("3/12", or "No results"). Enter or `F3` for the next match, Shift+Enter or `Shift+F3` for the previous one, **Aa** to match case, Esc to close.

### Known limits
* Not yet tried on a device.

### Testing
323 automated tests.

## v0.13.3 (2026-11-21) — Smoother scrolling, lighter on memory and battery

### Fixed
* **Scrolling stuttered on busy pages such as the x.com feed.** For every frame of a page, the engine set its drawing surface up again from scratch: a new surface, a blocking round trip to the graphics process, and a forced extra frame, all on the app's main thread. RemBrowse now sets the surface up once, as the engine does on its own default path. This also saves work whenever a page animates.
* **A call in a background tab could freeze.** Switching to another tab paused the call's page; a page using the camera or microphone now keeps running wherever it is.

### Added
* **Settings → Appearance → Page rendering**, to compare on your device: **Standard** (the fix, the default), **Direct (experimental)**, which sends pages straight to the screen and may scroll more smoothly still but can flicker when a pane is resized or opens, and **As in 0.13.2 and earlier**. Open pages switch at once.

### Changed
* **Less memory for the engine.** Each of the engine's processes (main, web pages, extensions) used to unpack its own 13 MB copy of the engine's resource file into memory; it is now read in place from the app, as Firefox for Android does. Firefox's page-translation machinery, which RemBrowse doesn't offer, is switched off: its language detector took about 17 MB in every web page process (measured with `about:memory` on a Googlebook). Together, roughly 80 MB less with a few tabs open (estimated from those figures).
* **Pages nobody can see are paused.** The pages behind a fullscreen video, and every page while the RemBrowse window is minimised, are paused until they are seen again (as background tabs already were), saving CPU and battery. Pages playing sound keep running, and so does any page using the camera or microphone, such as a video call.
* **Smaller download.** About 1.3 MB less: Gecko's crash-report analyser (RemBrowse sends no crash reports) and unused resources are left out.

### Known limits
* A paused page (a chat or webmail in a minimised window, say) catches up when you come back to it, not before.
* Not yet tried on a device. If scrolling still stutters, try Direct; if pages ever stay blank, switch to "As in 0.13.2 and earlier" and tell us. `about:support` shows whether the graphics run on the GPU ("WebRender") or in software.
* The memory and battery savings are estimated from measurements and the engine's source; compare `about:memory` before and after to see them on your device.

### Testing
312 automated tests.

## v0.13.2 (2026-11-21) — The window comes back from fullscreen; One left, two right; a divider that follows the mouse

0.13.1 was built but never released; everything in it is part of this version.

### Fixed
* **The window stayed fullscreen after a page left fullscreen.** Once a page had gone fullscreen (a video, or any site's fullscreen button), RemBrowse never gave the window back its status bar and title bar, and on a Googlebook it never returned the window to its size. The whole browser then stayed fullscreen, and moving the pointer to the top of the screen slid the status bar and title bar over the address bar and the top of the tab rail. Leaving fullscreen now restores the window every time. (If 0.13 left your window like this, the title bar's restore button, or closing and reopening RemBrowse, puts it back.)
* **Resizing a split with a mouse or touchpad.** The divider could fall behind the pointer (at a steady speed, every other movement step was lost), wander off after reaching its limit, or stop responding after switching between side by side and stacked. It now stays under the pointer. The resize arrows show over the whole grip, not just its middle, and stay for the whole drag. Double-clicking the divider resets the split even if the mouse moves slightly, and only the main button drags it.
* "Last checked today at 9:23 a.m.." in Settings → About lost its second full stop.

### Added
* **One left, two right layout.** The Split menu (and `Ctrl+5`) offers one tall pane on the left with two stacked on the right, next to Side by side, Stacked and Grid. The page you are on takes the tall pane. Building the same shape by dragging a tab onto the bottom edge of the right-hand page is recognised as this layout too.

### Changed
* **Updates come from the repository's new name**, `sakifmostafa/RemBrowse` (it was `RemBrowser`). 0.13 asks the old name, which GitHub forwards, so updating from 0.13 works; from this version on, RemBrowse no longer depends on that forwarding.

### Known limits
* The first update that can be installed from inside the app: from 0.13, use **Settings → About → Check for updates**. Not yet tried on a device, nor are the divider and fullscreen fixes. If dragging a divider feels heavy on a large page, the cost of resizing the pages while dragging is the next thing to look at.

### Testing
292 automated tests.

## v0.13 (2026-11-20) — Layouts your way, fullscreen video, history and updates

### Added
* **Split layouts in any arrangement, built by dragging tabs.** Drag a tab from the rail onto the pages: near an edge of a page it splits that page (left, right, above or below), in the middle it shows the tab there. Up to four pages, in any arrangement, for example one tall page beside two stacked ones (a third page under YouTube is a drag to the bottom edge of YouTube). A highlight shows where the tab will land. With a mouse, press and drag; on touch, long-press then drag. Dragging a tab that is on screen moves it. Every split has its own divider, **Close pane** (in a tab's rail menu, or **Split → Close this pane**) removes a pane and keeps its tab, and the layout is restored on the next start. Side by side, Stacked and Grid stay as quick choices in the Split menu.
* **Browsing history.** **More → History** (`Ctrl+H`) opens **Settings → History**: the pages you have visited, newest first, grouped by day, with search, remove and **Clear history**. The command palette also suggests matching pages from it. **Keep browsing history** turns recording off. The start page, settings and extension pages are never recorded, and history stays on the device.
* **RemBrowse updates itself.** **Settings → About** can check for a new version, download the APK for your device's processor with progress and Cancel, and hand it to Android's installer, which asks you to confirm; tabs, bookmarks, extensions and settings are kept. A daily check at launch (on by default, can be turned off) offers a new version in a notice at the bottom of the window. A download is installed only if it is exactly the published file (size and SHA-256), is RemBrowse, is newer and is signed with the same key, so a wrongly signed build is explained rather than failing with "App not installed". Updates come from this repository, which has to be public for the check to work.

### Fixed
* **Fullscreen video stayed inside its pane.** A page's fullscreen button only filled the page's own area: in a split, the rail, toolbar and other page stayed on screen. RemBrowse now gives the page the whole window and hides Android's status and navigation bars. On Android 15 and newer, a desktop window (a Googlebook) that is not already full-screen is enlarged to the whole screen and restored afterwards. Back, Esc or the page's own button return to exactly the same layout.

### Known limits
* **First version with the update check.** 0.12 and earlier cannot update themselves: install 0.13 from the README once, and later versions can be installed from Settings → About.
* Not yet tried on a device: dragging tabs, fullscreen (in particular enlarging a Googlebook window) and installing an update.
* Showing another tab in a pane creates a new web view for it, so switching tabs in a pane could briefly flash.
* While a page is fullscreen, the pages behind it keep running.
* An older version opening a three-pane layout shows it as a grid.
* On a phone-sized window there is no rail to drag tabs from; layouts are still shown and panes can be closed from the menu.

### Testing
270 automated tests.

## v0.12 (2026-11-20) — Popups sized to their content

### Changed
* **Extension popups are sized to their content, as in desktop Firefox.** The popup was always 420 × 540: a popup made wider than that (Proton Pass) was shrunk to fit, with small text and empty space below it, and a narrower one (Proton VPN) had a band of empty background beside it. RemBrowse's built-in popup helper now measures what a popup written for desktop Firefox wants and the popup takes that size, up to desktop Firefox's 800 × 600 and within the window. The popup appears once its size is known (at most a moment later), so it does not jump. Popups made for phones keep the standard size.

### Known limits
* Measured with the page's own layout rather than Firefox's desktop panel code, which cannot be used on Android; not yet tried on a device.

### Testing
196 automated tests.

## v0.11 (2026-11-20) — VPN extensions, second attempt

### Fixed
* **Proton VPN still said "Not able to control the browser network settings".** 0.10 let it set the proxy, but two more things in the engine's proxy code only work on desktop. Reading the setting back, which Proton does to check that it is in control, looked up a preference Android builds of Firefox do not have, and failed. Setting it asked the enterprise-policy service, which Android builds do not include, and failed too. Both are now handled the way the engine handles them elsewhere: the preference falls back to its desktop default, and the policy check is skipped when there is no policy service. Running the engine's patched proxy code in a simulated Android environment, Proton's connect request now succeeds and the read-back reports it in control; with 0.10's code it fails exactly as it did on a device.
* **An extension could open its welcome page by itself after updating to 0.10** (AdGuard showed "Loading extension…"). Giving an installed extension the private-browsing permission restarts it, and some extensions greet a restart with a "thanks for installing" tab. Such tabs are no longer opened. (Extensions already updated by 0.10 keep the permission and will not restart again.)

### Known limits
* The VPN fix still needs a check on a device with Proton VPN.

### Testing
189 automated tests. The build applies and verifies all four changes to the engine's proxy code.

## v0.10 (2026-11-20) — Uploads, and VPN extensions

### Fixed
* **Upload buttons did nothing.** Choosing a file on Google Drive, Proton Drive, X or any other site never opened anything, because the engine's request for a file picker went unanswered. It now opens the system file picker, for one file or several as the page allows, filtered to the kinds of file the page accepts. The chosen files are copied into RemBrowse's private storage and handed to the page from there, since the engine cannot open files in shared storage directly; the copies are removed the next time RemBrowse starts. Folder uploads are not offered yet.
* **VPN extensions could not connect.** Extensions such as Proton VPN take control of the browser's proxy setting before they route anything, and the Android build of Firefox's engine refuses that outright ("Not able to control the browser network settings"; Mozilla bug 1725981). RemBrowse now ships the engine with that one refusal removed. The routing itself (`proxy.onRequest`) was already supported.
* **"Allow … in private windows" banners.** Extensions are now allowed in private browsing, which Firefox requires before an extension may change the proxy setting. RemBrowse has no private windows, so this exposes nothing; extensions installed earlier get the permission on the next start (they restart once).

### Known limits
* Neither fix has been tried on a device yet. The proxy change is in how the engine itself behaves on Android and needs a check with a real VPN extension.
* A very large upload is copied before the page receives it, which takes a moment and uses storage until RemBrowse restarts.

### Testing
189 automated tests. The build checks the engine patch itself: it fails if the code it replaces is not found exactly, and verifies that nothing else in the engine changed.

## v0.09 (2026-11-20) — Extension buttons on the rail, readable popups

### Changed
* **Extension buttons moved to the bottom of the tab rail.** They used to be text entries in the More menu. Each now shows the extension's own icon, which updates when the extension changes it (a VPN showing it is connected, for example), and its badge in the colours the extension chose. Select one to open its popup; right-click or long-press it for **Manage extensions**. With more than four extensions, or more than fit on a short window such as a phone in landscape, the last button lists the rest, so the tabs always keep their room. On a phone-sized window, where there is no rail, they stay in the More menu, now with their icons.
* **Popups open beside their button.** On windows with the rail, an extension's popup appears at the bottom-left next to the extension buttons, the way a desktop toolbar popup drops from its button, and the page behind it is no longer dimmed. The popup's header shows the extension's icon, and its button stays marked while the popup is open.

### Fixed
* **Extension popups were shrunk to under half size.** Popups written for desktop Firefox, such as Proton VPN's, usually leave out the viewport setting a phone browser relies on, so the engine laid them out 980 pixels wide and zoomed them out to fit the popup. v0.08's narrower layout width already covers this on tablets and laptops, but phones keep 980, and v0.08 depends on a startup setting being applied. RemBrowse now also includes a small built-in helper that gives such popups that viewport setting on every device, so they are laid out at the popup's own width at full size; a popup wider than that is scaled down to fit rather than scrolled sideways. Popups that already set it, and extension pages opened in a tab, are unchanged. The helper is part of the browser and is not listed among your extensions.

### Known limits
* **Uninstall 0.08 before installing 0.09.** These APKs are signed with a different key from earlier releases, so Android will not install them over an older copy. Uninstalling removes RemBrowse's tabs, bookmarks and extensions.
* The popup fix has been checked against the GeckoView 138 sources and by automated tests of the app, but not yet on a device. If the helper cannot start, popups open as they did before. (On a laptop, 0.08 already shows Proton VPN's popup at full size.)

### Testing
183 automated tests.

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
