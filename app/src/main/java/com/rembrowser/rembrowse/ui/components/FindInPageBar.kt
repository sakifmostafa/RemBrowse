package com.rembrowser.rembrowse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.engine.FindResult
import com.rembrowser.rembrowse.model.FindInPageState

/** Where the find bar sits in its pane, which other overlays there (the permission card) keep clear of. */
internal val FindBarInset = 10.dp
internal val FindBarMaxWidth = 420.dp
internal val FindBarHeight = 44.dp

/** Everything the find bar can ask for. */
data class FindInPageActions(
    val onQueryChange: (String) -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onMatchCaseChange: (Boolean) -> Unit,
    val onClose: () -> Unit
)

/**
 * The find bar of the page shown in this pane, if it is the one being searched: it drops into
 * the pane's top corner and lifts away when closed. Call it from the pane's own box, so in a
 * split the bar sits on the page it searches.
 */
@Composable
fun BoxScope.FindInPageOverlay(
    tabId: String,
    find: FindInPageState?,
    actions: FindInPageActions
) {
    val visible = find?.tabId == tabId
    // What was last shown here, so the bar can still be drawn while it animates away
    var shown by remember { mutableStateOf<FindInPageState?>(null) }
    if (visible) shown = find

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it } +
            fadeIn(tween(180)),
        exit = slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { -it / 2 } +
            fadeOut(tween(120)),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(FindBarInset)
    ) {
        shown?.let { FindInPageBar(state = it, actions = actions) }
    }
}

/**
 * Find in page: a floating pill with the search, how many matches there are, previous and next,
 * match case and close. Enter goes to the next match and Shift+Enter to the previous one; Esc
 * closes it. The count and outline turn to the error colour, gently, when nothing matches.
 */
@Composable
fun FindInPageBar(
    state: FindInPageState,
    actions: FindInPageActions,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val result = state.result
    val noMatches = state.query.isNotEmpty() && result != null && !result.found
    val hasMatches = result?.found == true

    val outline by animateColorAsState(
        targetValue = if (noMatches) colors.error.copy(alpha = 0.6f) else colors.outlineVariant,
        animationSpec = tween(180),
        label = "findOutline"
    )
    val countColor by animateColorAsState(
        targetValue = if (noMatches) colors.error else colors.onSurfaceVariant,
        animationSpec = tween(180),
        label = "findCount"
    )

    // The count stays as it was while the next answer is on its way, so it does not flicker
    var count by remember(state.tabId) { mutableStateOf("") }
    when {
        state.query.isEmpty() -> count = ""
        result != null -> count = findCountText(result)
    }

    var field by remember(state.tabId) { mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length))) }
    val focus = remember { FocusRequester() }
    // Opening the bar, or asking for it again, puts the cursor in it with the text selected
    LaunchedEffect(state.focusRequests) {
        if (state.focusRequests > 0) {
            field = field.copy(selection = TextRange(0, field.text.length))
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }

    Surface(
        modifier = modifier
            .widthIn(max = FindBarMaxWidth)
            .fillMaxWidth()
            .testTag("find_bar"),
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, outline),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .height(FindBarHeight)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            BasicTextField(
                value = field,
                onValueChange = { value ->
                    field = value
                    actions.onQueryChange(value.text)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // The on-screen keyboard's search key; a hardware Enter is handled below
                keyboardActions = KeyboardActions(onSearch = { actions.onNext() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            // Both halves of the press are taken, so the release reaches nothing else
                            Key.Enter, Key.NumPadEnter -> {
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.isShiftPressed) actions.onPrevious() else actions.onNext()
                                }
                                true
                            }
                            Key.Escape -> {
                                if (event.type == KeyEventType.KeyDown) actions.onClose()
                                true
                            }
                            // Back (once the on-screen keyboard is down) closes the bar, as in
                            // Chrome, instead of only taking the focus out of the box. On the
                            // release, as Android's own Back, so the press is not seen twice.
                            // Up to Android 12L. From Android 13 (predictive back) Android hands
                            // Back to the newest Back callback first (the on-screen keyboard's,
                            // or the find bar's BackHandler in RemBrowseScreen); Android 16 then
                            // still passes the key on, its release cancelled, and closing an
                            // already closed bar again changes nothing.
                            Key.Back -> {
                                if (event.type == KeyEventType.KeyUp) actions.onClose()
                                true
                            }
                            else -> false
                        }
                    }
                    .semantics { contentDescription = "Find in page" }
                    .testTag("find_input"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (field.text.isEmpty()) {
                            Text(
                                text = "Find in page",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                }
            )
            Text(
                text = count,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = countColor,
                maxLines = 1,
                modifier = Modifier
                    .animateContentSize(tween(160))
                    .padding(horizontal = if (count.isEmpty()) 0.dp else 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("find_status")
            )
            VerticalDivider(
                modifier = Modifier
                    .height(20.dp)
                    .padding(horizontal = 2.dp),
                color = colors.outlineVariant
            )
            RemIconButton(
                icon = Icons.Outlined.KeyboardArrowUp,
                label = "Previous match (Shift+Enter)",
                onClick = actions.onPrevious,
                enabled = hasMatches,
                size = 32.dp,
                modifier = Modifier.testTag("find_previous")
            )
            RemIconButton(
                icon = Icons.Outlined.KeyboardArrowDown,
                label = "Next match (Enter)",
                onClick = actions.onNext,
                enabled = hasMatches,
                size = 32.dp,
                modifier = Modifier.testTag("find_next")
            )
            MatchCaseToggle(checked = state.matchCase, onCheckedChange = actions.onMatchCaseChange)
            RemIconButton(
                icon = Icons.Outlined.Close,
                label = "Close (Esc)",
                onClick = actions.onClose,
                size = 32.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag("find_close")
            )
        }
    }
}

/** "Aa": filled in the accent colour while matching case. */
@Composable
private fun MatchCaseToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = if (checked) colors.primaryContainer else colors.surfaceContainerHigh,
        animationSpec = tween(160),
        label = "matchCaseBackground"
    )
    val content by animateColorAsState(
        targetValue = if (checked) colors.onPrimaryContainer else colors.onSurfaceVariant,
        animationSpec = tween(160),
        label = "matchCaseContent"
    )
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(32.dp)
            .keyboardFocusRing(MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = "Match case" }
            .testTag("find_match_case"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Aa",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = content
        )
    }
}

/** "3/12", "3/1000+" past the engine's count limit, or "No results". */
internal fun findCountText(result: FindResult): String = when {
    !result.found -> "No results"
    result.total < 0 -> "${result.current}/1000+"
    else -> "${result.current}/${result.total}"
}
