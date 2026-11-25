package com.rembrowser.rembrowse.engine

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Looper
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.WebNotification
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A page hears what became of its notification, as GeckoView 145 asks: shown once it is posted,
 * dismissed when nothing is posted. Gecko isn't running here, so what the page would be told is
 * recorded instead of reaching [WebNotification.show] and [WebNotification.dismiss].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GeckoSiteNotificationsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)

    /** What each page was told, in order: "shown <title>" or "dismissed <title>". */
    private val told = mutableListOf<String>()

    private val notifications = GeckoSiteNotifications(
        app,
        Activity::class.java,
        tellShown = { told += "shown ${it.title}" },
        tellNotShown = { told += "dismissed ${it.title}" }
    )

    @Before
    fun androidAllowsNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun aPostedNotificationIsReportedShownOnce() {
        notifications.onShowNotification(webNotification("New message"))

        assertEquals(1, posted())
        assertEquals(listOf("shown New message"), told)
    }

    @Test
    fun aPrivatePagesNotificationIsDismissedAndNotPosted() {
        notifications.onShowNotification(webNotification("Secret", privateBrowsing = true))

        assertEquals(0, posted())
        assertEquals(listOf("dismissed Secret"), told)
    }

    @Test
    fun withoutAndroidsPermissionTheNotificationIsDismissed() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.onShowNotification(webNotification("New message"))

        assertEquals(0, posted())
        assertEquals(listOf("dismissed New message"), told)
    }

    @Test
    fun withRemBrowsesNotificationsTurnedOffTheNotificationIsDismissed() {
        shadowOf(manager).setNotificationsEnabled(false)

        notifications.onShowNotification(webNotification("New message"))

        assertEquals(0, posted())
        assertEquals(listOf("dismissed New message"), told)
    }

    @Test
    fun withTheSiteNotificationsChannelTurnedOffTheNotificationIsDismissed() {
        // Turned off by the user before RemBrowse asks for the channel; asking again keeps it off
        manager.createNotificationChannel(
            NotificationChannel("site_notifications", "Site notifications", NotificationManager.IMPORTANCE_NONE)
        )

        notifications.onShowNotification(webNotification("New message"))

        assertEquals(0, posted())
        assertEquals(listOf("dismissed New message"), told)
    }

    @Test
    fun eachNotificationIsToldOnceAndOnlyAboutItself() {
        notifications.onShowNotification(webNotification("First"))
        notifications.onShowNotification(webNotification("Private", privateBrowsing = true))
        notifications.onShowNotification(webNotification("Second"))

        assertEquals(2, posted())
        assertEquals(listOf("shown First", "dismissed Private", "shown Second"), told)
    }

    @Test
    fun withAnIconThePageIsToldOnlyOnceTheNotificationIsPosted() {
        notifications.onShowNotification(webNotification("With icon", imageUrl = PIXEL_PNG))

        // The icon is still loading: nothing is posted yet, so the page has heard nothing
        assertEquals(0, posted())
        assertEquals(emptyList<String>(), told)

        idleMainUntil { told.isNotEmpty() }
        assertEquals(1, posted())
        assertEquals(listOf("shown With icon"), told)
    }

    @Test
    fun aNotificationThePageClosedWhileItsIconLoadedIsNeitherPostedNorReported() {
        val notification = webNotification("Closed early", imageUrl = PIXEL_PNG)
        notifications.onShowNotification(notification)
        notifications.onCloseNotification(notification)

        idleMainUntil(timeoutMs = 1_500) { false }
        assertEquals(0, posted())
        assertEquals(emptyList<String>(), told)
    }

    private fun posted(): Int = shadowOf(manager).allNotifications.size

    /** Runs the main thread's queue until [done], while the icon loads on another thread. */
    private fun idleMainUntil(timeoutMs: Long = 5_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    /**
     * A notification as Gecko would hand it over, from its own parcel form (version 1: no
     * action buttons, no origin). Each has a tag of its own, so none replaces another.
     */
    private fun webNotification(
        title: String,
        privateBrowsing: Boolean = false,
        imageUrl: String? = null
    ): WebNotification {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(1)
            parcel.writeString(title)
            parcel.writeString("https://chat.example#tag:${UUID.randomUUID()}")
            parcel.writeString(null) // cookie
            parcel.writeString("Hello")
            parcel.writeString(imageUrl.orEmpty())
            parcel.writeString(null) // text direction
            parcel.writeString(null) // language
            parcel.writeInt(0) // requireInteraction
            parcel.writeString("https://chat.example/inbox")
            parcel.writeInt(0) // silent
            parcel.writeInt(if (privateBrowsing) 1 else 0)
            parcel.writeIntArray(IntArray(0)) // vibration pattern
            parcel.setDataPosition(0)
            return WebNotification.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        /** A 1×1 PNG. */
        const val PIXEL_PNG =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
