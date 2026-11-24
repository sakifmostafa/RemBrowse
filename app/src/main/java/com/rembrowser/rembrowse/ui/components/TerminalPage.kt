package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rembrowser.rembrowse.R
import com.rembrowser.rembrowse.data.TerminalSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.SshAuthMethod
import com.rembrowser.rembrowse.model.SshHost
import com.rembrowser.rembrowse.model.TerminalTarget
import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.rembrowser.rembrowse.model.TerminalUrls
import com.rembrowser.rembrowse.terminal.HostKeyCheck
import com.rembrowser.rembrowse.terminal.TerminalScreenView
import com.rembrowser.rembrowse.terminal.TerminalStatus
import com.rembrowser.rembrowse.terminal.TerminalTheme
import com.rembrowser.rembrowse.viewmodel.TerminalViewModel
import kotlin.math.roundToInt

private val TerminalBackground get() = Color(TerminalTheme.BACKGROUND)
private val TerminalForeground get() = Color(TerminalTheme.FOREGROUND)

/**
 * A terminal tab: a new one offers the saved hosts and a field for any `user@host`; once a server
 * is chosen it shows the terminal, with what the connection needs from the user on top of it.
 *
 * @param isActive the tab's pane has focus: a connected terminal then takes the keyboard.
 * @param onChooseTarget a server was chosen in a new terminal: the tab's address follows.
 */
