package com.rembrowser.rembrowse.ui.components

import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.HistoryEntry
import com.rembrowser.rembrowse.util.UrlResolver
import java.util.function.Consumer

private const val MAX_SUGGESTIONS = 6
private const val MAX_SUGGESTIONS_COMPACT = 4

/** Fewest rows shown however short the window: the one Enter runs and the open-or-search row. */
private const val MIN_SUGGESTIONS = 2

/** A window shorter than this puts the popup near its top, as a narrow one does. */
private val SHORT_WINDOW_HEIGHT = 480.dp
private val FIELD_HEIGHT = 56.dp

/** A suggestion row (48dp) with the space after it. */
private val ROW_PITCH = 50.dp

/** Below the field: the divider, the list's padding, and a margin under the popup. */
private val LIST_CHROME = 33.dp

/** How far down the window the popup sits: near the top in a narrow or short window. */
internal fun goToAddressTopOffset(compact: Boolean, windowHeight: Dp): Dp =
    if (compact || windowHeight < SHORT_WINDOW_HEIGHT) 12.dp else 96.dp

/**
 * How many suggestion rows to show: 6, or 4 in a narrow window, fewer when the window above the
 * on-screen keyboard has no room for them (a phone held sideways), but never fewer than 2.
 */
internal fun goToAddressRowLimit(compact: Boolean, windowHeight: Dp, keyboardHeight: Dp): Int {
    val most = if (compact) MAX_SUGGESTIONS_COMPACT else MAX_SUGGESTIONS
    val room = windowHeight - keyboardHeight - goToAddressTopOffset(compact, windowHeight) - FIELD_HEIGHT - LIST_CHROME
    return (room / ROW_PITCH).toInt().coerceIn(MIN_SUGGESTIONS, most)
}

/**
 * Go to an address (Ctrl+G): the address bar lifted into the middle of the window, over a blur
 * of everything behind it. Starts with the focused page's address selected, ready to replace;
 * once something is typed, the rows of the command palette are offered, ranked the same way
 * (see [buildPaletteResults]): commands and saved hosts the text names, the typed address or
 * search, open tabs and history. Enter runs the highlighted row (the first, unless the arrow keys
 * moved it), Alt+Enter opens the typed text in a new tab; Esc, a click outside or Ctrl+G again
 * closes it.
 */
