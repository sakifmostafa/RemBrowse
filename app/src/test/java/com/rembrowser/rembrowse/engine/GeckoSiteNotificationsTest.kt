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
import android.app.Notification
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationAction
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

    @Test
    fun aNotificationWithoutActionsHasNoButtons() {
        notifications.onShowNotification(webNotification("New message"))

        assertEquals(0, shown().actions?.size ?: 0)
    }

    @Test
    fun thePagesActionsAreButtonsWithTheirTitles() {
        notifications.onShowNotification(
            webNotificationWithActions("New message", "reply" to "Reply", "archive" to "Archive")
        )

        val buttons = shown().actions
        assertEquals(listOf("Reply", "Archive"), buttons.map { it.title.toString() })
        assertEquals(listOf("reply", "archive"), buttons.map { intentOf(it.actionIntent).getStringExtra(EXTRA_ACTION) })
        // The notification itself names no button
        assertNull(intentOf(shown().contentIntent).getStringExtra(EXTRA_ACTION))
    }

    @Test
    fun atMostTwoButtonsAndNoneWithoutWords() {
        notifications.onShowNotification(
            webNotificationWithActions("New message", "blank" to " ", "one" to "One", "two" to "Two", "three" to "Three")
        )

        assertEquals(listOf("One", "Two"), shown().actions.map { it.title.toString() })
    }

    @Test
    fun theSiteBesideTheAppsNameIsTheNotificationsOrigin() {
        notifications.onShowNotification(
            webNotificationWithActions("New message", origin = "https://chat.example^userContextId=1")
        )

        assertEquals("chat.example", shown().extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
    }

    @Test
    fun aButtonTellsThePageItsNameAndTakesTheNotificationOffTheScreen() {
        val notification = webNotificationWithActions("New message", "reply" to "Reply", "archive" to "Archive")
        notifications.onShowNotification(notification)
        val archive = intentOf(shown().actions[1].actionIntent)

        val tap = GeckoSiteNotifications.takeTapOf(app, archive)

        assertNotNull(tap)
        assertSame(notification, tap!!.notification)
        assertEquals("archive", tap.action)
        assertEquals(0, posted())
        // Taken: the same intent again does nothing
        assertNull(GeckoSiteNotifications.takeTapOf(app, archive))
    }

    @Test
    fun aTapOnTheNotificationItselfNamesNoButton() {
        notifications.onShowNotification(webNotificationWithActions("New message", "reply" to "Reply"))

        val tap = GeckoSiteNotifications.takeTapOf(app, intentOf(shown().contentIntent))

        assertNotNull(tap)
        assertNull(tap!!.action)
    }

    @Test
    fun aNameTheNotificationDoesNotShowIsAPlainTap() {
        notifications.onShowNotification(webNotificationWithActions("New message", "reply" to "Reply"))
        val forged = Intent(intentOf(shown().actions[0].actionIntent)).putExtra(EXTRA_ACTION, "delete-account")

        val tap = GeckoSiteNotifications.takeTapOf(app, forged)

        assertNotNull(tap)
        assertNull(tap!!.action)
    }

    @Test
    fun aButtonIntentWithoutTheTokenTakesNothing() {
        notifications.onShowNotification(webNotificationWithActions("New message", "reply" to "Reply"))
        val forged = Intent(intentOf(shown().actions[0].actionIntent))
            .putExtra("com.rembrowser.rembrowse.extra.SITE_NOTIFICATION_TOKEN", "guessed")

        assertNull(GeckoSiteNotifications.takeTapOf(app, forged))
        // Left on the screen, still live
        assertEquals(1, posted())
    }

    private fun posted(): Int = shadowOf(manager).allNotifications.size

    private fun shown(): Notification = shadowOf(manager).allNotifications.single()

    private fun intentOf(pending: android.app.PendingIntent): Intent = shadowOf(pending).savedIntent

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

    /**
     * A notification as GeckoView 161 hands it over, from its parcel form (version 3), with the
     * page's [actions] (name to title) and its principal's [origin].
     */
    private fun webNotificationWithActions(
        title: String,
        vararg actions: Pair<String, String>,
        origin: String = "https://chat.example"
    ): WebNotification {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(3)
            parcel.writeString(title)
            parcel.writeString("https://chat.example#tag:${UUID.randomUUID()}")
            parcel.writeString(null) // cookie
            parcel.writeString("Hello")
            parcel.writeString("")
            parcel.writeString(null) // text direction
            parcel.writeString(null) // language
            parcel.writeInt(0) // requireInteraction
            parcel.writeString("https://chat.example/sw.js")
            parcel.writeInt(0) // silent
            parcel.writeInt(0) // private browsing
            parcel.writeIntArray(IntArray(0)) // vibration pattern
            parcel.writeParcelableArray(actions.map { (name, label) -> WebNotificationAction(name, label) }.toTypedArray(), 0)
            parcel.writeString(origin)
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