@Composable
fun TerminalPage(
    tab: BrowserTab,
    terminal: TerminalViewModel,
    isActive: Boolean,
    onChooseTarget: (TerminalTarget) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** The cursor's colour: the accent, in its tone for dark backgrounds (terminals are always dark). */
    cursorColor: Color = Color(TerminalTheme.DEFAULT_CURSOR)
) {
    val target = remember(tab.url) { TerminalUrls.parse(tab.url) ?: TerminalTarget.Picker }
    val connection = remember(tab.id) { terminal.session(tab.id) }
    val status by connection.status.collectAsStateWithLifecycle()
    val state by terminal.uiState.collectAsStateWithLifecycle()

    val settings = state.settings
    val theme = settings.theme
    val themeBackground = Color(theme.background)
    if (target == TerminalTarget.Picker && status == TerminalStatus.Idle) {
        Box(modifier = modifier.testTag("terminal_page")) {
            HostPicker(
                hosts = state.hosts,
                onConnect = { chosen ->
                    onChooseTarget(chosen)
                    terminal.connect(tab.id, chosen)
                },
                onOpenSettings = onOpenSettings
            )
        }
        return
    }

    val savedHostForHeader = (target as? TerminalTarget.SavedHost)?.let { terminal.hostById(it.hostId) }
    var screen by remember { mutableStateOf<TerminalScreenView?>(null) }
    val cursorClaim = LocalChromePointerIconClaim.current
    Column(modifier = modifier.background(themeBackground).testTag("terminal_page")) {
        TerminalHeader(
            theme = theme,
            title = savedHostForHeader?.displayName ?: (target as? TerminalTarget.Address)?.display ?: "Terminal",
            address = savedHostForHeader?.address,
            status = status,
            fontSizeSp = settings.fontSizeSp,
            onTheme = terminal::setTheme,
            onFontSize = terminal::setFontSize,
            onConnect = { terminal.connect(tab.id, target) },
            onDisconnect = { connection.disconnect() },
            onOpenSettings = onOpenSettings
        )
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val density = LocalDensity.current
        val fontSizePx = with(density) { settings.fontSizeSp.sp.toPx() }
        var menuAt by remember { mutableStateOf<Offset?>(null) }

        AndroidView(
            factory = { context -> TerminalScreenView(context).also { screen = it } },
            update = { view ->
                view.connection = connection
                view.textSizePx = fontSizePx
                view.setAppearance(theme, cursorColor.toArgb(), settings.font, settings.cursorStyle)
                view.suggest = if (settings.suggestions) terminal::suggestCommand else null
                view.onCommandRun = terminal::recordCommand
                view.onContextMenu = { x, y -> menuAt = Offset(x, y) }
                view.onReconnectRequested = { terminal.connect(tab.id, target) }
                // Over the cards and banners drawn on top, and a divider's grab area, the
                // cursor is theirs, not the text cursor
                view.chromeHasPointer = cursorClaim::isClaimed
            },
            onRelease = { view -> view.connection = null },
            modifier = Modifier.fillMaxSize().testTag("terminal_screen")
        )

        // A connected terminal in the focused pane takes the keyboard
        LaunchedEffect(status is TerminalStatus.Connected, isActive, screen) {
            if (status is TerminalStatus.Connected && isActive) screen?.requestFocus()
        }

        val savedHost = (target as? TerminalTarget.SavedHost)?.let { terminal.hostById(it.hostId) }
        val label = savedHost?.displayName ?: (target as? TerminalTarget.Address)?.display ?: "Terminal"
        when (val current = status) {
            TerminalStatus.Idle -> CenterCard {
                CardBadge(Icons.Outlined.Terminal)
                CardTitle(label)
                savedHost?.let { CardText(it.address, monospace = true) }
                Spacer(Modifier.height(16.dp))
                PrimaryDialogButton("Connect", modifier = Modifier.testTag("terminal_connect")) {
                    terminal.connect(tab.id, target)
                }
            }
            is TerminalStatus.Connecting -> TopBanner {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text("Connecting to ${current.label}…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { connection.disconnect() }) { Text("Cancel") }
            }
            is TerminalStatus.ConfirmingHostKey -> HostKeyCard(
                check = current.check,
                onAnswer = connection::answerHostKey
            )
            is TerminalStatus.NeedsPassword -> PasswordCard(
                status = current,
                username = savedHost?.username ?: (target as? TerminalTarget.Address)?.username,
                onAnswer = connection::answerPassword
            )
            is TerminalStatus.Connected -> Unit
            is TerminalStatus.Disconnected -> if (current.failedToConnect) {
                CenterCard(testTag = "terminal_failed") {
                    CardBadge(Icons.Outlined.ErrorOutline, error = true)
                    CardTitle("Couldn't connect to ${current.label}")
                    current.reason?.let { CardText(it) }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (savedHost != null || target is TerminalTarget.SavedHost) {
                            RemOutlinedButton(onClick = onOpenSettings) { Text("Edit host") }
                        }
                        PrimaryDialogButton("Try again", modifier = Modifier.testTag("terminal_retry")) {
                            terminal.connect(tab.id, target)
                        }
                    }
                }
            } else {
                BottomBanner(testTag = "terminal_disconnected") {
                    Text(
                        text = current.reason?.let { "Disconnected: $it" } ?: "Disconnected",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Enter to reconnect", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    PrimaryDialogButton("Reconnect", modifier = Modifier.testTag("terminal_reconnect")) {
                        terminal.connect(tab.id, target)
                    }
                }
            }
        }

        menuAt?.let { at ->
            TerminalMenu(
                at = at,
                canCopy = screen?.hasSelection == true,
                connected = status is TerminalStatus.Connected,
                onCopy = { screen?.copySelection() },
                onPaste = { screen?.paste() },
                onSelectAll = { screen?.selectAll() },
                onDisconnect = { connection.disconnect() },
                onReconnect = { terminal.connect(tab.id, target) },
                onDismiss = {
                    menuAt = null
                    screen?.requestFocus()
                }
            )
        }
    }
    }
}

