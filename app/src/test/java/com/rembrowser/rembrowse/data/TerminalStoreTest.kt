package com.rembrowser.rembrowse.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.KnownHost
import com.rembrowser.rembrowse.model.SshAuthMethod
import com.rembrowser.rembrowse.model.SshHost
import com.rembrowser.rembrowse.model.SshKeyInfo
import com.rembrowser.rembrowse.model.TerminalCursorStyle
import com.rembrowser.rembrowse.model.TerminalFont
import com.rembrowser.rembrowse.model.TerminalThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Saved hosts, keys and accepted servers survive a restart; nothing secret is written with them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TerminalStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun everythingComesBackAsItWasSaved() {
        val hosts = listOf(
            SshHost("h1", "Home", "home.example", 2222, "sakif", SshAuthMethod.KEY, keyId = "k1"),
            SshHost("h2", "", "10.0.0.2", 22, "root", SshAuthMethod.PASSWORD, hasSavedPassword = true)
        )
        val keys = listOf(SshKeyInfo("k1", "googlebook", "ssh-ed25519 AAAA googlebook", "SHA256:abc", 42))
        val known = listOf(KnownHost("home.example:2222", "ssh-ed25519", "AAAAkey", "SHA256:def", 7))

        SharedPrefsTerminalStore(context).apply {
            saveHosts(hosts)
            saveKeys(keys)
            saveKnownHosts(known)
            saveSettings(TerminalSettings(fontSizeSp = 16))
        }
        val reopened = SharedPrefsTerminalStore(context)
        assertEquals(hosts, reopened.loadHosts())
        assertEquals(keys, reopened.loadKeys())
        assertEquals(known, reopened.loadKnownHosts())
        assertEquals(16, reopened.loadSettings().fontSizeSp)

        val written = context.getSharedPreferences("terminal", 0).all.values.joinToString()
        assertTrue("No private key or password in the plain store", !written.contains("PRIVATE KEY"))
    }

    @Test
    fun aDamagedStore_startsEmpty() {
        context.getSharedPreferences("terminal", 0).edit().putString("hosts", "{not json").commit()
        assertEquals(emptyList<SshHost>(), SharedPrefsTerminalStore(context).loadHosts())
    }

    @Test
    fun theLookAndTheCommandHistory_comeBack() {
        val settings = TerminalSettings(
            fontSizeSp = 12,
            theme = TerminalThemePreset.MIDNIGHT_GREEN,
            font = TerminalFont.FIRA_MONO,
            cursorStyle = TerminalCursorStyle.BAR,
            suggestions = false,
            rememberCommands = false
        )
        SharedPrefsTerminalStore(context).apply {
            saveSettings(settings)
            saveCommandHistory(listOf("git status", "docker compose logs -f \"web\""))
        }
        val reopened = SharedPrefsTerminalStore(context)
        assertEquals(settings, reopened.loadSettings())
        assertEquals(listOf("git status", "docker compose logs -f \"web\""), reopened.loadCommandHistory())
    }

    @Test
    fun aFreshStore_hasTheDefaultLook_andAnUnknownThemeFallsBack() {
        assertEquals(TerminalSettings(), SharedPrefsTerminalStore(context).loadSettings())
        context.getSharedPreferences("terminal", 0).edit()
            .putString("theme", "NO_SUCH_THEME")
            .putString("font", "COMIC_SANS")
            .putInt("fontSizeSp", 99)
            .commit()
        val settings = SharedPrefsTerminalStore(context).loadSettings()
        assertEquals(TerminalThemePreset.DEFAULT, settings.theme)
        assertEquals(TerminalFont.DEFAULT, settings.font)
        assertEquals(TerminalSettings.MAX_FONT_SIZE_SP, settings.fontSizeSp)
    }
}
