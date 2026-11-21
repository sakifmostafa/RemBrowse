package com.rembrowser.rembrowse.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.rembrowser.rembrowse.model.KnownHost
import com.rembrowser.rembrowse.model.SshAuthMethod
import com.rembrowser.rembrowse.model.SshHost
import com.rembrowser.rembrowse.model.SshKeyInfo
import com.rembrowser.rembrowse.model.TerminalCursorStyle
import com.rembrowser.rembrowse.model.TerminalFont
import com.rembrowser.rembrowse.model.TerminalThemePreset
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "TerminalStore"

/** The terminal's own settings. */
data class TerminalSettings(
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    val theme: TerminalThemePreset = TerminalThemePreset.DEFAULT,
    val font: TerminalFont = TerminalFont.DEFAULT,
    val cursorStyle: TerminalCursorStyle = TerminalCursorStyle.DEFAULT,
    /** Suggest how a command might end, from the history and common commands; Tab takes it. */
    val suggestions: Boolean = true,
    /** Remember the commands run, for suggestions. Kept on this device only. */
    val rememberCommands: Boolean = true
) {
    companion object {
        const val DEFAULT_FONT_SIZE_SP = 14
        const val MIN_FONT_SIZE_SP = 10
        const val MAX_FONT_SIZE_SP = 24
    }
}

/** Saved hosts, keys (their public halves) and accepted servers. Nothing secret is kept here. */
interface TerminalStore {
    fun loadHosts(): List<SshHost>
    fun saveHosts(hosts: List<SshHost>)
    fun loadKeys(): List<SshKeyInfo>
    fun saveKeys(keys: List<SshKeyInfo>)
    fun loadKnownHosts(): List<KnownHost>
    fun saveKnownHosts(knownHosts: List<KnownHost>)
    fun loadSettings(): TerminalSettings
    fun saveSettings(settings: TerminalSettings)

    /** Commands run in terminals, most recent first. */
    fun loadCommandHistory(): List<String>
    fun saveCommandHistory(commands: List<String>)
}

/**
 * Passwords and private keys, encrypted on the device. The encryption key lives in Android's
 * keystore (in hardware where the device has it) and cannot be read out or backed up, so a copy
 * of the app's data opens nothing elsewhere.
 */
interface SecretStore {
    fun put(name: String, value: String)
    fun get(name: String): String?
    fun remove(name: String)

    companion object {
        fun hostPassword(hostId: String) = "host-password:$hostId"
        fun privateKey(keyId: String) = "private-key:$keyId"
    }
}

class InMemoryTerminalStore(
    private var hosts: List<SshHost> = emptyList(),
    private var keys: List<SshKeyInfo> = emptyList(),
    private var knownHosts: List<KnownHost> = emptyList(),
    private var settings: TerminalSettings = TerminalSettings(),
    private var commands: List<String> = emptyList()
) : TerminalStore {
    override fun loadHosts() = hosts
    override fun saveHosts(hosts: List<SshHost>) { this.hosts = hosts }
    override fun loadKeys() = keys
    override fun saveKeys(keys: List<SshKeyInfo>) { this.keys = keys }
    override fun loadKnownHosts() = knownHosts
    override fun saveKnownHosts(knownHosts: List<KnownHost>) { this.knownHosts = knownHosts }
    override fun loadSettings() = settings
    override fun saveSettings(settings: TerminalSettings) { this.settings = settings }
    override fun loadCommandHistory() = commands
    override fun saveCommandHistory(commands: List<String>) { this.commands = commands }
}

class InMemorySecretStore : SecretStore {
    val values = mutableMapOf<String, String>()
    override fun put(name: String, value: String) { values[name] = value }
    override fun get(name: String): String? = values[name]
    override fun remove(name: String) { values.remove(name) }
}

