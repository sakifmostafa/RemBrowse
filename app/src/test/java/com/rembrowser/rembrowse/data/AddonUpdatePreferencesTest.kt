package com.rembrowser.rembrowse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The add-on update switch, the last check and the declined updates, kept across launches. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AddonUpdatePreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SharedPrefsAddonUpdatePreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun aNewInstall_updatesAutomatically_andHasCheckedNothing() {
        val preferences = SharedPrefsAddonUpdatePreferences(context)
        assertTrue(preferences.updateAutomatically)
        assertNull(preferences.lastCheckedMillis)
        assertNull("No version stored: an install updated from 0.16.1 reads as changed", preferences.lastCheckedVersionCode)
        assertEquals(emptyMap<String, String>(), preferences.declinedUpdates)
    }

    @Test
    fun everythingIsReadBackByTheNextLaunch() {
        SharedPrefsAddonUpdatePreferences(context).apply {
            updateAutomatically = false
            lastCheckedMillis = 1_795_000_000_000L
            lastCheckedVersionCode = 38
            declinedUpdates = mapOf("vpn@proton.ch" to "2.0.1", "{b9db16a4-6edc-47ec-a1f4-b86292ed211d}" to "5.0")
        }
        val later = SharedPrefsAddonUpdatePreferences(context)
        assertEquals(false, later.updateAutomatically)
        assertEquals(1_795_000_000_000L, later.lastCheckedMillis)
        assertEquals(38, later.lastCheckedVersionCode)
        assertEquals(
            mapOf("vpn@proton.ch" to "2.0.1", "{b9db16a4-6edc-47ec-a1f4-b86292ed211d}" to "5.0"),
            later.declinedUpdates
        )

        later.lastCheckedMillis = null
        later.lastCheckedVersionCode = null
        later.declinedUpdates = emptyMap()
        assertNull(SharedPrefsAddonUpdatePreferences(context).lastCheckedMillis)
        assertNull(SharedPrefsAddonUpdatePreferences(context).lastCheckedVersionCode)
        assertEquals(emptyMap<String, String>(), SharedPrefsAddonUpdatePreferences(context).declinedUpdates)
    }

    @Test
    fun unreadableDeclinesCountAsNone() {
        assertEquals(emptyMap<String, String>(), SharedPrefsAddonUpdatePreferences.decodeDeclined("not json"))
        assertEquals(emptyMap<String, String>(), SharedPrefsAddonUpdatePreferences.decodeDeclined(null))
        assertEquals(mapOf("a" to "1"), SharedPrefsAddonUpdatePreferences.decodeDeclined("""{"a":"1","b":""}"""))
    }
}
