/* This Source Code Form is part of RemBrowse. */

"use strict";

/* global ExtensionAPI, Services */

// Extension popups are written for desktop Firefox, which sizes the popup panel to the page
// and ignores <meta name="viewport">, so most popups leave that tag out. On Android, Gecko
// lays a page without the tag out 980px wide and then zooms it out to fit the view. In a
// 420dp popup that leaves the page at about 40%, too small to read or use.
//
// This gives such a popup the tag a page made for a phone would carry, so it is laid out at
// the popup's own width at 100%. There is deliberately no initial-scale: without one, Gecko
// still scales a popup whose content is wider than the popup down to fit, instead of making
// it scroll sideways. A tag the popup has itself is parsed later and takes precedence (Gecko
// uses the most recently added one), so popups that already work on Android are unchanged.
// Pages of extensions shown in a tab are left alone.

const VIEWPORT = "width=device-width";

let started = false;

function isExtensionPopup(win) {
  // The browser that hosts a popup is marked as one; Gecko answers this for every extension
  // page as it starts, so asking again is cheap.
  const data = win.docShell?.messageManager?.sendSyncMessage("Extension:GetFrameData")?.[0];
  return data?.viewType === "popup";
}

const observer = {
  observe(doc) {
    try {
      const win = doc?.defaultView;
      if (!win || win.top !== win) {
        return;
      }
      if (!doc.documentURIObject?.schemeIs("moz-extension")) {
        return;
      }
      const root = doc.documentElement;
      if (!root || root.localName !== "html") {
        return;
      }
      if (!isExtensionPopup(win)) {
        return;
      }
      if (doc.querySelector('meta[name="viewport" i]')) {
        return;
      }
      const meta = doc.createElement("meta");
      meta.setAttribute("name", "viewport");
      meta.setAttribute("content", VIEWPORT);
      root.prepend(meta);
    } catch (e) {
      // Never let this get in the way of the popup loading
      Cu.reportError(e);
    }
  },
};

this.popupViewport = class extends ExtensionAPI {
  getAPI() {
    return {
      popupViewport: {
        start() {
          if (started) {
            return;
          }
          started = true;
          Services.obs.addObserver(observer, "document-element-inserted");
        },
      },
    };
  }
};