class SharedPrefsTerminalStore(context: Context) : TerminalStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)

    override fun loadHosts(): List<SshHost> = readArray("hosts") { o ->
        SshHost(
            id = o.getString("id"),
            label = o.optString("label"),
            hostname = o.getString("hostname"),
            port = o.optInt("port", 22),
            username = o.getString("username"),
            auth = SshAuthMethod.fromNameOrDefault(o.optString("auth")),
            keyId = o.optString("keyId").takeIf { it.isNotBlank() },
            hasSavedPassword = o.optBoolean("hasSavedPassword")
        )
    }

    override fun saveHosts(hosts: List<SshHost>) = writeArray("hosts", hosts) { h ->
        JSONObject()
            .put("id", h.id)
            .put("label", h.label)
            .put("hostname", h.hostname)
            .put("port", h.port)
            .put("username", h.username)
            .put("auth", h.auth.name)
            .put("keyId", h.keyId ?: "")
            .put("hasSavedPassword", h.hasSavedPassword)
    }

    override fun loadKeys(): List<SshKeyInfo> = readArray("keys") { o ->
        SshKeyInfo(
            id = o.getString("id"),
            name = o.getString("name"),
            publicKey = o.getString("publicKey"),
            fingerprint = o.optString("fingerprint"),
            createdAtMillis = o.optLong("createdAt")
        )
    }

    override fun saveKeys(keys: List<SshKeyInfo>) = writeArray("keys", keys) { k ->
        JSONObject()
            .put("id", k.id)
            .put("name", k.name)
            .put("publicKey", k.publicKey)
            .put("fingerprint", k.fingerprint)
            .put("createdAt", k.createdAtMillis)
    }

    override fun loadKnownHosts(): List<KnownHost> = readArray("knownHosts") { o ->
        KnownHost(
            hostPort = o.getString("hostPort"),
            keyType = o.getString("keyType"),
            keyBase64 = o.getString("key"),
            fingerprint = o.optString("fingerprint"),
            addedAtMillis = o.optLong("addedAt")
        )
    }

    override fun saveKnownHosts(knownHosts: List<KnownHost>) = writeArray("knownHosts", knownHosts) { k ->
        JSONObject()
            .put("hostPort", k.hostPort)
            .put("keyType", k.keyType)
            .put("key", k.keyBase64)
            .put("fingerprint", k.fingerprint)
            .put("addedAt", k.addedAtMillis)
    }

    override fun loadSettings(): TerminalSettings = TerminalSettings(
        fontSizeSp = prefs.getInt("fontSizeSp", TerminalSettings.DEFAULT_FONT_SIZE_SP)
            .coerceIn(TerminalSettings.MIN_FONT_SIZE_SP, TerminalSettings.MAX_FONT_SIZE_SP),
        theme = TerminalThemePreset.fromNameOrDefault(prefs.getString("theme", null)),
        font = TerminalFont.fromNameOrDefault(prefs.getString("font", null)),
        cursorStyle = TerminalCursorStyle.fromNameOrDefault(prefs.getString("cursorStyle", null)),
        suggestions = prefs.getBoolean("suggestions", true),
        rememberCommands = prefs.getBoolean("rememberCommands", true)
    )

    override fun saveSettings(settings: TerminalSettings) {
        prefs.edit()
            .putInt("fontSizeSp", settings.fontSizeSp)
            .putString("theme", settings.theme.name)
            .putString("font", settings.font.name)
            .putString("cursorStyle", settings.cursorStyle.name)
            .putBoolean("suggestions", settings.suggestions)
            .putBoolean("rememberCommands", settings.rememberCommands)
            .apply()
    }

    override fun loadCommandHistory(): List<String> = readArray("commands") { it.getString("c") }

    override fun saveCommandHistory(commands: List<String>) = writeArray("commands", commands) { JSONObject().put("c", it) }

    private fun <T> readArray(key: String, parse: (JSONObject) -> T): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> runCatching { parse(array.getJSONObject(i)) }.getOrNull() }
        }.getOrElse {
            Log.w(TAG, "Could not read the saved $key", it)
            emptyList()
        }
    }

    private fun <T> writeArray(key: String, items: List<T>, toJson: (T) -> JSONObject) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        prefs.edit().putString(key, array.toString()).apply()
    }
}

/** [SecretStore] encrypted with an AES-GCM key kept in the Android keystore. */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("terminal_secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // The keystore key is gone (a restore onto another device, a reset lock screen):
            // what was sealed with it can never be opened again
            Log.w(TAG, "A saved secret could not be opened and was dropped", e)
            remove(name)
            null
        }
    }

    override fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rembrowse_terminal_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