/** A new terminal: the saved hosts, and a field for any server. */
@Composable
private fun HostPicker(hosts: List<SshHost>, onConnect: (TerminalTarget) -> Unit, onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var address by remember { mutableStateOf("") }
    val parsed = TerminalUrls.parseAddress(address)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Surface(color = colors.surfaceContainerLowest, contentColor = colors.onSurface, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(Icons.Outlined.Terminal, size = 48.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("New terminal", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Connect over SSH to one of your hosts, or to any server.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))

                // Quick connect: one pill, like the address bar
                val invalid = address.isNotBlank() && parsed == null
                var focused by remember { mutableStateOf(false) }
                val outline = when {
                    invalid -> colors.error
                    focused -> colors.primary
                    else -> colors.outlineVariant
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(colors.surface)
                        .border(if (focused || invalid) 1.5.dp else 1.dp, outline, RoundedCornerShape(26.dp))
                        .padding(start = 18.dp, end = 6.dp)
                ) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
                        if (address.isEmpty()) {
                            Text(
                                "user@host or ssh user@host -p 2222",
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = TerminalMono,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // The TextFieldState BasicTextField replaces this one; moving to it is a change of its own
                        @Suppress("DEPRECATION")
                        BasicTextField(
                            value = address,
                            onValueChange = { address = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = TerminalMono, color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { parsed?.let(onConnect) }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .onFocusChanged { focused = it.isFocused }
                                .semantics { contentDescription = "Connect to" }
                                .testTag("terminal_quick_connect")
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    PrimaryDialogButton("Connect", enabled = parsed != null, modifier = Modifier.testTag("terminal_quick_connect_go")) {
                        parsed?.let(onConnect)
                    }
                }
                if (invalid) {
                    Text(
                        "Type it as user@host, with :port if it isn't 22",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.padding(start = 20.dp, top = 6.dp)
                    )
                }
                Spacer(Modifier.height(32.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved hosts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("terminal_manage_hosts")) {
                        Text(if (hosts.isEmpty()) "Add a host" else "Manage hosts")
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (hosts.isEmpty()) {
                    EmptyHosts(onOpenSettings)
                } else {
                    // Two columns of cards; a lone last card keeps its width
                    hosts.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            pair.forEach { host ->
                                HostCard(host, onClick = { onConnect(TerminalTarget.SavedHost(host.id)) }, modifier = Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** A saved host: its initial, name, address and how it signs in. Outlined in the accent on hover. */
@Composable
private fun HostCard(host: SshHost, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (hovered) colors.surfaceContainerHigh else colors.surface,
        border = BorderStroke(1.dp, if (hovered) colors.primary else colors.outlineVariant),
        modifier = modifier
            .hoverable(interaction)
            .clip(MaterialTheme.shapes.large)
            .clickable(
                interactionSource = interaction,
                indication = ripple(focusRingShape = MaterialTheme.shapes.large),
                onClick = onClick
            )
            .testTag("terminal_pick_${host.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialBadge(host.displayName)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(host.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        host.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = TerminalMono,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val (icon, signIn) = when {
                host.auth == SshAuthMethod.KEY -> Icons.Outlined.Key to "Key"
                host.hasSavedPassword -> Icons.Outlined.Lock to "Saved password"
                else -> Icons.Outlined.Password to "Asks for password"
            }
            Chip(icon, signIn)
        }
    }
}

@Composable
private fun EmptyHosts(onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 32.dp, horizontal = 24.dp)) {
            Badge(Icons.Outlined.Dns, size = 44.dp)
            Spacer(Modifier.height(12.dp))
            Text("No saved hosts yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Save the servers you use, with how you sign in to each, and they appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(16.dp))
            RemOutlinedButton(onClick = onOpenSettings) { Text("Add a host") }
        }
    }
}

/** A new server, or one whose key changed: the user decides. */
@Composable
private fun BoxScope.HostKeyCard(check: HostKeyCheck, onAnswer: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    CenterCard(testTag = if (check.changed) "terminal_host_key_changed" else "terminal_host_key_new") {
        if (check.changed) {
            CardBadge(Icons.Outlined.GppMaybe, error = true)
            CardTitle("The server's identity has changed")
            CardText(
                "${check.hostname}:${check.port} now shows a different key from the one you accepted before. " +
                    "This happens when a server is reinstalled, but it can also mean someone is intercepting the connection. " +
                    "Only continue if you know why it changed."
            )
            check.previousFingerprint?.let { Fingerprint("Before", it) }
            Fingerprint("Now", check.fingerprint)
        } else {
            CardBadge(Icons.Outlined.VerifiedUser)
            CardTitle("First connection to ${check.hostname}")
            CardText("Check that this is the server's key before you go on. On the server, ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub prints its fingerprints.")
            Fingerprint(check.keyType, check.fingerprint)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemOutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.testTag("host_key_cancel")) { Text("Cancel") }
            PrimaryDialogButton(
                if (check.changed) "Connect anyway" else "Trust and connect",
                modifier = Modifier.testTag("host_key_accept")
            ) { onAnswer(true) }
        }
    }
}

@Composable
private fun Fingerprint(label: String, fingerprint: String) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Surface(
            shape = MaterialTheme.shapes.small,
            color = TerminalBackground,
            contentColor = TerminalForeground,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Text(
                fingerprint,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = TerminalMono,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag("host_key_fingerprint")
            )
        }
    }
}

@Composable
private fun BoxScope.PasswordCard(status: TerminalStatus.NeedsPassword, username: String?, onAnswer: (String?, Boolean) -> Unit) {
    var password by remember(status) { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(status) { runCatching { focus.requestFocus() } }
    CenterCard(testTag = "terminal_password") {
        CardBadge(Icons.Outlined.Lock)
        CardTitle(username?.let { "Password for $it" } ?: "Password")
        CardText(if (status.retry) "That didn't work. Try again." else status.label, monospace = !status.retry)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            // Deprecated with value-based text fields; SecureTextField needs TextFieldState, a change of its own
            visualTransformation = @Suppress("DEPRECATION") PasswordVisualTransformation(),
            isError = status.retry,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onAnswer(password, remember) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("terminal_password_field")
        )
        if (status.canRemember) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .toggleable(
                        value = remember,
                        interactionSource = null,
                        indication = ripple(focusRingShape = MaterialTheme.shapes.small),
                        role = Role.Checkbox,
                        onValueChange = { remember = it }
                    )
                    .testTag("terminal_password_remember")
            ) {
                Checkbox(checked = remember, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text("Remember on this device", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemOutlinedButton(onClick = { onAnswer(null, false) }) { Text("Cancel") }
            PrimaryDialogButton("Connect", enabled = password.isNotEmpty(), modifier = Modifier.testTag("terminal_password_go")) {
                onAnswer(password, remember)
            }
        }
    }
}

@Composable
private fun BoxScope.TerminalMenu(
    at: Offset,
    canCopy: Boolean,
    connected: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    Box(modifier = Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.size(0.dp)) {
        RemDropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            offset = DpOffset.Zero,
            properties = PopupProperties(focusable = false),
            modifier = Modifier.testTag("terminal_menu")
        ) {
            DropdownMenuItem(
                text = { Text("Copy") },
                trailingIcon = { ShortcutHint("Ctrl+Shift+C") },
                enabled = canCopy,
                onClick = run(onCopy),
                modifier = Modifier.testTag("terminal_menu_copy")
            )
            DropdownMenuItem(
                text = { Text("Paste") },
                trailingIcon = { ShortcutHint("Ctrl+Shift+V") },
                enabled = connected,
                onClick = run(onPaste),
                modifier = Modifier.testTag("terminal_menu_paste")
            )
            DropdownMenuItem(text = { Text("Select all") }, onClick = run(onSelectAll), modifier = Modifier.testTag("terminal_menu_select_all"))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (connected) {
                DropdownMenuItem(text = { Text("Disconnect") }, onClick = run(onDisconnect), modifier = Modifier.testTag("terminal_menu_disconnect"))
            } else {
                DropdownMenuItem(text = { Text("Reconnect") }, onClick = run(onReconnect), modifier = Modifier.testTag("terminal_menu_reconnect"))
            }
        }
    }
}

// --- Cards and banners over the terminal ---

@Composable
private fun BoxScope.CenterCard(testTag: String? = null, content: @Composable () -> Unit) {
    val claim = LocalChromePointerIconClaim.current
    val claimant = rememberPointerIconClaimant(claim)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp)
            .widthIn(max = 480.dp)
            .claimsPointerIcon(claim, claimant)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            content()
        }
    }
}

