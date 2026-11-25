package com.rembrowser.rembrowse.engine

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rembrowser.rembrowse.R
import com.rembrowser.rembrowse.model.SiteNotificationAction
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
/** Names the button tapped, on a button's intent. */
internal const val EXTRA_ACTION = "com.rembrowser.rembrowse.extra.SITE_NOTIFICATION_ACTION"

/**
 * Shows sites' web notifications as Android notifications, on the "Site notifications" channel.
 * Without a delegate on the runtime Gecko shows nothing, even for a site allowed to notify.
 *
 * A tap opens [tapActivity], which shows the site's tab and then lets the page handle the click
 * (see [takeTapOf]); a swipe tells the page its notification was dismissed (see
 * [SiteNotificationDismissReceiver]). The live notifications are kept in [registry] until then.
 * The page's action buttons (up to two) open [tapActivity] the same way, each with its own
 * intent that also names the button.
 *
 * The page is told what became of each notification, as GeckoView asks since 145: shown once it
 * is posted, which fires the page's `show` event, or dismissed when nothing is posted (a private
 * page, notifications turned off, posting refused), which fires its `error` event instead (both
 * seen on an emulator with GeckoView 161).
 *
 * Gecko calls in on the main thread, which everything here stays on, apart from loading icons.
 */
class GeckoSiteNotifications internal constructor(
    context: Context,
    private val tapActivity: Class<out Activity>,
    /** Tells the page its notification is on show; [WebNotification.show] outside the tests. */
    private val tellShown: (WebNotification) -> Unit,
    /** Tells the page its notification went nowhere; [WebNotification.dismiss] outside the tests. */
    private val tellNotShown: (WebNotification) -> Unit
) : WebNotificationDelegate {

    constructor(context: Context, tapActivity: Class<out Activity>) :
        this(context, tapActivity, WebNotification::show, WebNotification::dismiss)

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    // Lives as long as the runtime, which is as long as the process
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val iconClient by lazy { notificationIconClient() }

    private var channelCreated = false

    /** Whether the log already says why nothing is shown, so a busy chat does not repeat it. */
    private var reportedBlocked = false

    override fun onShowNotification(notification: WebNotification) {
        // What a private page shows must not outlive it in the notification shade
        if (notification.privateBrowsing) {
            Log.d(TAG, "Not showing a notification from a private page")
            notShown(notification)
            return
        }
        if (!mayPost()) {
            notShown(notification)
            return
        }
        val content = SiteNotifications.contentOf(
            title = notification.title,
            text = notification.text,
            tag = notification.tag,
            imageUrl = notification.imageUrl,
            source = notification.source,
            silent = notification.silent,
            principalOrigin = notification.origin,
            actions = actionsOf(notification)
        )
        // Only this notification's own tap and swipe carry the token
        val token = UUID.randomUUID().toString()
        registry.put(content.key, notification, token)
        val iconUrl = content.iconUrl
        if (iconUrl == null) {
            post(notification, content, token, icon = null)
            return
        }
        scope.launch {
            val icon = withContext(Dispatchers.IO) {
                runCatching { loadIcon(iconUrl) }
                    .onFailure { Log.d(TAG, "Couldn't load a notification's icon", it) }
                    .getOrNull()
            }
            // The page may have closed or replaced it while the icon loaded. Then it is told
            // nothing more about this one: it closed it itself, or a dismiss, which Gecko matches
            // by tag, would close the notification that replaced it.
            if (registry.holds(content.key, token)) post(notification, content, token, icon)
        }
    }

    override fun onCloseNotification(notification: WebNotification) {
        val key = SiteNotifications.keyOf(notification.source, notification.tag)
            ?: registry.keyOf(notification)
            ?: return
        registry.remove(key)
        manager.cancel(key, NOTIFICATION_ID)
    }

    private fun post(notification: WebNotification, content: SiteNotificationContent, token: String, icon: Bitmap?) {
        val failure = try {
            manager.notify(content.key, NOTIFICATION_ID, build(content, token, icon))
            null
        } catch (e: SecurityException) {
            // The permission was taken back after mayPost looked
            e
        } catch (e: RuntimeException) {
            // Android would not take what the page sent: an intent or a notification too large
            // to hand over fails this way, and must not take the app down with it
            e
        }
        if (failure != null) {
            registry.remove(content.key)
            Log.w(TAG, "Couldn't show a site notification", failure)
            notShown(notification)
            return
        }
        shown(notification)
    }

    /** [content]'s notification, with its intents. */
    private fun build(content: SiteNotificationContent, token: String, icon: Bitmap?): Notification {
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
        content.actions.forEachIndexed { index, action ->
            builder.addAction(
                NotificationCompat.Action.Builder(0, action.title, actionIntent(content, token, action, index))
                    // It brings RemBrowse forward, as a tap on the notification does
                    .setShowsUserInterface(true)
                    .build()
            )
        }
        return builder.build()
    }

    /** The page hears that its notification is on show (its `show` event). */
    private fun shown(notification: WebNotification) {
        runCatching { tellShown(notification) }
            .onFailure { Log.w(TAG, "Couldn't tell a page its notification is shown", it) }
    }

    /** The page hears that its notification will not be shown (its `error` event). */
    private fun notShown(notification: WebNotification) {
        runCatching { tellNotShown(notification) }
            .onFailure { Log.w(TAG, "Couldn't tell a page its notification wasn't shown", it) }
    }

    /**
     * Whether Android will show what RemBrowse posts: it needs the notification permission, and
     * neither the app's notifications nor the channel may be turned off. When not, the log says why, once until it changes.
     */
    private fun mayPost(): Boolean {
        ensureChannel()
        val reason = when {
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

    private fun tapIntent(content: SiteNotificationContent, token: String): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            0,
            openIntent(content, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * The intent of the button at [index]: the notification's tap, naming [action]. Each button
     * has its own request code, since its intent differs from the tap's only in its extras.
     */
    private fun actionIntent(
        content: SiteNotificationContent,
        token: String,
        action: SiteNotificationAction,
        index: Int
    ): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            1 + index,
            openIntent(content, token).putExtra(EXTRA_ACTION, action.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openIntent(content: SiteNotificationContent, token: String): Intent =
        Intent(appContext, tapActivity)
            .setAction(ACTION_OPEN)
            .setData(uriOf(content.key))
            .putExtra(EXTRA_KEY, content.key)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_SOURCE, content.sourceUrl)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
            // Sent with the user agent Gecko gives mobile pages, so the site sees a browser
            downloadNotificationIcon(iconClient, url, GeckoSession.getDefaultUserAgent())
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

        /**
         * A tap on a site notification or on one of its buttons, from the tap [intent]: the live
         * notification, taken out of [registry] as [takeNotificationOf] does (null when it is not
         * live or the token is wrong), and the button's action name. The name came from the page
         * and travels in an intent, so it counts only when the live notification shows a button
         * of that name; any other name is a tap on the notification itself. A button, unlike the
         * notification itself, doesn't take the notification off the screen, so that is done
         * here. Main thread only.
         */
        fun takeTapOf(context: Context, intent: Intent): SiteNotificationTap? {
            val notification = takeNotificationOf(intent) ?: return null
            val requested = intent.getStringExtra(EXTRA_ACTION)
            if (requested != null) {
                intent.getStringExtra(EXTRA_KEY)?.let { key ->
                    NotificationManagerCompat.from(context).cancel(key, NOTIFICATION_ID)
                }
            }
            val action = SiteNotifications.clickedAction(requested, SiteNotifications.actionsOf(actionsOf(notification)))
            return SiteNotificationTap(notification, action)
        }

        /** The page's buttons on [notification], as Gecko hands them over. */
        private fun actionsOf(notification: WebNotification): List<SiteNotificationAction> =
            notification.actions.map { SiteNotificationAction(name = it.name, title = it.title) }

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
 * The client a notification's large icon is downloaded with. It never connects to this device or
 * the local network by name ([PublicAddressesOnly] answers every name it is asked about), and it
 * follows no redirect itself: [downloadNotificationIcon] does, checking each new address first,
 * since OkHttp connects to an address written in a URL without asking its [Dns]. The tests pin
 * both of those facts about OkHttp ([dns] is theirs to replace).
 */
internal fun notificationIconClient(dns: Dns = PublicAddressesOnly()): OkHttpClient =
    OkHttpClient.Builder()
        .dns(dns)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/**
 * [url]'s body, or null when the request fails, takes longer than [timeoutNanos] in all, is
 * redirected somewhere an icon may not come from, or is larger than an icon can be. [url] and
 * every address it redirects to must be a web address [SiteNotifications.iconUrlOf] accepts (not
 * on this device or the local network, nor on a port pages may not use); up to
 * [ICON_MAX_REDIRECTS] redirects are followed. Blocks: call it off the main thread.
 */
internal fun downloadNotificationIcon(
    client: OkHttpClient,
    url: String,
    userAgent: String,
    timeoutNanos: Long = TimeUnit.SECONDS.toNanos(ICON_TIMEOUT_SECONDS)
): ByteArray? {
    val deadline = System.nanoTime() + timeoutNanos
    var target = webIconUrlOf(url) ?: return null
    repeat(ICON_MAX_REDIRECTS + 1) {
        val request = Request.Builder()
            .url(target)
            .header("User-Agent", userAgent)
            .build()
        val call = client.newCall(request)
        val left = deadline - System.nanoTime()
        if (left <= 0) return null
        call.timeout().timeout(left, TimeUnit.NANOSECONDS)
        call.execute().use { response ->
            if (response.isRedirect) {
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                target = next?.toString()?.let(::webIconUrlOf) ?: return null
                return@use
            }
            if (!response.isSuccessful) return null
            val body = response.body
            if (body.contentLength() > SiteNotifications.MAX_ICON_BYTES) return null
            val source = body.source()
            // One byte past the limit tells a body that is too large, without reading the rest
            if (source.request(SiteNotifications.MAX_ICON_BYTES + 1L)) return null
            return source.readByteArray()
        }
    }
    return null
}

/** [url] when it is a web address an icon may be fetched from (see [SiteNotifications.iconUrlOf]). */
private fun webIconUrlOf(url: String): String? =
    SiteNotifications.iconUrlOf(url)?.takeUnless { it.startsWith("data:", ignoreCase = true) }

/**
 * Looks names up with [names] (the system's resolver outside the tests), but never hands back an
 * address on this device or the local network: a name that leads only there is not found.
 */
internal class PublicAddressesOnly(private val names: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        names.lookup(hostname)
            .filterNot { SiteNotifications.isLocalAddress(it.address) }
            .ifEmpty { throw UnknownHostException("$hostname is on this device or the local network") }
}

/**
 * A tap on a live site notification: [action] names the button tapped, or is null for the
 * notification itself.
 */
class SiteNotificationTap(val notification: WebNotification, val action: String?)

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
