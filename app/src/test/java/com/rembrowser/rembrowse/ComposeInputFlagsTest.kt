package com.rembrowser.rembrowse

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
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
 * Compose 1.11's newer touchpad and focus handling stays off in the app (see
 * keepComposeInputAsTested): it has not been tried on a touchpad and keyboard device. The other
 * tests run on plain Application, so this one starts RemBrowseApplication itself.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = RemBrowseApplication::class)
class ComposeInputFlagsTest {

    /** The flags as their names, read now, so a failure says which one */
    private fun flags(): Map<String, Boolean> = mapOf(
        "ComposeUiFlags.isTrackpadGestureHandlingEnabled" to ComposeUiFlags.isTrackpadGestureHandlingEnabled,
        "ComposeFoundationFlags.isTrackpadGestureHandlingEnabled" to
            ComposeFoundationFlags.isTrackpadGestureHandlingEnabled,
        "ComposeUiFlags.isIndirectPointerNavigationGestureDetectorEnabled" to
            ComposeUiFlags.isIndirectPointerNavigationGestureDetectorEnabled,
        "ComposeUiFlags.isBypassUnfocusableComposeViewEnabled" to ComposeUiFlags.isBypassUnfocusableComposeViewEnabled,
        "ComposeUiFlags.isOptimizedFocusEventDispatchEnabled" to ComposeUiFlags.isOptimizedFocusEventDispatchEnabled,
    )

    /** Compose 1.11.4's defaults for all of them */
    private fun setComposeDefaults() {
        ComposeUiFlags.isTrackpadGestureHandlingEnabled = true
        ComposeFoundationFlags.isTrackpadGestureHandlingEnabled = true
        ComposeUiFlags.isIndirectPointerNavigationGestureDetectorEnabled = true
        ComposeUiFlags.isBypassUnfocusableComposeViewEnabled = true
        ComposeUiFlags.isOptimizedFocusEventDispatchEnabled = true
    }

    // The flags are static and outlive the test: the other tests keep Compose's defaults
    @After
    fun restoreComposeDefaults() = setComposeDefaults()

    @Test
    fun startedApplicationHasTheNewHandlingOff() {
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
