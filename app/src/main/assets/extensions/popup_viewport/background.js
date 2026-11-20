/* This Source Code Form is part of RemBrowse. */

"use strict";

/* global browser */

// The app's end of the channel (GeckoView's WebExtension.setMessageDelegate) uses this name.
const NATIVE_APP = "rembrowse";

// Passes each popup's preferred size to the app, which sizes the popup to fit. If the app is
// not listening (yet), the message is simply dropped; the next change sends a new one.
browser.popupViewport.onPopupSize.addListener(size => {
  browser.runtime
    .sendNativeMessage(NATIVE_APP, { type: "popupSize", ...size })
    .catch(() => {});
});

// Calling into the API is what loads its script into the extension process, where every
// extension's popup page lives.
browser.popupViewport.start();
