package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.HistoryEntry
import com.rembrowser.rembrowse.util.UrlResolver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Everything the history list can ask for. */
data class HistoryActions(
    val onOpen: (url: String) -> Unit,
    val onRemove: (url: String) -> Unit,
    val onClear: () -> Unit
)

/**
 * The browsing history: a search box, then the pages visited, most recent first, under a
 * heading for each day. Selecting a page opens it; each can be removed, or all cleared.
 */
@Composable
fun HistoryPanel(
    entries: List<HistoryEntry>,
    keepHistory: Boolean,
    actions: HistoryActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone)
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = remember(entries, query) { filterHistory(entries, query) }
    val groups = remember(shown, zone) { groupByDay(shown, zone) }

    Column(modifier = modifier.testTag("history_panel")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search history") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("history_search")
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = { confirmClear = true },
                enabled = entries.isNotEmpty(),
                modifier = Modifier.testTag("history_clear")
            ) { Text("Clear history") }
        }
        Spacer(modifier = Modifier.padding(top = 8.dp))

        when {
            entries.isEmpty() -> EmptyHistoryText(
                if (keepHistory) "Pages you visit will be listed here." else "Browsing history is off."
            )
            shown.isEmpty() -> EmptyHistoryText("No pages match “${query.trim()}”.")
            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                groups.forEach { (day, dayEntries) ->
                    item(key = "day-$day") {
                        Text(
                            text = dayLabel(day, today),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                            modifier = Modifier
                                .padding(top = 16.dp, bottom = 4.dp)
                                .semantics { heading() }
                        )
                    }
                    items(dayEntries, key = { "entry-${it.url}" }) { entry ->
                        HistoryRow(
                            entry = entry,
                            zone = zone,
                            onOpen = { actions.onOpen(entry.url) },
                            onRemove = { actions.onRemove(entry.url) }
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear browsing history?") },
            text = {
                Text(
                    if (entries.size == 1) {
                        "The 1 page in your history will be removed. This can't be undone."
                    } else {
                        "All ${entries.size} pages in your history will be removed. This can't be undone."
                    }
                )
            },
            confirmButton = {
                PrimaryDialogButton(label = "Clear", onClick = {
                    confirmClear = false
                    actions.onClear()
                })
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EmptyHistoryText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

/**
 * A page in the history. A click opens it (in a new tab: History is a tab of its own), as does a
 * middle click; a right-click or a long press opens a menu with Open and Remove from history.
 */
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    zone: ZoneId,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        HistoryRowContent(entry, zone, onOpen, onRemove, onOpenMenu = { showMenu = true })
        RemDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            RemMenuItem(
                text = { Text("Open") },
                onClick = {
                    showMenu = false
                    onOpen()
                },
                modifier = Modifier.testTag("history_menu_open_${entry.url}")
            )
            RemMenuItem(
                text = { Text("Remove from history") },
                onClick = {
                    showMenu = false
                    onRemove()
                },
                modifier = Modifier.testTag("history_menu_remove_${entry.url}")
            )
        }
    }
}

@Composable
private fun HistoryRowContent(
    entry: HistoryEntry,
    zone: ZoneId,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val host = UrlResolver.extractDisplayHost(entry.url).ifBlank { entry.url }
    val time = remember(entry.visitedAtMillis, zone) {
        Instant.ofEpochMilli(entry.visitedAtMillis).atZone(zone).toLocalTime()
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .contextMenuTrigger(onOpenMenu)
            .middleClickTrigger(onOpen)
            .touchLongPress("More options", onOpenMenu)
            .clickable(
                interactionSource = null,
                indication = ripple(focusRingShape = MaterialTheme.shapes.small),
                onClickLabel = "Open",
                onClick = onOpen
            )
            .padding(start = 4.dp)
            .testTag("history_entry_${entry.url}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SiteIcon(url = entry.url, title = entry.displayTitle)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$host · $time",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        RemIconButton(
            icon = Icons.Outlined.Close,
            label = "Remove ${entry.displayTitle} from history",
            onClick = onRemove,
            iconSize = 18.dp
        )
    }
}

/** The entries whose title or address contains every word of [query], in their order. */
internal fun filterHistory(entries: List<HistoryEntry>, query: String): List<HistoryEntry> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return entries
    return entries.filter { entry -> words.all { entry.title.contains(it, true) || entry.url.contains(it, true) } }
}

/** [entries] (already newest first) split into days, newest day first. */
internal fun groupByDay(
    entries: List<HistoryEntry>,
    zone: ZoneId
): List<Pair<LocalDate, List<HistoryEntry>>> =
    entries.groupBy { Instant.ofEpochMilli(it.visitedAtMillis).atZone(zone).toLocalDate() }
        .toList()
        .sortedByDescending { it.first }

/** "Today", "Yesterday", or the date written out, such as "Monday, 16 November 2026". */
internal fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))
}
