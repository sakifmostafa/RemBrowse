package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rembrowser.rembrowse.data.TerminalSettings
import com.rembrowser.rembrowse.model.DEFAULT_SSH_PORT
import com.rembrowser.rembrowse.model.KnownHost
import com.rembrowser.rembrowse.model.SshAuthMethod
import com.rembrowser.rembrowse.model.SshHost
import com.rembrowser.rembrowse.model.SshKeyInfo
import com.rembrowser.rembrowse.model.TerminalCursorStyle
import com.rembrowser.rembrowse.model.TerminalFont
import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.rembrowser.rembrowse.viewmodel.TerminalUiState

/** What Settings → Terminal can do. */
data class TerminalSettingsActions(
    val onSaveHost: (host: SshHost, password: String?, forgetPassword: Boolean) -> Unit,
    val onDeleteHost: (hostId: String) -> Unit,
    val onConnectHost: (SshHost) -> Unit,
    val onGenerateKey: (name: String) -> SshKeyInfo,
    val onImportKey: (name: String, privateKey: String, passphrase: String?) -> Result<SshKeyInfo>,
    val onDeleteKey: (keyId: String) -> Unit,
    val onForgetKnownHost: (hostPort: String) -> Unit,
    val onSetFontSize: (Int) -> Unit,
    val onSetTheme: (TerminalThemePreset) -> Unit = {},
    val onSetFont: (TerminalFont) -> Unit = {},
    val onSetCursorStyle: (TerminalCursorStyle) -> Unit = {},
    val onSetSuggestions: (Boolean) -> Unit = {},
    val onSetRememberCommands: (Boolean) -> Unit = {},
    val onClearCommandHistory: () -> Unit = {}
)

/**
 * Settings → Terminal: the saved hosts, the keys for signing in, the servers whose identity was
 * accepted, and the text size.
 */
