package com.rembrowser.rembrowse.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind.CAMERA
import com.rembrowser.rembrowse.model.SitePermissionKind.LOCATION
import com.rembrowser.rembrowse.model.SitePermissionKind.MICROPHONE
import com.rembrowser.rembrowse.model.SitePermissionState.ALLOW
import com.rembrowser.rembrowse.model.SitePermissionState.BLOCK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Camera and microphone decisions, which Gecko does not keep, survive a restart in SharedPreferences. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SitePermissionStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefsName = "rembrowse_site_permissions"
    private val site = "https://meet.example.org"

    @Before
    fun clear() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = SharedPrefsSitePermissionStore(context)

    @Test
    fun aDecisionIsThereAfterARestart() {
        store().set(site, CAMERA, ALLOW)
        store().set(site, MICROPHONE, BLOCK)

        val reopened = store()
        assertEquals(mapOf(CAMERA to ALLOW, MICROPHONE to BLOCK), reopened.decisions(site))
    }

    @Test
    fun decisionsAreKeptPerOrigin() {
        val s = store()
        s.set(site, CAMERA, ALLOW)
        s.set("http://meet.example.org", CAMERA, BLOCK)

        val reopened = store()
        assertEquals(mapOf(CAMERA to ALLOW), reopened.decisions(site))
        assertEquals(mapOf(CAMERA to BLOCK), reopened.decisions("http://meet.example.org"))
        assertTrue(reopened.decisions("https://other.example.org").isEmpty())
    }

    @Test
    fun changingADecisionOverwritesTheOldOne() {
        val s = store()
        s.set(site, CAMERA, ALLOW)
        s.set(site, CAMERA, BLOCK)
        assertEquals(mapOf(CAMERA to BLOCK), store().decisions(site))
    }

    @Test
    fun settingNullForgetsOneKindAndTheSiteWhenItsLastIsGone() {
        val s = store()
        s.set(site, CAMERA, ALLOW)
        s.set(site, LOCATION, ALLOW)

        s.set(site, CAMERA, null)
        assertEquals(mapOf(LOCATION to ALLOW), store().decisions(site))

        s.set(site, LOCATION, null)
        assertTrue(store().entries().isEmpty())
        assertTrue(store().decisions(site).isEmpty())
    }

    @Test
    fun forgettingWhatWasNeverRememberedIsHarmless() {
        val s = store()
        s.set(site, CAMERA, null)
        s.clear(site)
        assertTrue(s.entries().isEmpty())
    }

    @Test
    fun clearForgetsEverythingOfOneSiteOnly() {
        val s = store()
        s.set(site, CAMERA, ALLOW)
        s.set(site, MICROPHONE, ALLOW)
        s.set("https://other.example.org", CAMERA, BLOCK)

        s.clear(site)

        val reopened = store()
        assertTrue(reopened.decisions(site).isEmpty())
        assertEquals(mapOf(CAMERA to BLOCK), reopened.decisions("https://other.example.org"))
    }

    @Test
    fun entriesListEachDecisionWithTheHostToShow() {
        val s = store()
        s.set("http://192.168.1.20:8080", CAMERA, BLOCK)
        s.set("https://xn--bcher-kva.example", MICROPHONE, ALLOW)

        val entries = store().entries().toSet()
        assertEquals(
            setOf(
                SitePermissionEntry("http://192.168.1.20:8080", "192.168.1.20:8080", CAMERA, BLOCK),
                // Shown as punycode: Gecko has not shown this host here, so nothing vouches for its Unicode form
                SitePermissionEntry("https://xn--bcher-kva.example", "xn--bcher-kva.example", MICROPHONE, ALLOW)
            ),
            entries
        )
    }

    @Test
    fun unreadableStoredDataIsIgnoredAndTheStoreStillWorks() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("decisions", "{not json").commit()
        val s = store()
        assertTrue(s.entries().isEmpty())

        s.set(site, CAMERA, ALLOW)
        assertEquals(mapOf(CAMERA to ALLOW), store().decisions(site))
    }

    @Test
    fun unknownKindsStatesAndShapesAreSkippedWhileValidOnesLoad() {
        val raw = """{"$site":{"CAMERA":"ALLOW","TELEPORT":"ALLOW","MICROPHONE":"MAYBE"},
            "https://junk.example":"not an object","https://empty.example":{}}"""
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("decisions", raw).commit()

        val s = store()
        assertEquals(mapOf(CAMERA to ALLOW), s.decisions(site))
        assertEquals(1, s.entries().size)
    }

    @Test
    fun temporaryGrantsAndTheStaleDenialFlagSurviveARestart() {
        val s = store()
        assertTrue(s.temporaryGrants.isEmpty())
        assertFalse(s.staleDenialsCleared)

        s.temporaryGrants = setOf("https://a.example LOCATION", "https://b.example LOCATION")
        s.staleDenialsCleared = true

        val reopened = store()
        assertEquals(setOf("https://a.example LOCATION", "https://b.example LOCATION"), reopened.temporaryGrants)
        assertTrue(reopened.staleDenialsCleared)

        reopened.temporaryGrants = emptySet()
        assertTrue(store().temporaryGrants.isEmpty())
    }

    @Test
    fun theInMemoryStoreBehavesTheSameForTheBasics() {
        val s = InMemorySitePermissionStore()
        s.set(site, CAMERA, ALLOW)
        s.set(site, MICROPHONE, BLOCK)
        assertEquals(mapOf(CAMERA to ALLOW, MICROPHONE to BLOCK), s.decisions(site))
        s.set(site, CAMERA, null)
        s.set(site, MICROPHONE, null)
        assertTrue(s.entries().isEmpty())
        s.set(site, CAMERA, ALLOW)
        s.clear(site)
        assertTrue(s.decisions(site).isEmpty())
    }
}
