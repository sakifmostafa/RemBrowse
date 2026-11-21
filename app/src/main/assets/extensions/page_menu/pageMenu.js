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