@Composable
fun TerminalSettingsSection(state: TerminalUiState, actions: TerminalSettingsActions) {
    val colors = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<SshHost?>(null) }
    var generating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var showingKey by remember { mutableStateOf<SshKeyInfo?>(null) }

    Text(
        text = "Open a terminal with Ctrl+Shift+T, from More → New terminal, the command palette (Ctrl+K), or Connect below. " +
            "Passwords and keys are encrypted on this device and never leave it.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(20.dp))

    // --- Hosts ---
    SubsectionTitle("Hosts", actionLabel = "Add host", actionTag = "terminal_add_host") {
        editing = SshHost(id = "", label = "", hostname = "", username = "")
    }
    if (state.hosts.isEmpty()) {
        EmptyNote("No hosts yet. Add the servers you connect to, with how you sign in to each.")
    }
    state.hosts.forEach { host ->
        HostRow(
            host = host,
            keyName = host.keyId?.let { id -> state.keys.firstOrNull { it.id == id }?.name },
            onConnect = { actions.onConnectHost(host) },
            onEdit = { editing = host },
            onDelete = { actions.onDeleteHost(host.id) }
        )
    }
    SectionDivider()

    // --- Appearance ---
    Text(text = "Appearance", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(10.dp))
    TerminalThemePreset.entries.chunked(4).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            row.forEach { theme ->
                ThemeCard(
                    theme = theme,
                    font = state.settings.font,
                    selected = theme == state.settings.theme,
                    onClick = { actions.onSetTheme(theme) },
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    OptionRow(
        title = "Font",
        options = TerminalFont.entries,
        selected = state.settings.font,
        label = { it.label },
        onSelect = actions.onSetFont,
        tagPrefix = "terminal_font"
    )
    OptionRow(
        title = "Cursor",
        options = TerminalCursorStyle.entries,
        selected = state.settings.cursorStyle,
        label = { it.label },
        onSelect = actions.onSetCursorStyle,
        tagPrefix = "terminal_cursor"
    )
    FontSizeRow(sizeSp = state.settings.fontSizeSp, onChange = actions.onSetFontSize)
    SectionDivider()

    // --- Suggestions ---
    Text(text = "Suggestions", style = MaterialTheme.typography.titleSmall)
    ToggleRow(
        title = "Suggest commands as you type",
        description = "Dimmed text after the cursor shows how a command you ran before, or a common one, might end. " +
            "Tab or → takes it; with no suggestion, Tab completes in the shell as usual.",
        checked = state.settings.suggestions,
        onChange = actions.onSetSuggestions,
        testTag = "terminal_suggestions"
    )
    ToggleRow(
        title = "Remember commands I run",
        description = "Kept on this device only, and never what you type at a password prompt. A command " +
            "typed with a space in front isn't kept either, the way shells keep secrets out of their history." +
            if (state.rememberedCommands > 0) " ${state.rememberedCommands} remembered." else "",
        checked = state.settings.rememberCommands,
        onChange = actions.onSetRememberCommands,
        testTag = "terminal_remember_commands"
    )
    if (state.rememberedCommands > 0) {
        TextButton(onClick = actions.onClearCommandHistory, modifier = Modifier.testTag("terminal_clear_history")) {
            Text("Forget remembered commands")
        }
    }
    SectionDivider()

    // --- Keys ---
    SubsectionTitle("Keys", actionLabel = "Generate key", actionTag = "terminal_generate_key") { generating = true }
    Text(
        text = "Sign in without a password: add a key's public half to ~/.ssh/authorized_keys on the server.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    state.keys.forEach { key ->
        KeyRow(key = key, onShow = { showingKey = key }, onDelete = { actions.onDeleteKey(key.id) })
    }
    TextButton(onClick = { importing = true }, modifier = Modifier.testTag("terminal_import_key")) {
        Text("Import a private key…")
    }
    SectionDivider()

    // --- Accepted servers ---
    Text(text = "Trusted servers", style = MaterialTheme.typography.titleSmall)
    Text(
        text = "Servers whose identity you accepted. If one ever shows a different key, the terminal warns you before connecting.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
    )
    if (state.knownHosts.isEmpty()) EmptyNote("None yet.")
    state.knownHosts.forEach { known -> KnownHostRow(known = known, onForget = { actions.onForgetKnownHost(known.hostPort) }) }


    editing?.let { host ->
        HostEditorDialog(
            initial = host,
            keys = state.keys,
            onDismiss = { editing = null },
            onSave = { saved, password, forget ->
                actions.onSaveHost(saved, password, forget)
                editing = null
            },
            onGenerateKey = { generating = true }
        )
    }
    if (generating) {
        GenerateKeyDialog(
            onDismiss = { generating = false },
            onGenerate = { name ->
                generating = false
                showingKey = actions.onGenerateKey(name)
            }
        )
    }
    if (importing) {
        ImportKeyDialog(onDismiss = { importing = false }, onImport = actions.onImportKey, onImported = {
            importing = false
            showingKey = it
        })
    }
    showingKey?.let { key -> PublicKeyDialog(key = key, onDismiss = { showingKey = null }) }
}

@Composable
private fun SubsectionTitle(title: String, actionLabel: String, actionTag: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction, modifier = Modifier.testTag(actionTag)) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(actionLabel)
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun HostRow(host: SshHost, keyName: String?, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("terminal_host_${host.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        ) {
            InitialBadge(host.displayName, size = 36.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(host.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val signIn = when (host.auth) {
                    SshAuthMethod.KEY -> "key ${keyName ?: "(missing)"}"
                    SshAuthMethod.PASSWORD -> if (host.hasSavedPassword) "saved password" else "password"
                }
                Text(
                    "${host.address} · $signIn",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = TerminalMono,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onConnect, modifier = Modifier.testTag("terminal_connect_${host.id}")) { Text("Connect") }
            RemIconButton(icon = Icons.Outlined.Edit, label = "Edit ${host.displayName}", onClick = onEdit)
            RemIconButton(icon = Icons.Outlined.Delete, label = "Delete ${host.displayName}", onClick = onDelete)
        }
    }
}

@Composable
private fun KeyRow(key: SshKeyInfo, onShow: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onShow)
            .testTag("terminal_key_${key.id}")
            .padding(vertical = 4.dp)
    ) {
        Badge(Icons.Outlined.Key, size = 36.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(key.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${key.type} · ${key.fingerprint}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = TerminalMono,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onShow) { Text("Public key") }
        RemIconButton(icon = Icons.Outlined.Delete, label = "Delete key ${key.name}", onClick = onDelete)
    }
}

@Composable
private fun KnownHostRow(known: KnownHost, onForget: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("terminal_known_${known.hostPort}")
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(known.hostPort, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${known.keyType} · ${known.fingerprint}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = TerminalMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onForget) { Text("Forget") }
    }
}

@Composable
private fun FontSizeRow(sizeSp: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Text size", style = MaterialTheme.typography.bodyLarge)
            Text("${sizeSp}sp", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RemIconButton(
            icon = Icons.Outlined.Remove,
            label = "Smaller text",
            enabled = sizeSp > TerminalSettings.MIN_FONT_SIZE_SP,
            onClick = { onChange(sizeSp - 1) }
        )
        RemIconButton(
            icon = Icons.Outlined.Add,
            label = "Larger text",
            enabled = sizeSp < TerminalSettings.MAX_FONT_SIZE_SP,
            onClick = { onChange(sizeSp + 1) }
        )
    }
}

/** Adding or editing a host. The password field is only for saving one; leave it empty to be asked. */
@Composable
private fun HostEditorDialog(
    initial: SshHost,
    keys: List<SshKeyInfo>,
    onDismiss: () -> Unit,
    onSave: (SshHost, String?, Boolean) -> Unit,
    onGenerateKey: () -> Unit
) {
    var label by remember { mutableStateOf(initial.label) }
    var hostname by remember { mutableStateOf(initial.hostname) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var auth by remember { mutableStateOf(initial.auth) }
    var keyId by remember { mutableStateOf(initial.keyId ?: keys.firstOrNull()?.id) }
    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(initial.hasSavedPassword) }

    val portNumber = port.toIntOrNull()
    val valid = hostname.isNotBlank() && username.isNotBlank() && portNumber != null && portNumber in 1..65535 &&
        (auth == SshAuthMethod.PASSWORD || keyId != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "Add host" else "Edit host") },
        text = {
            TouchpadAsMouseInDialog()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("host_label")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = { hostname = it.trim() },
                        label = { Text("Host") },
                        placeholder = { Text("example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f).testTag("host_hostname")
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        isError = portNumber == null || portNumber !in 1..65535,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp).testTag("host_port")
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("host_username")
                )
                Text("Sign in with", style = MaterialTheme.typography.labelLarge)
                Column(modifier = Modifier.selectableGroup()) {
                    ChoiceRow("Password", auth == SshAuthMethod.PASSWORD, "host_auth_password") { auth = SshAuthMethod.PASSWORD }
                    ChoiceRow("Key", auth == SshAuthMethod.KEY, "host_auth_key") { auth = SshAuthMethod.KEY }
                }
                if (auth == SshAuthMethod.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (initial.hasSavedPassword) "New password (leave empty to keep)" else "Password (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("host_password")
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .toggleable(value = savePassword, role = Role.Checkbox, onValueChange = { savePassword = it })
                            .testTag("host_save_password")
                    ) {
                        Checkbox(checked = savePassword, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save the password on this device", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (keys.isEmpty()) {
                    Text("No keys yet.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onGenerateKey) { Text("Generate a key") }
                } else {
                    Column(modifier = Modifier.selectableGroup()) {
                        keys.forEach { key ->
                            ChoiceRow(key.name, keyId == key.id, "host_key_${key.id}") { keyId = key.id }
                        }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryDialogButton("Save", enabled = valid, modifier = Modifier.testTag("host_save")) {
                val host = initial.copy(
                    label = label,
                    hostname = hostname,
                    port = portNumber ?: DEFAULT_SSH_PORT,
                    username = username,
                    auth = auth,
                    keyId = if (auth == SshAuthMethod.KEY) keyId else null
                )
                val keepPassword = auth == SshAuthMethod.PASSWORD && savePassword
                onSave(host, password.takeIf { keepPassword && it.isNotEmpty() }, !keepPassword)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, testTag: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(testTag)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GenerateKeyDialog(onDismiss: () -> Unit, onGenerate: (String) -> Unit) {
    var name by remember { mutableStateOf("googlebook") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate a key") },
        text = {
            TouchpadAsMouseInDialog()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "An Ed25519 key, the kind ssh-keygen makes today. Its private half stays encrypted on this device.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("key_name")
                )
            }
        },
        confirmButton = {
            PrimaryDialogButton("Generate", enabled = name.isNotBlank(), modifier = Modifier.testTag("key_generate")) { onGenerate(name) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ImportKeyDialog(
    onDismiss: () -> Unit,
    onImport: (String, String, String?) -> Result<SshKeyInfo>,
    onImported: (SshKeyInfo) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a private key") },
        text = {
            TouchpadAsMouseInDialog()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("import_name")
                )
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = {
                        privateKey = it
                        error = null
                    },
                    label = { Text("Private key") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = TerminalMono),
                    modifier = Modifier.fillMaxWidth().testTag("import_key")
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (if it has one)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("import_passphrase")
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            PrimaryDialogButton("Import", enabled = privateKey.isNotBlank(), modifier = Modifier.testTag("import_confirm")) {
                onImport(name.ifBlank { "Imported key" }, privateKey, passphrase.ifEmpty { null }).fold(
                    onSuccess = onImported,
                    onFailure = { error = it.message ?: "The key couldn't be imported." }
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A key's public half, to copy onto servers. */
@Composable
private fun PublicKeyDialog(key: SshKeyInfo, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.name) },
        text = {
            TouchpadAsMouseInDialog()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Add this line to ~/.ssh/authorized_keys on each server you want to reach with this key " +
                        "(for example with ssh-copy-id, or by pasting it into the file).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        key.publicKey,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = TerminalMono,
                        modifier = Modifier.padding(10.dp).testTag("public_key_text")
                    )
                }
                Text(key.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = TerminalMono)
            }
        },
        confirmButton = {
            PrimaryDialogButton(if (copied) "Copied" else "Copy", modifier = Modifier.testTag("public_key_copy")) {
                clipboard.setText(AnnotatedString(key.publicKey))
                copied = true
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        icon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) }
    )
}

/** A theme as a little terminal: a prompt, a listing and a suggestion, in its colours and the chosen font. */
@Composable
private fun ThemeCard(theme: TerminalThemePreset, font: TerminalFont, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val background = Color(theme.background)
    val foreground = Color(theme.foreground)
    val family = when (font) {
        TerminalFont.JETBRAINS_MONO -> TerminalFont_JetBrains
        TerminalFont.FIRA_MONO -> TerminalFont_Fira
        TerminalFont.SYSTEM -> FontFamily.Monospace
    }
    val line = MaterialTheme.typography.labelSmall.copy(fontFamily = family, fontSize = 10.sp, lineHeight = 14.sp)
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("terminal_theme_card_${theme.name.lowercase()}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(background)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) colors.primary else foreground.copy(alpha = 0.15f),
                    MaterialTheme.shapes.medium
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color(theme.ansi[10]), fontWeight = FontWeight.Bold)) { append("me@box") }
                    withStyle(SpanStyle(color = foreground)) { append(":") }
                    withStyle(SpanStyle(color = Color(theme.ansi[12]), fontWeight = FontWeight.Bold)) { append("~") }
                    withStyle(SpanStyle(color = foreground)) { append("$ ls") }
                },
                style = line,
                maxLines = 1
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color(theme.ansi[12]), fontWeight = FontWeight.Bold)) { append("app  docs  ") }
                    withStyle(SpanStyle(color = Color(theme.ansi[10]))) { append("run.sh") }
                },
                style = line,
                maxLines = 1
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = foreground)) { append("$ git ") }
                    withStyle(SpanStyle(color = foreground.copy(alpha = 0.45f))) { append("status") }
                },
                style = line,
                maxLines = 1
            )
        }
        Text(
            theme.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.primary else colors.onSurface,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp)
        )
    }
}

/** A setting with a few choices, as a row of chips. */
@Composable
private fun <T> OptionRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, tagPrefix: String) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).selectableGroup()) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) colors.primaryContainer else colors.surface,
                    contentColor = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
                    border = BorderStroke(1.dp, if (isSelected) colors.primaryContainer else colors.outlineVariant),
                    modifier = Modifier
                        .clip(CircleShape)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .testTag("${tagPrefix}_${option.toString().lowercase()}")
                ) {
                    Text(label(option), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, testTag: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(testTag)
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimaryContainer,
                checkedBorderColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

private val TerminalFont_JetBrains = FontFamily(androidx.compose.ui.text.font.Font(com.rembrowser.rembrowse.R.font.jetbrains_mono))
private val TerminalFont_Fira = FontFamily(androidx.compose.ui.text.font.Font(com.rembrowser.rembrowse.R.font.fira_mono))
