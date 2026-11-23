package com.rembrowser.rembrowse.engine

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rembrowser.rembrowse.R
import com.rembrowser.rembrowse.model.SiteNotificationContent
import com.rembrowser.rembrowse.model.SiteNotificationRegistry
import com.rembrowser.rembrowse.model.SiteNotifications
import com.rembrowser.rembrowse.model.SiteOrigins
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

private const val TAG = "GeckoSiteNotifications"

private const val CHANNEL_ID = "site_notifications"

/** Every site notification is posted under this id; its tag (the content's key) tells them apart. */
private const val NOTIFICATION_ID = 1

/** How long a large icon may take to download before the notification goes up without it. */
private const val ICON_TIMEOUT_SECONDS = 3L

/** How many redirects an icon download follows; each new address is checked like the first. */
private const val ICON_MAX_REDIRECTS = 3

private const val EXTRA_KEY = "com.rembrowser.rembrowse.extra.SITE_NOTIFICATION_KEY"
private const val EXTRA_TOKEN = "com.rembrowser.rembrowse.extra.SITE_NOTIFICATION_TOKEN"
private const val EXTRA_SOURCE = "com.rembrowser.rembrowse.extra.SITE_NOTIFICATION_SOURCE"

/**
 * Shows sites' web notifications as Android notifications, on the "Site notifications" channel.
 * Without a delegate on the runtime Gecko shows nothing, even for a site allowed to notify.
 *
 * A tap opens [tapActivity], which shows the site's tab and then lets the page handle the click
 * (see [takeNotificationOf]); a swipe tells the page its notification was dismissed (see
 * [SiteNotificationDismissReceiver]). The live notifications are kept in [registry] until then.
 *
 * Gecko calls in on the main thread, which everything here stays on, apart from loading icons.
 */