/** An icon on a tinted square, as the host cards and Settings show them. */
@Composable
private fun CardBadge(icon: ImageVector, error: Boolean = false) {
    Badge(icon, size = 44.dp, error = error)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun CardText(text: String, monospace: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = if (monospace) TerminalMono else null,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun BoxScope.TopBanner(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val claim = LocalChromePointerIconClaim.current
    val claimant = rememberPointerIconClaimant(claim)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp,
        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).widthIn(max = 560.dp)
            .claimsPointerIcon(claim, claimant).testTag("terminal_connecting")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), content = content)
    }
}

@Composable
private fun BoxScope.BottomBanner(testTag: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val claim = LocalChromePointerIconClaim.current
    val claimant = rememberPointerIconClaimant(claim)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = TerminalForeground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 640.dp).fillMaxWidth()
            .claimsPointerIcon(claim, claimant).testTag(testTag)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), content = content)
    }
}

/** The terminal's typeface, JetBrains Mono, for addresses and fingerprints around it too. */
internal val TerminalMono = FontFamily(Font(R.font.jetbrains_mono))

/** An icon on a rounded square of the accent's container colour (or the error's). */
@Composable
internal fun Badge(icon: ImageVector, size: Dp = 40.dp, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(if (error) colors.errorContainer else colors.primaryContainer, RoundedCornerShape(size * 0.28f))
    ) {
        Icon(icon, contentDescription = null, tint = if (error) colors.onErrorContainer else colors.onPrimaryContainer, modifier = Modifier.size(size * 0.5f))
    }
}

