/* This Source Code Form is part of RemBrowse. */

"use strict";

/* global ChromeUtils, Cu, ExtensionAPI, ExtensionCommon, Services */

const { setTimeout } = ChromeUtils.importESModule("resource://gre/modules/Timer.sys.mjs");

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
//
// Desktop Firefox also sizes the panel to the popup's content. To do the same, the size a
// desktop popup wants is measured here and reported (onPopupSize) to RemBrowse, which sizes
// the popup to fit. Popups with a viewport tag of their own were made for Android's popup,
// which fills the window: they report the size they already have, which keeps it, and lets
// RemBrowse show the popup without waiting.
//
// Each report also carries the viewport the page was laid out in. Content sized from the
// viewport (100vh, or 100% of a root as tall as the viewport) measures the viewport plus
// whatever sits around it, such as the body's margin, so every resize made to fit it makes it
// bigger again. RemBrowse compares reports and, but for one step right after the content
// changed by itself, keeps the popup's size when a size changed only by as much as its viewport
// did (ExtensionPopups.sized).

const VIEWPORT = "width=device-width";

// Marks the viewport tag added here, to tell it from one the popup has itself
const OWN_TAG = "data-rembrowse";

// How long a change in the page settles before it is measured again
const MEASURE_DELAY_MS = 100;

// Smaller size changes are not worth resizing the popup for
const MIN_CHANGE_PX = 2;

let started = false;

// The onPopupSize listeners (the helper's background page)
const sizeListeners = new Set();

function isExtensionPopup(win) {
  // The browser that hosts a popup is marked as one; Gecko answers this for every extension
  // page as it starts, so asking again is cheap.
  const data = win.docShell?.messageManager?.sendSyncMessage("Extension:GetFrameData")?.[0];
  return data?.viewType === "popup";
}

/** Whether the popup carries a viewport tag of its own, which means it was made for Android. */
function hasOwnViewport(doc) {
  return Array.from(doc.querySelectorAll('meta[name="viewport" i]')).some(
    meta => !meta.hasAttribute(OWN_TAG)
  );
}

/**
 * The size, in CSS pixels, the popup's content wants:
 * - width: all of the content when it is wider than the popup; otherwise the body's own
 *   width, which is a fixed-width popup's width and, for a page that fills whatever it is
 *   given, the popup's current width;
 * - height: where the body ends, so content shorter than the popup lets it shrink and
 *   longer content lets it grow.
 */
function preferredSize(win, doc) {
  const root = doc.documentElement;
  const body = doc.body;
  if (!root || !body) {
    return null;
  }
  const style = win.getComputedStyle(body);
  const px = name => parseFloat(style[name]) || 0;
  const rect = body.getBoundingClientRect();
  const width =
    root.scrollWidth > root.clientWidth + 1
      ? root.scrollWidth
      : rect.width + px("marginLeft") + px("marginRight");
  const height = rect.bottom + win.scrollY + px("marginBottom");
  if (!(width > 0) || !(height > 0)) {
    return null;
  }
  return { width: Math.ceil(width), height: Math.ceil(height) };
}

/**
 * The viewport [doc] is laid out in, in CSS pixels: what 100vh, and 100% of the root, are
 * measured against. It can be taller than the popup on screen: Gecko adds the height of a
 * keyboard reported over the page to it, taking the keyboard to cover the page's bottom. And on
 * some screens its CSS pixel is a fraction smaller than Android's dp, since Gecko rounds the
 * number of its units in a device pixel to a whole one.
 */
function viewportSize(doc) {
  // In quirks mode the body answers for the viewport
  const element = doc.compatMode === "BackCompat" ? doc.body : doc.documentElement;
  if (!element || !(element.clientWidth > 0) || !(element.clientHeight > 0)) {
    return null;
  }
  return { width: element.clientWidth, height: element.clientHeight };
}

/** Whether [size] differs from [before] (none yet counts) by enough to resize the popup. */
function changed(size, before) {
  return (
    !before ||
    Math.abs(before.width - size.width) >= MIN_CHANGE_PX ||
    Math.abs(before.height - size.height) >= MIN_CHANGE_PX
  );
}

/** Measures [doc] whenever it settles after a change, and reports new sizes. */
function watchSize(win, doc, extensionId) {
  let pending = false;
  let last = null;
  let lastViewport = null;

  const measure = () => {
    pending = false;
    try {
      if (!doc.defaultView) {
        return;
      }
      const ownViewport = hasOwnViewport(doc);
      const size = ownViewport
        ? { width: doc.documentElement.clientWidth, height: win.innerHeight }
        : preferredSize(win, doc);
      if (!size || !(size.width > 0 && size.height > 0)) {
        return;
      }
      // A popup with its own viewport tag already reports the size it has; for the others, a
      // new viewport is reported even when the size stayed, so RemBrowse compares each size
      // with the one measured just before it
      const viewport = ownViewport ? null : viewportSize(doc);
      if (!changed(size, last) && !(viewport && changed(viewport, lastViewport))) {
        return;
      }
      last = size;
      lastViewport = viewport;
      const report = { extensionId, width: size.width, height: size.height };
      if (viewport) {
        report.viewportWidth = viewport.width;
        report.viewportHeight = viewport.height;
      }
      for (const fire of sizeListeners) {
        fire.async(report);
      }
    } catch (e) {
      Cu.reportError(e);
    }
  };

  const schedule = () => {
    if (!pending) {
      pending = true;
      setTimeout(measure, MEASURE_DELAY_MS);
    }
  };

  win.addEventListener("DOMContentLoaded", schedule, { capture: true, once: true });
  win.addEventListener("load", schedule, { capture: true, once: true });
  win.addEventListener("resize", schedule);
  // Content that grows, shrinks or appears later (most popups render from script)
  new win.ResizeObserver(schedule).observe(doc.documentElement);
  new win.MutationObserver(schedule).observe(doc.documentElement, {
    attributes: true,
    characterData: true,
    childList: true,
    subtree: true,
  });
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
      if (!hasOwnViewport(doc)) {
        const meta = doc.createElement("meta");
        meta.setAttribute("name", "viewport");
        meta.setAttribute("content", VIEWPORT);
        meta.setAttribute(OWN_TAG, "");
        root.prepend(meta);
      }
      const extensionId = doc.nodePrincipal?.addonId;
      if (extensionId) {
        watchSize(win, doc, extensionId);
      }
    } catch (e) {
      // Never let this get in the way of the popup loading
      Cu.reportError(e);
    }
  },
};

this.popupViewport = class extends ExtensionAPI {
  getAPI(context) {
    return {
      popupViewport: {
        start() {
          if (started) {
            return;
          }
          started = true;
          Services.obs.addObserver(observer, "document-element-inserted");
        },
        onPopupSize: new ExtensionCommon.EventManager({
          context,
          name: "popupViewport.onPopupSize",
          register: fire => {
            sizeListeners.add(fire);
            return () => sizeListeners.delete(fire);
          },
        }).api(),
      },
    };
  }
};
