package com.rembrowser.rembrowse.model

/**
 * A page in the browsing history. One entry per address: visiting it again moves it to the
 * top with the new time, as desktop browsers' history lists show it.
 */
data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAtMillis: Long
) {
    /** What a list shows: the page's title, or its address when it had none. */
    val displayTitle: String
        get() = title.ifBlank { url }
}
