package com.rembrowser.rembrowse.model

/**
 * A dialog that web content asked the browser to show (JavaScript `alert`/`confirm`/`prompt`,
 * HTTP authentication, an HTML `<select>` picker, or the file picker of an upload button).
 *
 * The engine owns the callbacks; the UI calls exactly one of them and then removes the prompt.
 */
sealed interface PagePrompt {
    val id: String
    val tabId: String
    val title: String?
    val message: String?

    /** Called when the user backs out without answering. */
    val onDismiss: () -> Unit

    data class Alert(
        override val id: String,
        override val tabId: String,
        override val title: String?,
        override val message: String?,
        override val onDismiss: () -> Unit
    ) : PagePrompt

    data class Confirm(
        override val id: String,
        override val tabId: String,
        override val title: String?,
        override val message: String?,
        val onResult: (confirmed: Boolean) -> Unit,
        override val onDismiss: () -> Unit
    ) : PagePrompt

    data class TextInput(
        override val id: String,
        override val tabId: String,
        override val title: String?,
        override val message: String?,
        val defaultValue: String,
        val onResult: (text: String) -> Unit,
        override val onDismiss: () -> Unit
    ) : PagePrompt

    data class Authentication(
        override val id: String,
        override val tabId: String,
        override val title: String?,
        override val message: String?,
        val passwordOnly: Boolean,
        val defaultUsername: String,
        val previousAttemptFailed: Boolean,
        val onResult: (username: String, password: String) -> Unit,
        override val onDismiss: () -> Unit
    ) : PagePrompt

    /**
     * A page's file input (an upload button, as on Google Drive or X) asked for files. The UI
     * opens the system file picker rather than a dialog, then hands back what was chosen as
     * `content://` addresses, or calls [onDismiss] when nothing was.
     */
    data class FilePicker(
        override val id: String,
        override val tabId: String,
        override val title: String?,
        val multiple: Boolean,
        /** MIME types to offer, such as `image/png` (a family ends in a wildcard). Never empty. */
        val mimeTypes: List<String>,
        val onResult: (uris: List<String>) -> Unit,
        override val onDismiss: () -> Unit,
        override val message: String? = null
    ) : PagePrompt

    data class Choice(
        override val id: String,
        override val tabId: String,
        override val title: String?,
        override val message: String?,
        val multiple: Boolean,
        val options: List<PromptOption>,
        val onResult: (selectedIds: List<String>) -> Unit,
        override val onDismiss: () -> Unit
    ) : PagePrompt
}

/** One row of a [PagePrompt.Choice]. Group headers and separators are not selectable. */
data class PromptOption(
    val id: String,
    val label: String,
    val selected: Boolean = false,
    val disabled: Boolean = false,
    val isGroupHeader: Boolean = false,
    val isSeparator: Boolean = false,
    val indented: Boolean = false
) {
    val isSelectable: Boolean
        get() = !disabled && !isGroupHeader && !isSeparator
}
