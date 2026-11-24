package com.rembrowser.rembrowse

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Android permission requests wait for a window, and a withdrawn one never shows a dialog (review L8). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActivityPermissionBridgeTest {

    private val bridge = ActivityPermissionBridge(ApplicationProvider.getApplicationContext<Application>())
    private val launched = mutableListOf<List<String>>()
    private val launcher: (Array<String>) -> Unit = { launched += it.toList() }

    @Test
    fun aRequestWithdrawnWhileTheAppWasAwayNeverShowsADialog() {
        var answered = false
        val pending = bridge.request(listOf(Manifest.permission.CAMERA)) { answered = true }

        pending.cancel()
        bridge.attach(launcher)

        assertEquals(emptyList<List<String>>(), launched)
        assertFalse(answered)
    }

    @Test
    fun aRequestStillWantedIsAskedWhenTheWindowComesBack() {
        var granted: Set<String>? = null
        bridge.request(listOf(Manifest.permission.RECORD_AUDIO)) { granted = it }

        bridge.attach(launcher)
        bridge.onResult()

        assertEquals(listOf(listOf(Manifest.permission.RECORD_AUDIO)), launched)
        assertEquals(emptySet<String>(), granted)
    }

    @Test
    fun aRequestWithdrawnWhileItsDialogIsOpenGetsNoAnswerAndTheNextOneIsAsked() {
        bridge.attach(launcher)
        var answered = false
        val first = bridge.request(listOf(Manifest.permission.CAMERA)) { answered = true }
        bridge.request(listOf(Manifest.permission.RECORD_AUDIO)) {}

        first.cancel()
        bridge.onResult()

        assertFalse(answered)
        assertEquals(listOf(listOf(Manifest.permission.CAMERA), listOf(Manifest.permission.RECORD_AUDIO)), launched)
    }
}
