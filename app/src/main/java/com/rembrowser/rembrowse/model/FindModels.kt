package com.rembrowser.rembrowse.model

import com.rembrowser.rembrowse.engine.FindResult

/**
 * The find bar, open on [tabId] (always the focused tab).
 *
 * @param result what the last search for [query] found; null while there is nothing to show
 * (no text yet, a search on its way, or the page changed).
 * @param focusRequests goes up each time the bar is asked for again (Ctrl+F while it is open),
 * so the screen can put the cursor back in it.
 */
data class FindInPageState(
    val tabId: String,
    val query: String = "",
    val matchCase: Boolean = false,
    val result: FindResult? = null,
    val focusRequests: Int = 0
)