/** A host's initial on a rounded square, so saved hosts are told apart at a glance. */
@Composable
internal fun InitialBadge(name: String, size: Dp = 40.dp) {
    val colors = MaterialTheme.colorScheme
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: ">"
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).background(colors.primaryContainer, RoundedCornerShape(size * 0.28f))
    ) {
        Text(initial, style = MaterialTheme.typography.titleMedium, fontFamily = TerminalMono, color = colors.onPrimaryContainer)
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(colors.surfaceContainerHighest, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

/**
 * The strip above a terminal, in the terminal theme's own colours: whether it is connected, to
 * which server, and quick controls (text size, theme, disconnect or reconnect, settings).
 */
@Composable
private fun TerminalHeader(
    theme: TerminalThemePreset,
    title: String,
    address: String?,
    status: TerminalStatus,
    fontSizeSp: Int,
    onTheme: (TerminalThemePreset) -> Unit,
    onFontSize: (Int) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val background = Color(theme.background)
    // Neutral text and icons (the theme's white, or its black on a light theme), not the theme's
    // text colour: a green-on-navy theme shouldn't turn the bar green
    val chrome = Color(if (theme.isLight) theme.ansi[0] else theme.ansi[15])
    val headerBackground = chrome.copy(alpha = 0.05f).compositeOver(background)
    val dim = chrome.copy(alpha = 0.62f)
    val (dot, state) = when (status) {
        is TerminalStatus.Connected -> Color(theme.ansi[2]) to "Connected"
        is TerminalStatus.Connecting, is TerminalStatus.ConfirmingHostKey, is TerminalStatus.NeedsPassword ->
            Color(theme.ansi[3]) to "Connecting"
        is TerminalStatus.Disconnected -> Color(theme.ansi[1]) to "Disconnected"
        TerminalStatus.Idle -> chrome.copy(alpha = 0.30f) to "Not connected"
    }
    var menuOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // In a narrow pane the address goes before the name is cut short
        val showAddress = address != null && address != title && maxWidth >= HEADER_ADDRESS_MIN_WIDTH
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(headerBackground)
                .padding(start = 16.dp, end = 6.dp)
                .testTag("terminal_header")
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dot, CircleShape)
                    .semantics { contentDescription = state }
            )
            Spacer(Modifier.width(10.dp))
            // The name and address take what the buttons leave, and the address gives way first
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(title, color = chrome, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showAddress) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        address,
                        color = dim,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = TerminalMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            Box {
                RemIconButton(
                    icon = Icons.Outlined.Palette,
                    label = "Theme and text size",
                    tint = dim,
                    size = 32.dp,
                    iconSize = 18.dp,
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("terminal_theme_button")
                )
                RemDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.width(240.dp).testTag("terminal_theme_menu")
                ) {
                    TextSizeRow(fontSizeSp = fontSizeSp, onFontSize = onFontSize)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TerminalThemePreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            leadingIcon = { ThemeSwatch(preset, width = 30.dp, height = 22.dp) },
                            text = { Text(preset.label) },
                            trailingIcon = {
                                if (preset == theme) Icon(Icons.Outlined.Check, contentDescription = "Current", tint = MaterialTheme.colorScheme.primary)
                            },
                            onClick = {
                                menuOpen = false
                                onTheme(preset)
                            },
                            modifier = Modifier.testTag("terminal_theme_${preset.name.lowercase()}")
                        )
                    }
                }
            }
            when (status) {
                is TerminalStatus.Connected, is TerminalStatus.Connecting -> RemIconButton(
                    icon = Icons.Outlined.LinkOff,
                    label = "Disconnect",
                    tint = dim,
                    size = 32.dp,
                    iconSize = 18.dp,
                    onClick = onDisconnect,
                    modifier = Modifier.testTag("terminal_header_disconnect")
                )
                is TerminalStatus.Disconnected, TerminalStatus.Idle -> RemIconButton(
                    icon = Icons.Outlined.Refresh,
                    label = "Connect",
                    tint = dim,
                    size = 32.dp,
                    iconSize = 18.dp,
                    onClick = onConnect,
                    modifier = Modifier.testTag("terminal_header_connect")
                )
                else -> Unit
            }
            RemIconButton(
                icon = Icons.Outlined.Settings,
                label = "Terminal settings",
                tint = dim,
                size = 32.dp,
                iconSize = 18.dp,
                onClick = onOpenSettings
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(chrome.copy(alpha = 0.08f)))
}

