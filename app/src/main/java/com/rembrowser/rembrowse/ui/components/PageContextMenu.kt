package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.PageContextMenu
import com.rembrowser.rembrowse.model.PageMediaType
import com.rembrowser.rembrowse.model.PageMenuAction
import com.rembrowser.rembrowse.model.pageMenuSections
import kotlin.math.roundToInt

/**
 * The context menu of the page shown in this pane, opened where it was right clicked: for a
 * link, a picture, or the page itself. Call it from the pane's own box, like the find bar.
 *
 * @param menu the open menu, if it belongs to this pane's page.
 */
@Composable
fun BoxScope.PageContextMenuOverlay(
    menu: PageContextMenu?,
    tab: BrowserTab,
    canSplit: Boolean,
    isBookmarked: Boolean,
    onAction: (PageMenuAction) -> Unit,
    onDismiss: () -> Unit
) {
    if (menu == null || menu.tabId != tab.id) return
    val sections = pageMenuSections(menu, tab, canSplit)
    if (sections.isEmpty()) return

    // A new right click is a new menu, at its own place
    key(menu.serial) {
        // A point to hang the menu from; the menu itself moves up or left when there is no room
        Box(
            modifier = Modifier
                .offset { IntOffset((menu.x ?: 0f).roundToInt(), (menu.y ?: 0f).roundToInt()) }
                .size(0.dp)
        ) {
            RemDropdownMenu(
                expanded = true,
                onDismissRequest = onDismiss,
                offset = DpOffset.Zero,
                modifier = Modifier.testTag("page_context_menu")
            ) {
                sections.forEachIndexed { index, section ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    section.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(labelFor(entry.action, menu.mediaType, isBookmarked)) },
                            trailingIcon = shortcutFor(entry.action)?.let { hint -> { ShortcutHint(hint) } },
                            enabled = entry.enabled,
                            onClick = { onAction(entry.action) },
                            modifier = Modifier.testTag("page_menu_${entry.action.name.lowercase()}")
                        )
                    }
                }
            }
        }
    }
}

private fun labelFor(action: PageMenuAction, mediaType: PageMediaType, isBookmarked: Boolean): String {
    val media = when (mediaType) {
        PageMediaType.VIDEO -> "video"
        PageMediaType.AUDIO -> "audio"
        else -> "image"
    }
    return when (action) {
        PageMenuAction.OPEN_LINK_IN_NEW_TAB -> "Open link in new tab"
        PageMenuAction.OPEN_LINK_IN_SPLIT -> "Open link in split view"
        PageMenuAction.COPY_LINK -> "Copy link"
        PageMenuAction.COPY_LINK_TEXT -> "Copy link text"
        PageMenuAction.OPEN_MEDIA_IN_NEW_TAB -> "Open $media in new tab"
        PageMenuAction.COPY_IMAGE -> "Copy image"
        PageMenuAction.COPY_MEDIA_ADDRESS -> "Copy $media address"
        PageMenuAction.BACK -> "Back"
        PageMenuAction.FORWARD -> "Forward"
        PageMenuAction.RELOAD -> "Reload"
        PageMenuAction.BOOKMARK -> if (isBookmarked) "Remove bookmark" else "Bookmark this page"
        PageMenuAction.COPY_PAGE_ADDRESS -> "Copy page address"
        PageMenuAction.FIND_IN_PAGE -> "Find in page"
        PageMenuAction.PRINT -> "Print…"
        PageMenuAction.SCREENSHOT -> "Take screenshot"
    }
}

private fun shortcutFor(action: PageMenuAction): String? = when (action) {
    PageMenuAction.BACK -> "Alt+Left"
    PageMenuAction.FORWARD -> "Alt+Right"
    PageMenuAction.RELOAD -> "Ctrl+R"
    PageMenuAction.BOOKMARK -> "Ctrl+D"
    PageMenuAction.FIND_IN_PAGE -> "Ctrl+F"
    PageMenuAction.PRINT -> "Ctrl+P"
    PageMenuAction.SCREENSHOT -> "Ctrl+Alt+S"
    else -> null
}
