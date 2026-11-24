package com.rembrowser.rembrowse

import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The newer focus handling Compose still has a switch for stays off in the app (see
 * keepComposeInputAsTested): it has not been tried on a touchpad and keyboard device. Compose
 * 1.13 removed the other four switches the app used to turn off. No application is named here:
 * like every test, this one gets RemBrowseApplication from robolectric.properties, so the UI
 * tests run with the handling as shipped.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ComposeInputFlagsTest {

    /** The flags as their names, read now, so a failure says which one */
    private fun flags(): Map<String, Boolean> = mapOf(
        "ComposeUiFlags.isBypassUnfocusableComposeViewEnabled" to ComposeUiFlags.isBypassUnfocusableComposeViewEnabled,
    )

    /** Compose 1.13.0's default */
    private fun setComposeDefaults() {
        ComposeUiFlags.isBypassUnfocusableComposeViewEnabled = true
    }

    // The flags are static and outlive the test; each test's RemBrowseApplication sets them
    // again, and this puts them back as shipped in case a test stopped halfway
    @After
    fun restoreShippedFlags() = keepComposeInputAsTested()

    @Test
    fun everyTestsApplication_hasTheNewHandlingOff() {
        assertTrue(ApplicationProvider.getApplicationContext<android.app.Application>() is RemBrowseApplication)
        assertEquals(flags().mapValues { false }, flags())
    }

    @Test
    fun onCreateTurnsTheNewHandlingOff() {
        setComposeDefaults()
        assertEquals(flags().mapValues { true }, flags())

        ApplicationProvider.getApplicationContext<RemBrowseApplication>().onCreate()

        assertEquals(flags().mapValues { false }, flags())
    }
}
