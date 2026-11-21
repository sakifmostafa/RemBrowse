package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.HistoryEntry
import com.rembrowser.rembrowse.util.UrlResolver

/** One runnable entry in the command palette. */
data class PaletteCommand(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val shortcut: String? = null,
    val action: () -> Unit
)

private const val MAX_TAB_RESULTS = 6

/**
 * Keyboard-first launcher (Ctrl+K): type to search the web, open a site, jump to an open tab
 * or run a browser command. Arrow keys move the highlight, Enter runs it, Esc closes.
 */
@Composable
fun CommandPalette(
    commands: List<PaletteCommand>,
    tabs: List<BrowserTab>,
    history: List<HistoryEntry> = emptyList(),
    onNavigate: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableIntStateOf(0) }
    val inputFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val results = remember(query, commands, tabs, history) {
        buildPaletteResults(query, commands, tabs, onNavigate, onSelectTab, history)
    }
    LaunchedEffect(results.size) {
        highlighted = highlighted.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(highlighted) {
        if (results.isNotEmpty()) listState.animateScrollToItem(highlighted)
    }
    LaunchedEffect(Unit) { runCatching { inputFocus.requestFocus() } }

    fun run(command: PaletteCommand) {
        onDismiss()
        command.action()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        TouchpadAsMouseInDialog()
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .testTag("command_palette"),
            shape = MaterialTheme.shapes.large,
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.primaryContainer),
            shadowElevation = 16.dp
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            highlighted = 0
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { results.getOrNull(highlighted)?.let(::run) }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(inputFocus)
                            .testTag("command_palette_input")
                            .semantics { contentDescription = "Command, search or address" }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (results.isNotEmpty()) highlighted = (highlighted + 1) % results.size
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        if (results.isNotEmpty()) {
                                            highlighted = (highlighted - 1 + results.size) % results.size
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            },
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "Type a command, search, or open a site…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
                HorizontalDivider(color = colors.outlineVariant)
                if (results.isEmpty()) {
                    Text(
                        text = "No matching commands",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(results, key = { _, item -> item.id }) { index, command ->
                            PaletteRow(
                                command = command,
                                isHighlighted = index == highlighted,
                                onClick = { run(command) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(command: PaletteCommand, isHighlighted: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = if (isHighlighted) colors.onPrimaryContainer else colors.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (isHighlighted) colors.primaryContainer else Color.Transparent)
            .border(1.dp, if (isHighlighted) colors.primary else Color.Transparent, MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = isHighlighted }
            .testTag("palette_${command.id}")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = command.icon,
            contentDescription = null,
            tint = if (isHighlighted) content else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = command.label,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        command.shortcut?.let { shortcut ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                shortcut.split('+').forEach { keyLabel ->
                    Box(
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (isHighlighted) content.copy(alpha = 0.4f) else colors.outlineVariant,
                                MaterialTheme.shapes.extraSmall
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        ShortcutHint(text = keyLabel, color = if (isHighlighted) content else colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Typed text first offers to open or search for itself, then matching commands, then matching
 * open tabs, then matching pages from the history that are not open. With nothing typed, the
 * full command list is shown.
 */
internal fun buildPaletteResults(
    query: String,
    commands: List<PaletteCommand>,
    tabs: List<BrowserTab>,
    onNavigate: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    history: List<HistoryEntry> = emptyList()
): List<PaletteCommand> {
    val needle = query.trim()
    if (needle.isEmpty()) return commands

    val results = mutableListOf<PaletteCommand>()
    val resolved = UrlResolver.resolveInput(needle)
    val isSearch = resolved.startsWith(UrlResolver.DEFAULT_SEARCH_ENGINE_PREFIX)
    results += PaletteCommand(
        id = "go",
        label = if (isSearch) "Search the web for “$needle”" else "Open $needle",
        icon = if (isSearch) Icons.Outlined.Search else Icons.Outlined.Language,
        action = { onNavigate(needle) }
    )
    results += commands.filter { it.label.contains(needle, ignoreCase = true) }
    results += tabs
        .filter {
            !it.isNewTabPage &&
                (it.displayTitle.contains(needle, ignoreCase = true) || it.url.contains(needle, ignoreCase = true))
        }
        .take(MAX_TAB_RESULTS)
        .map { tab ->
            PaletteCommand(
                id = "tab-${tab.id}",
                label = "Switch to ${tab.displayTitle}",
                icon = Icons.Outlined.Tab,
                action = { onSelectTab(tab.id) }
            )
        }
    val openUrls = tabs.map { it.url }.toSet()
    results += filterHistory(history, needle)
        .filter { it.url !in openUrls }
        .take(MAX_HISTORY_RESULTS)
        .map { entry ->
            PaletteCommand(
                id = "history-${entry.url}",
                label = entry.displayTitle,
                icon = Icons.Outlined.History,
                action = { onNavigate(entry.url) }
            )
        }
    return results
}

/** Pages from the history offered for a typed query. */
private const val MAX_HISTORY_RESULTS = 5
