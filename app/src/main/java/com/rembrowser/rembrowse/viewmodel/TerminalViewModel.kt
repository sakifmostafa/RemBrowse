package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.SecretStore
import com.rembrowser.rembrowse.data.TerminalSettings
import com.rembrowser.rembrowse.data.TerminalStore
import com.rembrowser.rembrowse.model.KnownHost
import com.rembrowser.rembrowse.model.SshAuthMethod
import com.rembrowser.rembrowse.model.SshHost
import com.rembrowser.rembrowse.model.SshKeyInfo
import com.rembrowser.rembrowse.model.TerminalCursorStyle
import com.rembrowser.rembrowse.model.TerminalFont
import com.rembrowser.rembrowse.model.TerminalTarget
import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.rembrowser.rembrowse.terminal.CommandSuggestions
import com.rembrowser.rembrowse.terminal.HostKeyCheck
import com.rembrowser.rembrowse.terminal.HostKeyStore
import com.rembrowser.rembrowse.terminal.SshConnector
import com.rembrowser.rembrowse.terminal.SshCredentials
import com.rembrowser.rembrowse.terminal.SshKeyMaterial
import com.rembrowser.rembrowse.terminal.SshKeys
import com.rembrowser.rembrowse.terminal.TerminalConnectSpec
import com.rembrowser.rembrowse.terminal.TerminalConnection
import com.rembrowser.rembrowse.terminal.TerminalStatus
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What Settings → Terminal shows. */
data class TerminalUiState(
    val hosts: List<SshHost> = emptyList(),
    val keys: List<SshKeyInfo> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
    val settings: TerminalSettings = TerminalSettings(),
    /** How many commands are remembered for suggestions. */
    val rememberedCommands: Int = 0
)

/**
 * The terminal: saved hosts and keys (Settings → Terminal), and the connection of each terminal
 * tab. Connections live as long as their tab, whatever happens to the screen.
 *
 * Main thread only.
 *
 * @param onTabTitleChanged tells the browser what a terminal tab is called now.
 */
