/* This Source Code Form is part of RemBrowse. */

"use strict";

/* global browser */

// Calling into the API is what loads its script into the extension process, where every
// extension's popup page lives.
browser.popupViewport.start();