/** Below this width the header shows the host's name without its address. */
private val HEADER_ADDRESS_MIN_WIDTH = 360.dp

/** "Text size  −  14  +" at the top of the theme menu. The menu stays open while it changes. */
@Composable
private fun TextSizeRow(fontSizeSp: Int, onFontSize: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = 16.dp, end = 8.dp)
            .testTag("terminal_text_size")
    ) {
        Text("Text size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        RemIconButton(
            icon = Icons.Outlined.Remove,
            label = "Smaller text",
            enabled = fontSizeSp > TerminalSettings.MIN_FONT_SIZE_SP,
            size = 32.dp,
            iconSize = 18.dp,
            onClick = { onFontSize(fontSizeSp - 1) },
            modifier = Modifier.testTag("terminal_text_smaller")
        )
        Text(
            "$fontSizeSp",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = TerminalMono,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp)
        )
        RemIconButton(
            icon = Icons.Outlined.Add,
            label = "Larger text",
            enabled = fontSizeSp < TerminalSettings.MAX_FONT_SIZE_SP,
            size = 32.dp,
            iconSize = 18.dp,
            onClick = { onFontSize(fontSizeSp + 1) },
            modifier = Modifier.testTag("terminal_text_larger")
        )
    }
}

/**
 * A theme in miniature: its background, with a prompt in its green and text in its colours. For
 * the theme menu and Settings.
 */
@Composable
internal fun ThemeSwatch(theme: TerminalThemePreset, width: Dp, height: Dp) {
    val background = Color(theme.background)
    val foreground = Color(theme.foreground)
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, foreground.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.Center)
        ) {
            listOf(theme.ansi[1], theme.ansi[2], theme.ansi[4]).forEach { color ->
                Box(Modifier.size(height * 0.24f).background(Color(color), CircleShape))
            }
        }
    }
}
