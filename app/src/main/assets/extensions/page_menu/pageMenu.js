/* This Source Code Form is part of RemBrowse. */

"use strict";

/* global browser */

// The app's end of the channel (the session's WebExtensionController.setMessageDelegate).
const NATIVE_APP = "rembrowse_page_menu";

// GeckoView shows nothing for a right click on a page's background or text: it reports links,
// pictures and media to the app itself, and nothing else. So the page's own menu (Back, Reload
// and so on) is asked for here, for a right click that nobody else took:
// - not the page, which shows a menu of its own by cancelling the event;
// - not GeckoView, which cancels it when it reports a link or picture, or shows the selection
//   toolbar for selected text.
// Both answer while the event is still being dispatched, so it is looked at once that is over.
// A long press on a touchscreen ("touch") selects text instead, as on any Android browser.
function isEditable(event) {
  const target = event.composedPath()[0];
  if (!target || target.nodeType !== Node.ELEMENT_NODE) {
    return false;
  }
  return target.isContentEditable || !!target.closest("input, textarea, select");
}

window.addEventListener("contextmenu", event => {
  if (!event.isTrusted) {
    return;
  }
  if (event.pointerType && event.pointerType !== "mouse") {
    return;
  }
  if (isEditable(event)) {
    return;
  }
  setTimeout(() => {
    if (event.defaultPrevented) {
      return;
    }
    browser.runtime.sendNativeMessage(NATIVE_APP, { type: "pageMenu" }).catch(() => {});
  }, 0);
});

// A Ctrl+click or a middle click on a link opens it in a new tab in the background, and with
// Shift as well in a new tab in front, as on a desktop. GeckoView does nothing with such a
// click: Gecko leaves it to the browser, and desktop Firefox opens the tab from its own front
// end, which GeckoView does not have. So it is asked for here, for a click the page left alone.
// A page that handles the click itself (a web app's own Ctrl+click) cancels it, and keeps it.
// The listeners capture, so a page that only stops the click from bubbling (a table row with
// its own click handler) still gets the tab, as in Firefox. A link inside a closed shadow root
// is out of reach: the event's path leaves it out.
const XLINK_NS = "http://www.w3.org/1999/xlink";

// The link the click landed on, inside an open shadow tree too. Found while the event is being
// dispatched: its path is gone once that is over.
function clickedLink(event) {
  for (const node of event.composedPath()) {
    if (node.nodeType === Node.ELEMENT_NODE && node.matches("a[*|href], area[href]")) {
      return node;
    }
  }
  return null;
}

// The link's address as it is now (a page may rewrite it while the click is handled). An HTML
// link resolves it itself, in the page's own encoding; an SVG link's href is not a string.
function linkAddress(link) {
  if (typeof link.href === "string") {
    return link.href;
  }
  const href = link.getAttribute("href") ?? link.getAttributeNS(XLINK_NS, "href");
  if (href === null) {
    return null;
  }
  try {
    return new URL(href, link.baseURI).href;
  } catch (e) {
    return null;
  }
}

function openInNewTab(event) {
  if (!event.isTrusted) {
    return;
  }
  const link = clickedLink(event);
  if (!link) {
    return;
  }
  const foreground = event.shiftKey;
  setTimeout(() => {
    if (event.defaultPrevented) {
      return;
    }
    const url = linkAddress(link);
    // Only web pages: not script, and not data:, file: or the browser's own pages
    if (!url || !/^https?:/i.test(url)) {
      return;
    }
    browser.runtime.sendNativeMessage(NATIVE_APP, { type: "openLink", url, foreground }).catch(() => {});
  }, 0);
}

// Alt+click is left alone: on a desktop it saves the link instead
window.addEventListener("click", event => {
  if (event.button === 0 && event.ctrlKey && !event.altKey) {
    openInNewTab(event);
  }
}, { capture: true });

window.addEventListener("auxclick", event => {
  if (event.button === 1 && !event.altKey) {
    openInNewTab(event);
  }
}, { capture: true });
