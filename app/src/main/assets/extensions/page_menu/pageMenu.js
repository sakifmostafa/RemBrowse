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

// A page's own button for copying a picture (navigator.clipboard.write with a PNG, as in an
// image viewer's Copy button) fails in GeckoView: Android's clipboard takes a picture only as a
// content: address, and GeckoView's clipboard handles text, HTML and web custom formats only, so
// the engine refuses the write. So a write with a PNG is taken over here: the picture is sent to
// the app, which puts it on the clipboard as its own Copy image does, and the page's promise
// settles with the app's answer, so the site shows its own message for it. A write with text as
// well copies the picture alone. Every other write goes to the engine unchanged.
// As in the real API, only a write the user has just asked for (a click or a key press a moment
// ago) may change the clipboard; otherwise any page could replace what the user copied. That is
// checked as the page calls, and the copy starts here and then, so nothing the page is handed
// can start another. One picture is copied at a time: each can be large.
const PNG = "image/png";

// The largest picture sent to the app, which refuses bigger ones too
const MAX_IMAGE_BYTES = 20 * 1024 * 1024;

// The page's own objects. Everywhere else the script sees the page through Xrays: the real
// properties and methods, whatever the page has changed.
const page = window.wrappedJSObject;

// Taken now, before the page's scripts run: a page that replaces its Promise or DOMException
// later must not be handed what settles the copy
const PagePromise = page.Promise;
const PageDOMException = page.DOMException;

const NO_ACTIVATION = ["NotAllowedError", "Clipboard write was blocked due to lack of user activation."];
const STILL_COPYING = ["NotAllowedError", "A picture is still being copied."];
const NOT_COPIED = ["DataError", "The picture could not be copied."];

// The first item with a PNG among those written, or null: then the write is the engine's, which
// also says what is wrong with anything that isn't a list of items.
function pngItem(items) {
  try {
    for (let i = 0; i < items.length; i++) {
      const types = items[i] && items[i].types;
      for (let j = 0; types && j < types.length; j++) {
        if (types[j] === PNG) {
          return items[i];
        }
      }
    }
  } catch (e) {
    // Not a list of items: the engine says what is wrong with it
  }
  return null;
}

// The picture's bytes in base64, read without the page's help
function base64Of(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result.slice(reader.result.indexOf(",") + 1));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

let copying = false;

async function copyPng(item) {
  copying = true;
  try {
    const blob = await item.getType(PNG);
    if (!(blob.size > 0) || blob.size > MAX_IMAGE_BYTES) {
      throw NOT_COPIED;
    }
    const data = await base64Of(blob);
    const copied = await browser.runtime.sendNativeMessage(NATIVE_APP, { type: "copyImage", data });
    if (copied !== true) {
      throw NOT_COPIED;
    }
  } finally {
    copying = false;
  }
}

// A promise of the page's own (it can't use one of the script's) that settles as [done] does.
// It only passes on the outcome: calling what it is given again changes nothing.
function pagePromise(done) {
  return new PagePromise(exportFunction((resolve, reject) => {
    done.then(
      () => resolve(),
      error => {
        const [name, message] = error === NO_ACTIVATION || error === STILL_COPYING ? error : NOT_COPIED;
        reject(new PageDOMException(message, name));
      }
    );
  }, page));
}

function wrapClipboardWrite() {
  // Only a secure page (https, or a local one) has a clipboard
  if (!window.Clipboard || !page.Clipboard) {
    return;
  }
  // The engine's own method, through the Xray: never one the page put there, nor the one an
  // earlier copy of this script left on a page open while RemBrowse updated it (that one no
  // longer works)
  const engineWrite = window.Clipboard.prototype.write;
  exportFunction(function write(...args) {
    // Only for this page's own clipboard: the engine answers a call on anything else
    const item = this === window.navigator.clipboard ? pngItem(args[0]) : null;
    if (!item) {
      return Reflect.apply(engineWrite, this, args);
    }
    const activation = window.navigator.userActivation;
    if (!activation || !activation.isActive) {
      return pagePromise(Promise.reject(NO_ACTIVATION));
    }
    if (copying) {
      return pagePromise(Promise.reject(STILL_COPYING));
    }
    return pagePromise(copyPng(item));
  }, page.Clipboard.prototype, { defineAs: "write" });
}

wrapClipboardWrite();
