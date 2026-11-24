package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PromptOption
import com.rembrowser.rembrowse.util.UrlResolver

@Composable
fun BookmarksDialog(
    bookmarks: List<BookmarkItem>,
    onOpenBookmark: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    RemDialog(title = "Bookmarks", onDismiss = onDismiss, testTag = "bookmarks_dialog") {
        if (bookmarks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.StarBorder,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "No bookmarks yet", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Use the star next to the address bar to save the page you're on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(bottom = 8.dp)) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .keyboardFocusRing(MaterialTheme.shapes.small, inside = true)
                            .clickable(role = Role.Button) {
                                onOpenBookmark(bookmark.url)
                                onDismiss()
                            }
                            .padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SiteIcon(url = bookmark.url, title = bookmark.title)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bookmark.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = UrlResolver.toDisplayUrl(bookmark.url),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        RemIconButton(
                            icon = Icons.Outlined.DeleteOutline,
                            label = "Remove ${bookmark.title}",
                            onClick = { onRemoveBookmark(bookmark.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders a dialog requested by web content. [onResolved] must be called after the prompt's own
 * callback so the browser drops it from the queue.
 */
@Composable
fun PagePromptDialog(prompt: PagePrompt, originLabel: String, onResolved: () -> Unit) {
    fun dismiss() {
        prompt.onDismiss()
        onResolved()
    }
    val title = prompt.title?.takeIf { it.isNotBlank() }
    when (prompt) {
        is PagePrompt.Alert -> AlertDialog(
            onDismissRequest = ::dismiss,
            title = { PromptTitle(title ?: "$originLabel says") },
            text = { PromptMessage(prompt.message) },
            confirmButton = { PrimaryDialogButton("OK", onClick = ::dismiss) }
        )

        is PagePrompt.Confirm -> AlertDialog(
            onDismissRequest = ::dismiss,
            title = { PromptTitle(title ?: "$originLabel asks") },
            text = { PromptMessage(prompt.message) },
            confirmButton = {
                PrimaryDialogButton("OK") {
                    prompt.onResult(true)
                    onResolved()
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        prompt.onResult(false)
                        onResolved()
                    }
                ) { Text("Cancel") }
            }
        )

        is PagePrompt.TextInput -> {
            var text by remember(prompt.id) { mutableStateOf(prompt.defaultValue) }
            val submit = {
                prompt.onResult(text)
                onResolved()
            }
            AlertDialog(
                onDismissRequest = ::dismiss,
                title = { PromptTitle(title ?: "$originLabel asks") },
                text = {
                    TouchpadAsMouseInDialog()
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PromptMessage(prompt.message)
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Your answer") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = { PrimaryDialogButton("OK", onClick = submit) },
                dismissButton = { TextButton(onClick = ::dismiss) { Text("Cancel") } }
            )
        }

        is PagePrompt.Authentication -> {
            var username by remember(prompt.id) { mutableStateOf(prompt.defaultUsername) }
            var password by remember(prompt.id) { mutableStateOf("") }
            val submit = {
                prompt.onResult(username, password)
                onResolved()
            }
            AlertDialog(
                onDismissRequest = ::dismiss,
                title = { PromptTitle(title ?: "Sign in to $originLabel") },
                text = {
                    TouchpadAsMouseInDialog()
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PromptMessage(prompt.message)
                        if (prompt.previousAttemptFailed) {
                            Text(
                                text = "That didn't work. Check your details and try again.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (!prompt.passwordOnly) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("Username") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = { PrimaryDialogButton("Sign in", onClick = submit) },
                dismissButton = { TextButton(onClick = ::dismiss) { Text("Cancel") } }
            )
        }

        is PagePrompt.Choice -> ChoicePromptDialog(prompt = prompt, onDismiss = ::dismiss, onResolved = onResolved)
        // Answered by the system file picker, which the screen opens instead of a dialog
        is PagePrompt.FilePicker -> Unit
    }
}

/** The picker behind HTML `<select>` elements, single or multiple choice. */
@Composable
private fun ChoicePromptDialog(prompt: PagePrompt.Choice, onDismiss: () -> Unit, onResolved: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selectedIds = remember(prompt.id) {
        mutableStateListOf<String>().apply {
            addAll(prompt.options.filter { it.selected && it.isSelectable }.map { it.id })
        }
    }
    RemDialog(
        title = prompt.title?.takeIf { it.isNotBlank() } ?: "Choose an option",
        onDismiss = onDismiss,
        maxWidth = 420.dp,
        maxHeight = 560.dp,
        testTag = "choice_prompt"
    ) {
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            itemsIndexed(prompt.options) { _, option ->
                when {
                    option.isSeparator -> HorizontalDivider(color = colors.outlineVariant)
                    option.isGroupHeader -> Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
                    )
                    else -> ChoiceRow(
                        option = option,
                        multiple = prompt.multiple,
                        selected = option.id in selectedIds,
                        onClick = {
                            if (prompt.multiple) {
                                if (!selectedIds.remove(option.id)) selectedIds.add(option.id)
                            } else {
                                prompt.onResult(listOf(option.id))
                                onResolved()
                            }
                        }
                    )
                }
            }
        }
        if (prompt.multiple) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                PrimaryDialogButton("Done") {
                    prompt.onResult(selectedIds.toList())
                    onResolved()
                }
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ChoiceRow(option: PromptOption, multiple: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = option.isSelectable
    val interaction = if (multiple) {
        Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .keyboardFocusRing(MaterialTheme.shapes.small, inside = true)
            .then(interaction)
            .padding(start = if (option.indented) 36.dp else 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f)
        )
        if (multiple) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        } else if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Box(modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PromptTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PromptMessage(message: String?) {
    if (!message.isNullOrBlank()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 12,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Filled accent button used as the single primary action of a dialog. */
@Composable
fun PrimaryDialogButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(label)
    }
}