@Composable
fun GoToAddressDialog(
    /** The focused page's address, or "" for the start page, a terminal or settings. */
    currentAddress: String,
    tabs: List<BrowserTab>,
    history: List<HistoryEntry>,
    compact: Boolean,
    /** The command palette's commands; those not [PaletteCommand.inGoToAddress] are left out. */
    commands: List<PaletteCommand> = emptyList(),
    /** Saved SSH hosts, connected to from a matching row. */
    hosts: List<PaletteHost> = emptyList(),
    onNavigate: (String) -> Unit,
    onNavigateInNewTab: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    /** Enter was taken here: its release must not reach the page once the popup is gone. */
    onEnterTaken: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var value by remember { mutableStateOf(TextFieldValue(currentAddress, TextRange(0, currentAddress.length))) }
    var edited by remember { mutableStateOf(false) }
    var highlighted by remember { mutableIntStateOf(0) }
    val inputFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val text = value.text.trim()
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val keyboardHeight = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val rowLimit = goToAddressRowLimit(compact, windowHeight, keyboardHeight)

    val suggestions = remember(text, edited, tabs, history, commands, hosts, rowLimit) {
        if (!edited || text.isEmpty()) {
            emptyList()
        } else {
            limitKeepingGoRow(
                buildPaletteResults(text, commands.filter { it.inGoToAddress }, tabs, onNavigate, onSelectTab, history, hosts),
                limit = rowLimit
            )
        }
    }
    LaunchedEffect(suggestions.size) {
        highlighted = highlighted.coerceIn(0, (suggestions.size - 1).coerceAtLeast(0))
    }
    // Where the window is shorter still than [rowLimit] allowed for, the list scrolls: keep the
    // highlighted row in sight
    LaunchedEffect(highlighted, suggestions.size) {
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == highlighted }
        val inSight = item != null && item.offset >= layout.viewportStartOffset && item.offset + item.size <= layout.viewportEndOffset
        if (!inSight && highlighted < suggestions.size) listState.scrollToItem(highlighted)
    }
    LaunchedEffect(Unit) { runCatching { inputFocus.requestFocus() } }

    fun go() {
        if (text.isEmpty()) {
            onDismiss()
            return
        }
        onDismiss()
        val row = suggestions.getOrNull(highlighted)
        if (row != null) row.action() else onNavigate(text)
    }

    fun goInNewTab() {
        if (text.isEmpty()) return
        onDismiss()
        onNavigateInNewTab(text)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        TouchpadAsMouseInDialog()
        BlurBehindAndPlace(topOffset = goToAddressTopOffset(compact, windowHeight))
        Surface(
            modifier = Modifier
                .padding(horizontal = if (compact) 12.dp else 16.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .testTag("go_to_address"),
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
                        .height(FIELD_HEIGHT)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAddress = text.isNotEmpty() && !UrlResolver.resolveInput(text).startsWith(UrlResolver.DEFAULT_SEARCH_ENGINE_PREFIX)
                    Icon(
                        imageVector = if (isAddress) Icons.Outlined.Language else Icons.Outlined.Search,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    BasicTextField(
                        value = value,
                        onValueChange = { next ->
                            // A paste of several lines goes in as one, as in the address bar
                            val clean = if (next.text.any { it == '\n' || it == '\r' }) {
                                val joined = pastedOnOneLine(next.text)
                                TextFieldValue(joined, TextRange(joined.length))
                            } else {
                                next
                            }
                            if (clean.text != value.text) {
                                edited = true
                                highlighted = 0
                            }
                            value = clean
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                            autoCorrectEnabled = false
                        ),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(inputFocus)
                            .testTag("go_to_address_input")
                            .semantics { contentDescription = "Address or search" }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    event.key == Key.Escape -> {
                                        onDismiss()
                                        true
                                    }
                                    // Ctrl+G again closes it: the popup is its own window, so
                                    // the activity's shortcuts don't see the key here
                                    event.key == Key.G && event.isCtrlPressed -> {
                                        onDismiss()
                                        true
                                    }
                                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                        onEnterTaken()
                                        if (event.isAltPressed) goInNewTab() else go()
                                        true
                                    }
                                    event.key == Key.DirectionDown && suggestions.isNotEmpty() -> {
                                        highlighted = (highlighted + 1) % suggestions.size
                                        true
                                    }
                                    event.key == Key.DirectionUp && suggestions.isNotEmpty() -> {
                                        highlighted = (highlighted - 1 + suggestions.size) % suggestions.size
                                        true
                                    }
                                    else -> false
                                }
                            },
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.text.isEmpty()) {
                                    Text(
                                        text = "Search or enter address",
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
                if (suggestions.isNotEmpty()) {
                    HorizontalDivider(color = colors.outlineVariant)
                    // Takes the room left under the field, and scrolls if that is too little
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(suggestions, key = { _, row -> row.id }) { index, row ->
                            PaletteRow(
                                command = row,
                                isHighlighted = index == highlighted,
                                onClick = {
                                    onDismiss()
                                    row.action()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Puts the popup near the top of the window and blurs what is behind it. The system blurs it
 * (Android 12 and later, where the device allows cross-window blur), so web pages blur too
 * whatever way they are drawn; elsewhere, and while the device turns blur off (battery saver),
 * a plain dark scrim does instead.
 */
@Composable
private fun BlurBehindAndPlace(topOffset: Dp) {
    val view = LocalView.current
    val density = LocalDensity.current
    DisposableEffect(view, topOffset, density) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window == null) return@DisposableEffect onDispose {}
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = with(density) { topOffset.roundToPx() }
        }
        val blurRadius = with(density) { 24.dp.roundToPx() }
        fun applyBlur(enabled: Boolean) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = blurRadius }
                window.setDimAmount(0.25f)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.setDimAmount(0.6f)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val windowManager = window.windowManager
            applyBlur(windowManager.isCrossWindowBlurEnabled)
            val listener = Consumer<Boolean> { enabled -> applyBlur(enabled) }
            windowManager.addCrossWindowBlurEnabledListener(ContextCompat.getMainExecutor(view.context), listener)
            onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
        } else {
            applyBlur(false)
            onDispose {}
        }
    }
}
