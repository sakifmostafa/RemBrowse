package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Terminal
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
    /** Other words it is found by ("pdf" for Print…), besides the words of its label. */
    val keywords: List<String> = emptyList(),
    /**
     * False for commands that close or rearrange things (Close tab, split view): listed where the
     * text matches them, but never put first, so Enter on a typed word can't run them.
     */
    val promotable: Boolean = true,
    /** False for commands Go to address leaves out: they would open it again or drop what was typed. */
    val inGoToAddress: Boolean = true,
    val action: () -> Unit
)

/** A server saved in Settings → Terminal, offered by the palette and Go to address. */
data class PaletteHost(
    val id: String,
    /** The name it was given, or "" when it has none. */
    val name: String,
    /** `user@host`, with `:port` when it is not 22. */
    val address: String,
    /** Opens a terminal tab connected to the host. */
    val connect: () -> Unit
)

/** The id of the row that opens or searches for the typed text. */
internal const val GO_ROW_ID = "go"

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
    hosts: List<PaletteHost> = emptyList(),
    onNavigate: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableIntStateOf(0) }
    val inputFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val results = remember(query, commands, tabs, history, hosts) {
        buildPaletteResults(query, commands, tabs, onNavigate, onSelectTab, history, hosts)
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
                    // The TextFieldState BasicTextField replaces this one; moving to it is a change of its own
                    @Suppress("DEPRECATION")
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
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        // Room inside the list's edge for the first and last rows' focus rings
                        contentPadding = PaddingValues(vertical = 4.dp),
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
internal fun PaletteRow(command: PaletteCommand, isHighlighted: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = if (isHighlighted) colors.onPrimaryContainer else colors.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // Outside its border, which is the accent on the highlighted row (see keyboardFocusRing)
            .keyboardFocusRing(MaterialTheme.shapes.small)
            .clip(MaterialTheme.shapes.small)
            .background(if (isHighlighted) colors.primaryContainer else Color.Transparent)
            .border(1.dp, if (isHighlighted) colors.primary else Color.Transparent, MaterialTheme.shapes.small)
            .clickable(
                interactionSource = null,
                indication = ringOutsideRipple(),
                role = Role.Button,
                onClick = onClick
            )
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
 * The rows for typed text, shared by the palette (Ctrl+K) and Go to address (Ctrl+G); the first
 * row is what Enter runs.
 *
 * Text looks like an address when it would be opened rather than searched for ("localhost",
 * "news.com") or has a ".", ":" or "/" in it; then nothing goes above the open row.
 *
 * - Commands the text names come first, above the row that opens or searches for the text (see
 *   [promotionRank]): "ter" → New terminal, "pdf" → Print…, "new ter" → New terminal. Best first:
 *   a whole word before the start of one, the label before a keyword, then the shorter label.
 *   Commands that close or rearrange things are never put first.
 * - Then saved SSH hosts the text names: 3 letters or more starting a word of the host's name
 *   ("serv" → my-server), or "ssh" before the start of a word of its name or address ("ssh" alone
 *   lists them all).
 * - Then the row that opens or searches for the text, hosts with the text (3 letters or more)
 *   starting a word of their address ("root" → root@nas), other commands whose label contains the
 *   text, matching open tabs, and matching pages from the history that are not open.
 *
 * With nothing typed, every command is listed, then every host.
 */
internal fun buildPaletteResults(
    query: String,
    commands: List<PaletteCommand>,
    tabs: List<BrowserTab>,
    onNavigate: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    history: List<HistoryEntry> = emptyList(),
    hosts: List<PaletteHost> = emptyList()
): List<PaletteCommand> {
    val needle = query.trim()
    if (needle.isEmpty()) return commands + hosts.map(::hostRow)

    val isSearch = UrlResolver.resolveInput(needle).startsWith(UrlResolver.DEFAULT_SEARCH_ENGINE_PREFIX)
    val looksLikeAddress = !isSearch || needle.any { it == '.' || it == ':' || it == '/' }
    val promoted = if (looksLikeAddress) {
        emptyList()
    } else {
        commands
            .filter { it.promotable }
            .mapNotNull { command -> promotionRank(command, needle)?.let { rank -> command to rank } }
            .sortedWith(compareBy({ it.second }, { it.first.label.length }))
            .map { it.first }
    }
    val sshTyped = SSH_PREFIX.matchEntire(needle)
    val namedHosts = when {
        sshTyped != null -> sshTyped.groupValues[1].let { part ->
            hosts.filter { startsAWord(part, it.name) || startsAWord(part, it.address) }
        }
        looksLikeAddress || needle.length < MIN_PREFIX_LENGTH -> emptyList()
        else -> hosts.filter { startsAWord(needle, it.name) }
    }
    val hostsByAddress = if (sshTyped != null || needle.length < MIN_PREFIX_LENGTH) {
        emptyList()
    } else {
        hosts.filter { it !in namedHosts && startsAWord(needle, it.address) }
    }

    val results = mutableListOf<PaletteCommand>()
    results += promoted
    results += namedHosts.map(::hostRow)
    results += PaletteCommand(
        id = GO_ROW_ID,
        label = if (isSearch) "Search the web for “$needle”" else "Open $needle",
        icon = if (isSearch) Icons.Outlined.Search else Icons.Outlined.Language,
        action = { onNavigate(needle) }
    )
    results += hostsByAddress.map(::hostRow)
    val promotedIds = promoted.mapTo(HashSet()) { it.id }
    results += commands.filter { it.id !in promotedIds && it.label.contains(needle, ignoreCase = true) }
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

/**
 * How well [needle] names [command], lower is better, or null when it doesn't: 0 a whole word of
 * the label, 1 a whole keyword word, 2 the start of a label word (3 letters or more), 3 the start
 * of a keyword word (4 letters or more, so "con" and "she" don't find console and shell). Text with
 * spaces counts only as the start of the label (0 when it is the whole label). Fewer than three
 * letters never name a command.
 */
private fun promotionRank(command: PaletteCommand, needle: String): Int? {
    val typed = needle.lowercase()
    val labelWords = words(command.label)
    if (typed.any { it.isWhitespace() }) {
        val typedWords = words(typed).joinToString(" ")
        val label = labelWords.joinToString(" ")
        return when {
            label == typedWords -> 0
            typedWords.length >= MIN_PREFIX_LENGTH && label.startsWith(typedWords) -> 2
            else -> null
        }
    }
    // Two letters are too little to mean a command: "to" and "in" are words of "Switch to tab…" and
    // "Find in page", but someone typing them is searching
    if (typed.length < MIN_PREFIX_LENGTH) return null
    val keywordWords = command.keywords.flatMap(::words)
    return when {
        typed in labelWords -> 0
        typed in keywordWords -> 1
        labelWords.any { it.startsWith(typed) } -> 2
        typed.length >= MIN_KEYWORD_PREFIX_LENGTH && keywordWords.any { it.startsWith(typed) } -> 3
        else -> null
    }
}

/** The lower-case words of [text]: runs of letters and digits ("Print…" → "print"). */
private fun words(text: String): List<String> = text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

/**
 * Whether [part] starts a word of [text], ignoring case: at its start or after a character that is
 * not a letter or digit ("nas" in "root@nas", "10.0" in "pi@10.0.0.5", not "ter" in "master").
 * An empty [part] starts every text.
 */
private fun startsAWord(part: String, text: String): Boolean {
    if (part.isEmpty()) return true
    var at = text.indexOf(part, ignoreCase = true)
    while (at >= 0) {
        if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
        at = text.indexOf(part, startIndex = at + 1, ignoreCase = true)
    }
    return false
}

private fun hostRow(host: PaletteHost) = PaletteCommand(
    id = "ssh_${host.id}",
    label = if (host.name.isBlank() || host.name == host.address) "Connect to ${host.address}" else "Connect to ${host.name} (${host.address})",
    icon = Icons.Outlined.Terminal,
    action = host.connect
)

/**
 * The first [limit] of [rows], keeping the open-or-search row among them (in place of the last)
 * when commands and hosts above it would push it out, so it stays reachable with the arrow keys.
 */
internal fun limitKeepingGoRow(rows: List<PaletteCommand>, limit: Int): List<PaletteCommand> {
    val shown = rows.take(limit)
    if (shown.any { it.id == GO_ROW_ID }) return shown
    val goRow = rows.firstOrNull { it.id == GO_ROW_ID } ?: return shown
    return shown.dropLast(1) + goRow
}

/** Fewest letters that name a command or host by the start of a word. */
private const val MIN_PREFIX_LENGTH = 3

/** Fewest letters that name a command by the start of one of its keywords. */
private const val MIN_KEYWORD_PREFIX_LENGTH = 4

/** "ssh", alone or before what to connect to. */
private val SSH_PREFIX = Regex("ssh(?:\\s+(.*))?", RegexOption.IGNORE_CASE)

private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/** Pages from the history offered for a typed query. */
private const val MAX_HISTORY_RESULTS = 5