class GeckoSiteNotifications(
    context: Context,
    private val tapActivity: Class<out Activity>
) : WebNotificationDelegate {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    // Lives as long as the runtime, which is as long as the process
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val iconClient by lazy {
        OkHttpClient.Builder()
            // A name can lead to this device or the local network as surely as an address can
            .dns(PublicAddressesOnly)
            // Followed in download, where each new address is checked first: OkHttp connects to an
            // address written in a URL without asking the Dns above
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private var channelCreated = false

    /** Whether the log already says why nothing is shown, so a busy chat does not repeat it. */
    private var reportedBlocked = false

    override fun onShowNotification(notification: WebNotification) {
        // What a private page shows must not outlive it in the notification shade
        if (notification.privateBrowsing) {
            Log.d(TAG, "Not showing a notification from a private page")
            return
        }
        if (!mayPost()) return
        val content = SiteNotifications.contentOf(
            title = notification.title,
            text = notification.text,
            tag = notification.tag,
            imageUrl = notification.imageUrl,
            source = notification.source,
            silent = notification.silent
        )
        // Only this notification's own tap and swipe carry the token
        val token = UUID.randomUUID().toString()
        registry.put(content.key, notification, token)
        val iconUrl = content.iconUrl
        if (iconUrl == null) {
            post(content, token, icon = null)
            return
        }
        scope.launch {
            val icon = withContext(Dispatchers.IO) {
                runCatching { loadIcon(iconUrl) }
                    .onFailure { Log.d(TAG, "Couldn't load a notification's icon", it) }
                    .getOrNull()
            }
            // The page may have closed or replaced it while the icon loaded
            if (registry.holds(content.key, token)) post(content, token, icon)
        }
    }

    override fun onCloseNotification(notification: WebNotification) {
        val key = SiteNotifications.keyOf(notification.source, notification.tag)
            ?: registry.keyOf(notification)
            ?: return
        registry.remove(key)
        manager.cancel(key, NOTIFICATION_ID)
    }

    private fun post(content: SiteNotificationContent, token: String, icon: Bitmap?) {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rembrowse)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setLargeIcon(icon)
            .setSilent(content.silent)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(content, token))
            .setDeleteIntent(dismissIntent(content.key, token))
        // A long message can be read in full by expanding it
        content.text?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        try {
            manager.notify(content.key, NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // The permission was taken back after mayPost looked
            registry.remove(content.key)
            Log.w(TAG, "Not allowed to show a site notification", e)
        }
    }

    /**
     * Whether Android will show what RemBrowse posts: it needs the notification permission
     * (Android 13 and later), and neither the app's notifications nor the channel may be turned
     * off. When not, the log says why, once until it changes.
     */
    private fun mayPost(): Boolean {
        ensureChannel()
        val reason = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED -> "RemBrowse has no permission to post notifications"
            !manager.areNotificationsEnabled() -> "RemBrowse's notifications are turned off"
            manager.getNotificationChannelCompat(CHANNEL_ID)?.importance == NotificationManagerCompat.IMPORTANCE_NONE ->
                "the Site notifications channel is turned off"
            else -> null
        }
        if (reason == null) {
            reportedBlocked = false
            return true
        }
        if (!reportedBlocked) {
            reportedBlocked = true
            Log.i(TAG, "Not showing site notifications: $reason")
        }
        return false
    }

    private fun ensureChannel() {
        if (channelCreated) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Site notifications")
                .setDescription("Notifications from websites you allowed to show them, such as new messages")
                .build()
        )
        channelCreated = true
    }

    private fun tapIntent(content: SiteNotificationContent, token: String): PendingIntent {
        val intent = Intent(appContext, tapActivity)
            .setAction(ACTION_OPEN)
            .setData(uriOf(content.key))
            .putExtra(EXTRA_KEY, content.key)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_SOURCE, content.sourceUrl)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dismissIntent(key: String, token: String): PendingIntent {
        val intent = Intent(appContext, SiteNotificationDismissReceiver::class.java)
            .setAction(ACTION_DISMISSED)
            .setData(uriOf(key))
            .putExtra(EXTRA_KEY, key)
            .putExtra(EXTRA_TOKEN, token)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** The picture for the large icon, scaled down towards its size; null when there is none. */
    private fun loadIcon(url: String): Bitmap? {
        val bytes = if (url.startsWith("data:", ignoreCase = true)) {
            SiteNotifications.dataUrlBytes(url)
        } else {
            download(url)
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // The decoded size is what costs memory, and a small file can describe a vast picture
        if (!SiteNotifications.isAcceptableIconSize(bounds.outWidth, bounds.outHeight)) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = SiteNotifications.iconSampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * [url]'s body, or null when the request fails, takes longer than [ICON_TIMEOUT_SECONDS] in
     * all, is redirected somewhere an icon may not come from, or is larger than an icon can be.
     * It is sent with the user agent Gecko gives mobile pages, so the site sees a browser.
     */
    private fun download(url: String): ByteArray? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ICON_TIMEOUT_SECONDS)
        var target = url
        repeat(ICON_MAX_REDIRECTS + 1) {
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", GeckoSession.getDefaultUserAgent())
                .build()
            val call = iconClient.newCall(request)
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            call.timeout().timeout(left, TimeUnit.NANOSECONDS)
            call.execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { response.request.url.resolve(it) }
                    target = next?.toString()?.let(SiteNotifications::iconUrlOf) ?: return null
                    return@use
                }
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > SiteNotifications.MAX_ICON_BYTES) return null
                val source = body.source()
                // One byte past the limit tells a body that is too large, without reading the rest
                if (source.request(SiteNotifications.MAX_ICON_BYTES + 1L)) return null
                return source.readByteArray()
            }
        }
        return null
    }

    /** Looks names up as usual, but never hands back an address on this device or the local network. */
    private object PublicAddressesOnly : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname)
                .filterNot { SiteNotifications.isLocalAddress(it.address) }
                .ifEmpty { throw UnknownHostException("$hostname is on this device or the local network") }
    }

    companion object {
        /** The action of a tapped site notification's intent, which the tap activity receives. */
        const val ACTION_OPEN = "com.rembrowser.rembrowse.action.OPEN_SITE_NOTIFICATION"

        internal const val ACTION_DISMISSED = "com.rembrowser.rembrowse.action.SITE_NOTIFICATION_DISMISSED"

        /**
         * The web notifications on show, by their Android notification's tag. Only for this
         * process: a WebNotification is Gecko's handle on a live page, worth nothing once the
         * process that showed it has ended, so after a restart a tap only opens the page.
         */
        internal val registry = SiteNotificationRegistry<WebNotification>()

        /**
         * The web notification that a tap or swipe [intent] is about, taken out of [registry].
         * Null when it is no longer live (its page closed it, or another process showed it), or
         * when the intent does not carry its token: any app can start the activity a tap opens.
         * Main thread only.
         */
        fun takeNotificationOf(intent: Intent): WebNotification? =
            registry.take(intent.getStringExtra(EXTRA_KEY), intent.getStringExtra(EXTRA_TOKEN))

        /** The page a tap [intent] came from, when it is a web address. */
        fun sourceOf(intent: Intent): String? =
            intent.getStringExtra(EXTRA_SOURCE)?.takeIf { SiteOrigins.originOf(it) != null }

        /**
         * Makes each notification's tap and swipe intents distinct: Android tells pending intents
         * apart by their data, not by their extras, so one notification's would otherwise
         * replace another's.
         */
        private fun uriOf(key: String): Uri = Uri.fromParts("rembrowse-notification", key, null)
    }
}

/**
 * A site notification was swiped away (or cleared with the rest): the page hears that it was
 * dismissed. Not exported, so only RemBrowse's own notifications reach it. After a restart the
 * notification is no longer live and nothing happens.
 */
class SiteNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeckoSiteNotifications.ACTION_DISMISSED) return
        val notification = GeckoSiteNotifications.takeNotificationOf(intent) ?: return
        runCatching { notification.dismiss() }
            .onFailure { Log.w(TAG, "Couldn't tell a page its notification was dismissed", it) }
    }
}