class TerminalViewModel(
    private val store: TerminalStore,
    private val secrets: SecretStore,
    private val connector: SshConnector,
    private val scope: CoroutineScope,
    private val postToMain: (Runnable) -> Unit,
    private val runInBackground: (Runnable) -> Unit = { Thread(it, "ssh-connect").apply { isDaemon = true }.start() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val onTabTitleChanged: (tabId: String, title: String) -> Unit = { _, _ -> }
) {
    private var commandHistory: List<String> = store.loadCommandHistory()

    private val _uiState = MutableStateFlow(
        TerminalUiState(store.loadHosts(), store.loadKeys(), store.loadKnownHosts(), store.loadSettings(), commandHistory.size)
    )
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private val sessions = LinkedHashMap<String, TerminalConnection>()
    private val titleWatchers = HashMap<String, Job>()

    /** Accepted server keys, as the connections check them (called on the connecting thread). */
    private val hostKeys = object : HostKeyStore {
        @Volatile private var snapshot: List<KnownHost> = _uiState.value.knownHosts

        init {
            scope.launch { _uiState.collect { snapshot = it.knownHosts } }
        }

        override fun knownKey(hostname: String, port: Int): Pair<String, String>? =
            snapshot.firstOrNull { it.hostPort == hostPort(hostname, port) }?.let { it.keyType to it.keyBase64 }

        override fun remember(check: HostKeyCheck) {
            val entry = KnownHost(hostPort(check.hostname, check.port), check.keyType, check.keyBase64, check.fingerprint, clock())
            snapshot = snapshot.filterNot { it.hostPort == entry.hostPort } + entry
            postToMain { updateKnownHosts { list -> list.filterNot { it.hostPort == entry.hostPort } + entry } }
        }
    }

    // --- Terminal tabs ---

    /** The connection of terminal tab [tabId], made the first time it is asked for. */
    fun session(tabId: String): TerminalConnection = sessions.getOrPut(tabId) {
        TerminalConnection(tabId, connector, hostKeys, postToMain, runInBackground).also { watchTitle(it) }
    }

    /**
     * Connects tab [tabId] to [target], reading the host's details and secrets as they are now
     * (so Retry after fixing a host in Settings uses the fix). A new terminal with nothing
     * chosen yet does nothing.
     */
    fun connect(tabId: String, target: TerminalTarget) {
        val connection = session(tabId)
        val spec = specFor(target) ?: return
        spec.fold(
            onSuccess = connection::connect,
            onFailure = { error ->
                val label = (target as? TerminalTarget.SavedHost)?.let { hostById(it.hostId)?.displayName } ?: "Terminal"
                connection.fail(label, error.message ?: "Can't connect.")
            }
        )
    }

    /** How to connect to [target], or why it can't be; null for a new terminal with nothing chosen. */
    private fun specFor(target: TerminalTarget): Result<TerminalConnectSpec>? = when (target) {
        TerminalTarget.Picker -> null
        is TerminalTarget.Address -> Result.success(
            TerminalConnectSpec(
                label = target.display,
                hostname = target.hostname,
                port = target.port,
                username = target.username,
                credentials = SshCredentials.Password(saved = null)
            )
        )
        is TerminalTarget.SavedHost -> {
            val host = hostById(target.hostId)
            when {
                host == null -> Result.failure(IllegalStateException("This saved host was deleted. Open a new terminal, or add the host again in Settings → Terminal."))
                host.auth == SshAuthMethod.KEY -> {
                    val pem = host.keyId?.let { secrets.get(SecretStore.privateKey(it)) }
                    if (pem == null) {
                        Result.failure(IllegalStateException("The key for ${host.displayName} is missing. Choose another key in Settings → Terminal."))
                    } else {
                        Result.success(spec(host, SshCredentials.Key(pem)))
                    }
                }
                else -> Result.success(
                    spec(host, SshCredentials.Password(saved = secrets.get(SecretStore.hostPassword(host.id)))).copy(
                        savePassword = { password -> rememberPassword(host.id, password) }
                    )
                )
            }
        }
    }

    private fun spec(host: SshHost, credentials: SshCredentials) = TerminalConnectSpec(
        label = host.displayName,
        hostname = host.hostname,
        port = host.port,
        username = host.username,
        credentials = credentials
    )

    /** Ends tab [tabId]'s connection and forgets its screen: the tab was closed. */
    fun closeSession(tabId: String) {
        titleWatchers.remove(tabId)?.cancel()
        sessions.remove(tabId)?.disconnect(silently = true)
    }

    /** Closes the connections of tabs that are no longer open. */
    fun retainSessions(openTerminalTabIds: Set<String>) {
        sessions.keys.filterNot { it in openTerminalTabIds }.forEach(::closeSession)
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::closeSession)
    }

    private fun watchTitle(connection: TerminalConnection) {
        titleWatchers[connection.tabId] = scope.launch {
            combine(connection.status, connection.title) { status, title ->
                title ?: TerminalConnection.labelOf(status).takeIf { it.isNotBlank() }
            }.distinctUntilChanged().collect { title ->
                if (title != null) onTabTitleChanged(connection.tabId, title)
            }
        }
    }

    // --- Hosts ---

    fun hostById(id: String): SshHost? = _uiState.value.hosts.firstOrNull { it.id == id }

    /**
     * Adds or updates [host]. With [password], it is saved (encrypted) with the host; with
     * [forgetPassword], any saved one is removed.
     */
    fun saveHost(host: SshHost, password: String? = null, forgetPassword: Boolean = false): SshHost {
        val id = host.id.ifBlank { idGenerator() }
        val passwordKey = SecretStore.hostPassword(id)
        when {
            forgetPassword || host.auth == SshAuthMethod.KEY -> secrets.remove(passwordKey)
            !password.isNullOrEmpty() -> secrets.put(passwordKey, password)
        }
        val saved = host.copy(
            id = id,
            hostname = host.hostname.trim(),
            username = host.username.trim(),
            label = host.label.trim(),
            hasSavedPassword = secrets.get(passwordKey) != null
        )
        updateHosts { hosts ->
            if (hosts.any { it.id == id }) hosts.map { if (it.id == id) saved else it } else hosts + saved
        }
        return saved
    }

    fun deleteHost(id: String) {
        secrets.remove(SecretStore.hostPassword(id))
        updateHosts { hosts -> hosts.filterNot { it.id == id } }
    }

    private fun rememberPassword(hostId: String, password: String) {
        val host = hostById(hostId) ?: return
        secrets.put(SecretStore.hostPassword(hostId), password)
        updateHosts { hosts -> hosts.map { if (it.id == host.id) it.copy(hasSavedPassword = true) else it } }
    }

    // --- Keys ---

    /** Makes a new Ed25519 key named [name]; its private half is kept encrypted on the device. */
    fun generateKey(name: String): SshKeyInfo = keep(name, SshKeys.generateEd25519(keyComment(name)))

    /**
     * Imports a pasted private key. A key with a passphrase needs it once; what is kept is the key
     * without it, encrypted on the device instead.
     */
    fun importKey(name: String, privateKey: String, passphrase: String?): Result<SshKeyInfo> = runCatching {
        keep(name, SshKeys.import(privateKey, passphrase, keyComment(name)))
    }

    private fun keep(name: String, material: SshKeyMaterial): SshKeyInfo {
        val id = idGenerator()
        secrets.put(SecretStore.privateKey(id), material.privateKeyPem)
        val info = SshKeyInfo(id, name.trim().ifBlank { "Key" }, material.publicKey, material.fingerprint, clock())
        updateKeys { it + info }
        return info
    }

    /** Deletes key [id]. Hosts that used it ask for another one when they next connect. */
    fun deleteKey(id: String) {
        secrets.remove(SecretStore.privateKey(id))
        updateKeys { keys -> keys.filterNot { it.id == id } }
    }

    private fun keyComment(name: String): String =
        name.trim().ifBlank { "rembrowse" }.replace(Regex("\\s+"), "-")

    // --- Accepted servers and settings ---

    /** Forgets the key accepted for `host:port`; the next connection asks again. */
    fun forgetKnownHost(hostPort: String) {
        updateKnownHosts { list -> list.filterNot { it.hostPort == hostPort } }
    }

    fun setFontSize(sp: Int) {
        updateSettings { it.copy(fontSizeSp = sp.coerceIn(TerminalSettings.MIN_FONT_SIZE_SP, TerminalSettings.MAX_FONT_SIZE_SP)) }
    }

    fun setTheme(theme: TerminalThemePreset) = updateSettings { it.copy(theme = theme) }

    fun setFont(font: TerminalFont) = updateSettings { it.copy(font = font) }

    fun setCursorStyle(style: TerminalCursorStyle) = updateSettings { it.copy(cursorStyle = style) }

    fun setSuggestions(enabled: Boolean) = updateSettings { it.copy(suggestions = enabled) }

    /** Whether commands are remembered; turning it off also forgets those already remembered. */
    fun setRememberCommands(enabled: Boolean) {
        updateSettings { it.copy(rememberCommands = enabled) }
        if (!enabled) clearCommandHistory()
    }

    private fun updateSettings(change: (TerminalSettings) -> TerminalSettings) {
        val settings = change(_uiState.value.settings)
        if (settings == _uiState.value.settings) return
        _uiState.value = _uiState.value.copy(settings = settings)
        store.saveSettings(settings)
    }

    // --- Command suggestions ---

    /** How [typed] probably ends, from the commands run before and common ones; null if nothing fits. */
    fun suggestCommand(typed: String): String? =
        CommandSuggestions.suggest(typed, if (_uiState.value.settings.rememberCommands) commandHistory else emptyList())

    /** A command was run in a terminal: remembered for suggestions, unless that is turned off. */
    fun recordCommand(command: String) {
        if (!_uiState.value.settings.rememberCommands) return
        val updated = CommandSuggestions.remember(command, commandHistory) ?: return
        commandHistory = updated
        store.saveCommandHistory(updated)
        _uiState.value = _uiState.value.copy(rememberedCommands = updated.size)
    }

    fun clearCommandHistory() {
        commandHistory = emptyList()
        store.saveCommandHistory(emptyList())
        _uiState.value = _uiState.value.copy(rememberedCommands = 0)
    }

    private fun updateHosts(change: (List<SshHost>) -> List<SshHost>) {
        val hosts = change(_uiState.value.hosts)
        _uiState.value = _uiState.value.copy(hosts = hosts)
        store.saveHosts(hosts)
    }

    private fun updateKeys(change: (List<SshKeyInfo>) -> List<SshKeyInfo>) {
        val keys = change(_uiState.value.keys)
        _uiState.value = _uiState.value.copy(keys = keys)
        store.saveKeys(keys)
    }

    private fun updateKnownHosts(change: (List<KnownHost>) -> List<KnownHost>) {
        val list = change(_uiState.value.knownHosts)
        _uiState.value = _uiState.value.copy(knownHosts = list)
        store.saveKnownHosts(list)
    }

    companion object {
        fun hostPort(hostname: String, port: Int): String = "${hostname.lowercase()}:$port"

        /** Whether [status] is one a connection can be started again from. */
        fun canReconnect(status: TerminalStatus): Boolean = status is TerminalStatus.Disconnected
    }
}
