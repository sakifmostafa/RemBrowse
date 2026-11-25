package com.rembrowser.rembrowse.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.rembrowser.rembrowse.data.SitePermissionStore
import com.rembrowser.rembrowse.model.PermissionAnswer
import com.rembrowser.rembrowse.model.SiteOrigins
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionState
import org.json.JSONObject
import org.mozilla.gecko.EventDispatcher
import org.mozilla.gecko.util.GeckoBundle
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource
import java.util.UUID

private const val TAG = "GeckoSitePermissions"

private val LOCATION_PERMISSIONS = listOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

/** The value that removes an entry from Gecko's store (nsIPermissionManager.UNKNOWN_ACTION). */
private const val REMOVE = 0

/**
 * Refusals Gecko stored while RemBrowse had no permission delegate: every request then failed
 * ("No listener for GeckoView:ContentPermission") and Gecko recorded a lasting DENY for the site.
 * Only types RemBrowse now asks about or allows are cleared; the rest it refuses anyway.
 */
private val STALE_DENIAL_TYPES = setOf(
    "geolocation",
    "desktop-notification",
    "media-key-system-access",
    "persistent-storage"
)

/** How long to wait before reading Gecko's store again after the start-up read failed. */
private val CLEANUP_RETRY_DELAYS_MS = listOf(5_000L, 30_000L, 120_000L, 300_000L)

/** How often, and how many times, to look for Gecko's overwrite of a site's Allow (see [GeckoSitePermissions]). */
private const val KEEP_ALLOW_POLL_MS = 150L
private const val KEEP_ALLOW_ATTEMPTS = 20

/**
 * One entry of Gecko's permission store as GeckoView's storage events report it, with the type
 * exactly as Gecko keys it. GeckoView's public ContentPermission turns both kinds of storage
 * access key ("3rdPartyStorage^…" and "3rdPartyFrameStorage^…") into one type, and writing it back
 * always writes a "3rdPartyFrameStorage^" key, so a grant Gecko made under the other key could
 * never be changed through it. Rows keep the key, and are written back under it.
 */
internal data class GeckoPermissionRow(
    val uri: String,
    val principal: String,
    val type: String,
    val value: Int,
    val privateMode: Boolean,
    val contextId: String?
) {
    /** A row of an ordinary tab: not private, not in a separate session context. */
    val isPlain: Boolean
        get() = !privateMode && contextId.isNullOrEmpty()
}

/**
 * Site permissions for GeckoView sessions: turns Gecko's permission requests into
 * [SitePermissionAsk]s for the browser, carries out its answers, and keeps the decisions.
 *
 * GeckoView's own scripts (GeckoViewPermissionChild.sys.mjs, GeckoViewPermissionProcessChild.sys.mjs
 * and GeckoViewStorageController.sys.mjs, in the AAR's omni.ja) decide much of the shape:
 * - Gecko stores every answer to a content permission request itself, as a lasting one, whatever
 *   it is (ALLOW, DENY, or PROMPT, which reads as "ask again"). Storage access is the exception:
 *   Gecko's anti-tracking code stores it only when allowed, for the page's origin, under
 *   "3rdPartyFrameStorage^" and the embedded page's site.
 * - GeckoView passes every request on whatever is stored, so the stored value is looked up here.
 * - For location, Gecko asks for the Android permission only after the site was allowed, and
 *   stores a DENY for the site when the app lacks it. For camera and microphone it asks for the
 *   Android permission first, through whichever window was used last, and only then for the site's.
 * - Gecko keeps no camera or microphone decisions, so those live in [store].
 *
 * Every decision belongs to the page in the tab's address bar, the committed one Gecko reported
 * with its location, also when a frame inside it asked.
 *
 * Main thread only.
 */
internal class GeckoSitePermissions(
    private val appContext: Context,
    private val store: SitePermissionStore,
    private val androidPermissions: AndroidPermissionRequester?,
    private val postDelayed: (Long, () -> Unit) -> Unit = { delay, block ->
        Handler(Looper.getMainLooper()).postDelayed(block, delay)
    }
) {
    /** Requests waiting for an answer, per tab, so they can be withdrawn when the page goes. */
    private val pending = mutableMapOf<String, MutableList<SitePermissionAsk>>()

    /** The origin of the page each tab has committed to (its address bar), per tab. */
    private val pageOrigins = mutableMapOf<String, String?>()

    /**
     * Android permission requests made for each tab's page, as the way to withdraw each one:
     * a request outliving its page must not bring up a dialog when the app comes back.
     */
    private val androidRequests = mutableMapOf<String, MutableList<() -> Unit>>()

    /** "Allow this time" grants that Gecko stored as lasting ones, to take back later. */
    private val temporaryGrants = mutableListOf<TemporaryGrant>()

    /** A serialized principal per origin, from permissions Gecko reported, for writing new ones. */
    private val principals = mutableMapOf<String, String>()

    /** False until the first attempt to clean up after an earlier run; requests wait for it. */
    private var ready = false
    private val waitingForReady = mutableListOf<() -> Unit>()

    /**
     * True once that clean-up succeeded. Until then a stored ALLOW for location or the local
     * network may be a one-time grant an earlier run left behind, so none is trusted.
     */
    private var cleanedUp = false

    /** One-time grants an earlier run left in Gecko's store, until the clean-up removes them. */
    private val leftoverGrants = mutableSetOf<GrantKey>()

    /** Lasting Allows the user gave in this run: trusted even before the clean-up succeeded. */
    private val confirmedAllows = mutableSetOf<GrantKey>()
    private var cleanupAttempts = 0

    private data class GrantKey(val origin: String, val kind: SitePermissionKind, val embedded: String?) {
        fun encode(): String = listOfNotNull(origin, kind.name, embedded).joinToString(" ")

        companion object {
            fun decode(encoded: String): GrantKey? {
                val parts = encoded.split(" ")
                if (parts.size !in 2..3) return null
                val kind = SitePermissionKind.entries.firstOrNull { it.name == parts[1] } ?: return null
                return GrantKey(parts[0], kind, parts.getOrNull(2))
            }
        }
    }

    /** [key] granted once in tab [tabId] while it showed [tabOrigin]. */
    private data class TemporaryGrant(val tabId: String, val tabOrigin: String?, val key: GrantKey)

    /**
     * Takes back "Allow this time" grants an earlier run left in Gecko's store, and forgets the
     * refusals Gecko stored before RemBrowse answered requests (once). Requests wait for the first
     * attempt; if it fails, they go ahead without trusting what it would have cleaned, and the
     * clean-up is tried again later.
     */
    fun start() {
        leftoverGrants += store.temporaryGrants.mapNotNull { GrantKey.decode(it) }
        cleanUp()
    }

    private fun cleanUp() {
        val clearStaleDenials = !store.staleDenialsCleared
        if (leftoverGrants.isEmpty() && !clearStaleDenials) {
            finishCleanup()
            return
        }
        allRows { rows ->
            if (rows == null) {
                markReady()
                val delay = CLEANUP_RETRY_DELAYS_MS[cleanupAttempts.coerceAtMost(CLEANUP_RETRY_DELAYS_MS.lastIndex)]
                cleanupAttempts++
                postDelayed(delay) { if (!cleanedUp) cleanUp() }
                return@allRows
            }
            // One-time grants of this run are taken back when their tab leaves; not here
            val heldNow = temporaryGrants.map { it.key }.toSet()
            rowsToClean(
                rows = rows,
                leftoverGrants = (leftoverGrants - heldNow).map { it.encode() }.toSet(),
                userDenials = store.userDenials,
                clearStaleDenials = clearStaleDenials
            ).forEach { writeRow(it.principal, it.type, REMOVE) }
            val cleaned = leftoverGrants - heldNow
            store.temporaryGrants = store.temporaryGrants.filterNot { GrantKey.decode(it) in cleaned }.toSet()
            leftoverGrants.clear()
            if (clearStaleDenials) {
                store.staleDenialsCleared = true
                store.userDenials = emptySet()
            }
            finishCleanup()
        }
    }

    private fun finishCleanup() {
        cleanedUp = true
        markReady()
    }

    fun delegateFor(handle: GeckoSessionHandle): PermissionDelegate = object : PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: ContentPermission
        ): GeckoResult<Int> = onContentRequest(handle, perm)

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out MediaSource>?,
            audio: Array<out MediaSource>?,
            callback: PermissionDelegate.MediaCallback
        ) = onMediaRequest(handle, video, audio, callback)

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: PermissionDelegate.Callback
        ) = onAndroidRequest(handle, permissions, callback)
    }

    /** A new document started loading in [handle]'s tab: the one that asked for anything is going. */
    fun onPageStart(handle: GeckoSessionHandle, url: String) {
        withdrawAndroidRequests(handle.tabId)
        releaseTemporaryGrants(handle.tabId, stillOn = SiteOrigins.originOf(url), everything = false)
    }

    /** The origin of the page [tabId] last committed to, or null. */
    fun pageOriginOf(tabId: String): String? = pageOrigins[tabId]

    /**
     * The tab committed to [url] (a new page, or a change within one), and Gecko reported the
     * page's stored [permissions] with it. From now on the tab's requests are this page's.
     */
    fun onLocationChange(handle: GeckoSessionHandle, url: String?, permissions: List<ContentPermission>) {
        if (url != null) {
            pageOrigins[handle.tabId] = SiteOrigins.originOf(url)
            // GeckoView reports the location as its displaySpec: the address bar's form of the host
            SiteOrigins.rememberDisplayForm(url)
        }
        permissions.forEach(::rememberPrincipal)
    }

    /** The page in [handle]'s tab is gone (its content process crashed or was killed). */
    fun onPageGone(handle: GeckoSessionHandle) {
        withdrawPending(handle)
        withdrawAndroidRequests(handle.tabId)
    }

    /** [handle]'s session closed: its requests are withdrawn and its one-time grants end. */
    fun onSessionClosed(handle: GeckoSessionHandle) {
        withdrawPending(handle)
        withdrawAndroidRequests(handle.tabId)
        releaseTemporaryGrants(handle.tabId, stillOn = null, everything = true)
        pageOrigins.remove(handle.tabId)
    }

    fun load(onResult: (List<SitePermissionEntry>) -> Unit) = whenReady {
        allRows { rows ->
            val fromGecko = rows.orEmpty()
                .mapNotNull(::entryOf)
                // Two rows can name one decision (a storage grant under both keys): a Block wins
                .groupBy { Triple(it.origin, it.kind, it.embeddedOrigin) }
                .map { (_, same) -> same.firstOrNull { it.state == SitePermissionState.BLOCK } ?: same.first() }
            onResult(fromGecko + store.entries())
        }
    }

    fun set(origin: String, kind: SitePermissionKind, state: SitePermissionState?, embeddedOrigin: String?) {
        when (kind) {
            SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE -> store.set(origin, kind, state)
            SitePermissionKind.STORAGE_ACCESS -> {
                val embedded = SiteOrigins.originOf(embeddedOrigin)
                // The entries Settings listed, each under the key Gecko stored it with
                writeDecision(
                    origin,
                    matching = { row -> storageSuffixOf(row.type)?.let { embedded == null || it == embedded } == true },
                    newType = embedded?.let(::frameStorageType),
                    value = valueOf(state)
                )
            }
            else -> {
                val key = GrantKey(origin, kind, null)
                forgetTemporaryGrants { it.origin == origin && it.kind == kind }
                confirmedAllows -= key
                if (state == SitePermissionState.ALLOW) confirmedAllows += key
                if (state == SitePermissionState.BLOCK) noteUserDenial(key)
                val type = geckoTypeName(kind) ?: return
                writeDecision(origin, matching = { it.type == type }, newType = type, value = valueOf(state))
                if (kind == SitePermissionKind.NOTIFICATIONS && state == SitePermissionState.ALLOW) askToPostNotifications()
            }
        }
    }

    fun clear(origin: String) {
        store.clear(origin)
        forgetTemporaryGrants { it.origin == origin }
        confirmedAllows.removeAll { it.origin == origin }
        writeDecision(origin, matching = { kindOfType(it.type) != null }, newType = null, value = REMOVE)
    }

    private fun onContentRequest(handle: GeckoSessionHandle, perm: ContentPermission): GeckoResult<Int> {
        val kind = kindFor(perm.permission) ?: return GeckoResult.fromValue(silentAnswer(perm.permission))
        // The request's address is a displaySpec, the address bar's form of the host
        SiteOrigins.rememberDisplayForm(perm.uri)
        // A storage access request's address is the embedded page's: its principal is not the site's
        if (kind != SitePermissionKind.STORAGE_ACCESS) rememberPrincipal(perm)
        // Storage access is asked by an embedded page; the decision belongs to the page around it.
        // Other requests come with the page's own address (Gecko sends the top-level principal).
        val origin = if (kind == SitePermissionKind.STORAGE_ACCESS) {
            pageOrigins[handle.tabId]
        } else {
            SiteOrigins.originOf(perm.uri)
        }
        // Gecko grants storage access to the embedded page's whole site
        val embedded = if (kind == SitePermissionKind.STORAGE_ACCESS) SiteOrigins.siteOf(perm.thirdPartyOrigin) else null
        val noEmbeddedSite = kind == SitePermissionKind.STORAGE_ACCESS &&
            (embedded == null || embedded == SiteOrigins.siteOf(origin))
        // Nothing is asked for in a private session: what it decided could outlive it here
        if (origin == null || noEmbeddedSite || perm.privateMode || isPrivate(handle)) {
            return GeckoResult.fromValue(ContentPermission.VALUE_PROMPT)
        }
        val result = GeckoResult<Int>()
        var completed = false
        val complete = { value: Int ->
            if (!completed) {
                completed = true
                result.complete(value)
            }
        }
        whenReady {
            storedContentState(origin, kind, embedded) { stored ->
                post(handle, origin, setOf(kind), embedded, stored?.let { mapOf(kind to it) }.orEmpty()) { answer ->
                    answerContentRequest(handle, kind, origin, embedded, stored, answer, complete)
                }
            }
        }
        return result
    }

    private fun answerContentRequest(
        handle: GeckoSessionHandle,
        kind: SitePermissionKind,
        origin: String,
        embedded: String?,
        stored: SitePermissionState?,
        answer: PermissionAnswer,
        complete: (Int) -> Unit
    ) {
        val key = GrantKey(origin, kind, embedded)
        when (answer) {
            PermissionAnswer.ALLOW, PermissionAnswer.ALLOW_ONCE -> {
                val once = answer == PermissionAnswer.ALLOW_ONCE
                val grant = {
                    if (once) {
                        markTemporaryGrant(handle, key)
                    } else {
                        forgetTemporaryGrants { it == key }
                        confirmedAllows += key
                    }
                    // Gecko stores the ALLOW itself, as a lasting one
                    complete(ContentPermission.VALUE_ALLOW)
                    if (!once && stored == null && kind == SitePermissionKind.NOTIFICATIONS) askToPostNotifications()
                }
                if (kind == SitePermissionKind.LOCATION) {
                    // Gecko asks for the Android permission only after this answer and stores a
                    // lasting DENY for the site when it is refused. Asked first, a refusal costs
                    // the site this one request.
                    ensureAndroidPermissions(handle.tabId, LOCATION_PERMISSIONS) { granted ->
                        if (granted) {
                            grant()
                        } else {
                            complete(ContentPermission.VALUE_PROMPT)
                            if (stored == SitePermissionState.ALLOW) {
                                // Gecko stores that PROMPT over an Allow the user gave: put it back
                                keepAllowAfterRefusal(origin, kind) { handle.listener.onSitePermissionsChanged() }
                            } else {
                                // The Allow just given did not take: Settings must not list it
                                handle.listener.onSitePermissionsChanged()
                            }
                        }
                    }
                } else {
                    grant()
                }
            }
            PermissionAnswer.BLOCK -> {
                forgetTemporaryGrants { it == key }
                confirmedAllows -= key
                noteUserDenial(key)
                complete(ContentPermission.VALUE_DENY)
                // Gecko stores no refusal of storage access itself
                if (kind == SitePermissionKind.STORAGE_ACCESS && embedded != null) {
                    writeDecision(
                        origin,
                        matching = { row -> storageSuffixOf(row.type)?.let { SiteOrigins.siteOf(it) == embedded } == true },
                        newType = frameStorageType(embedded),
                        value = ContentPermission.VALUE_DENY
                    )
                }
            }
            // Gecko stores this answer too: a remembered Block stays a Block, anything else is "ask again"
            PermissionAnswer.DISMISS -> complete(
                if (stored == SitePermissionState.BLOCK) ContentPermission.VALUE_DENY else ContentPermission.VALUE_PROMPT
            )
        }
    }

    /**
     * A page wants the camera, the microphone or both. The decision is the page's, the one in the
     * address bar, also when a frame from another site asks: as in Chrome, such a frame can ask
     * only where the page let it (Gecko enforces the iframe's `allow` attribute), and then asks
     * in the page's name. So the prompt names the page, and a site allowed elsewhere cannot lend
     * its camera to a page that embeds it.
     */
    private fun onMediaRequest(
        handle: GeckoSessionHandle,
        video: Array<out MediaSource>?,
        audio: Array<out MediaSource>?,
        callback: PermissionDelegate.MediaCallback
    ) {
        val camera = video?.let { pickCamera(it) }
        val microphone = audio?.let { pickMicrophone(it) }
        val origin = pageOrigins[handle.tabId]
        // Screen capture and other sources are refused, as is a request with nothing usable in it
        val unusable = (video != null && camera == null) || (audio != null && microphone == null) ||
            (camera == null && microphone == null)
        if (origin == null || unusable || isPrivate(handle)) {
            callback.reject()
            return
        }
        val kinds = buildSet {
            if (camera != null) add(SitePermissionKind.CAMERA)
            if (microphone != null) add(SitePermissionKind.MICROPHONE)
        }
        val stored = store.decisions(origin).filterKeys { it in kinds }
        post(handle, origin, kinds, embedded = null, stored = stored) { answer ->
            val undecided = kinds - stored.keys
            when (answer) {
                PermissionAnswer.ALLOW, PermissionAnswer.ALLOW_ONCE -> {
                    ensureAndroidPermissions(handle.tabId, androidPermissionsFor(kinds)) { granted ->
                        if (granted) {
                            // Remembered only once it can work: an Allow Android refused would read as one in Settings
                            if (answer == PermissionAnswer.ALLOW) undecided.forEach { store.set(origin, it, SitePermissionState.ALLOW) }
                            callback.grant(camera?.id, microphone?.id)
                        } else {
                            callback.reject()
                            if (answer == PermissionAnswer.ALLOW && undecided.isNotEmpty()) handle.listener.onSitePermissionsChanged()
                        }
                    }
                }
                PermissionAnswer.BLOCK -> {
                    undecided.forEach { store.set(origin, it, SitePermissionState.BLOCK) }
                    callback.reject()
                }
                PermissionAnswer.DISMISS -> callback.reject()
            }
        }
    }

    /**
     * Gecko wants Android permissions. For camera and microphone this comes before the site is
     * asked (Gecko cannot list the devices without them) and through whichever window was used
     * last, so nothing here says which site it is for: the site's own decision is applied when
     * its request follows. For location it comes only after the site was allowed.
     *
     * On Android 17 and later Gecko also asks for ACCESS_LOCAL_NETWORK whenever it connects to an
     * address on the local network (not this device), through the window used last, and connects
     * without waiting for the answer (GeckoViewStartup.sys.mjs, AndroidLocalNetworkPermission.cpp).
     * An app targeting API 36 or lower holds it through INTERNET (Android's split permission), so
     * it is granted here without Android being asked ([androidPermissionsToAsk]), in a private
     * tab too. Android asks apps not to declare or request it before they target 37, and a request
     * for a permission the manifest doesn't declare is refused; the manifest needs it from then on.
     */
    private fun onAndroidRequest(
        handle: GeckoSessionHandle,
        permissions: Array<out String>?,
        callback: PermissionDelegate.Callback
    ) {
        val requested = androidPermissionsToAsk(
            permissions.orEmpty().toList(),
            targetSdk = appContext.applicationInfo.targetSdkVersion
        )
        if (requested.isEmpty()) {
            callback.grant()
            return
        }
        if (isPrivate(handle)) {
            callback.reject()
            return
        }
        ensureAndroidPermissions(handle.tabId, requested) { granted -> if (granted) callback.grant() else callback.reject() }
    }

    /** Hands a request to the browser, and keeps it until answered so it can be withdrawn. */
    private fun post(
        handle: GeckoSessionHandle,
        origin: String,
        kinds: Set<SitePermissionKind>,
        embedded: String?,
        stored: Map<SitePermissionKind, SitePermissionState>,
        onAnswer: (PermissionAnswer) -> Unit
    ) {
        lateinit var ask: SitePermissionAsk
        ask = SitePermissionAsk(
            id = UUID.randomUUID().toString(),
            tabId = handle.tabId,
            origin = origin,
            kinds = kinds,
            embeddedOrigin = embedded,
            stored = stored
        ) { answer ->
            pending[handle.tabId]?.remove(ask)
            onAnswer(answer)
        }
        pending.getOrPut(handle.tabId) { mutableListOf() } += ask
        handle.listener.onPermissionRequested(ask)
    }

    private fun withdrawPending(handle: GeckoSessionHandle) {
        val asks = pending.remove(handle.tabId) ?: return
        asks.filterNot { it.isAnswered }.forEach { ask ->
            ask.answer(PermissionAnswer.DISMISS)
            handle.listener.onPermissionRequestWithdrawn(ask.id)
        }
    }

    /**
     * What is stored for [kind] on [origin]. A one-time grant is not a decision, and neither is a
     * location ALLOW while an earlier run's one-time grants may still be among them.
     */
    private fun storedContentState(
        origin: String,
        kind: SitePermissionKind,
        embedded: String?,
        onResult: (SitePermissionState?) -> Unit
    ) {
        rowsOf(origin) { rows ->
            val values = rows.orEmpty()
                .filter { it.isPlain && SiteOrigins.originOf(it.uri) == origin && kindMatches(it.type, kind, embedded) }
                .map { it.value }
            onResult(
                when {
                    ContentPermission.VALUE_DENY in values -> SitePermissionState.BLOCK
                    ContentPermission.VALUE_ALLOW in values && !isDistrusted(GrantKey(origin, kind, embedded)) ->
                        SitePermissionState.ALLOW
                    else -> null
                }
            )
        }
    }

    /** Whether row type [type] is a decision about [kind] (for storage access, for [embedded]'s site). */
    private fun kindMatches(type: String, kind: SitePermissionKind, embedded: String?): Boolean {
        if (kind != SitePermissionKind.STORAGE_ACCESS) return type == geckoTypeName(kind)
        val suffix = storageSuffixOf(type) ?: return false
        return embedded == null || SiteOrigins.siteOf(suffix) == embedded
    }

    /**
     * Whether a stored ALLOW for [key] is not to be taken as the user's decision: a one-time grant
     * of this run, or, until the clean-up succeeded, one an earlier run may have left behind (any
     * ALLOW of a kind that offers "Allow this time", as the note of it may not have reached the
     * disk).
     */
    private fun isDistrusted(key: GrantKey): Boolean =
        temporaryGrants.any { it.key == key } ||
            (!cleanedUp && key !in confirmedAllows && (key in leftoverGrants || key.kind.offersAllowOnce))

    /**
     * The user refused [key] while Gecko still holds refusals from before RemBrowse answered
     * requests: the clean-up must keep this one.
     */
    private fun noteUserDenial(key: GrantKey) {
        if (store.staleDenialsCleared) return
        store.userDenials = store.userDenials + key.encode()
    }

    /**
     * Location was refused only because Android's permission was; the PROMPT this answers Gecko
     * with lands in its store over the user's Allow a moment later. Once it has, the Allow goes
     * back. Anything other than that PROMPT is a decision made since, and is left alone.
     */
    private fun keepAllowAfterRefusal(
        origin: String,
        kind: SitePermissionKind,
        attempt: Int = 0,
        onRestored: () -> Unit
    ) {
        val type = geckoTypeName(kind) ?: return
        rowsOf(origin) { rows ->
            val row = rows?.firstOrNull { it.isPlain && SiteOrigins.originOf(it.uri) == origin && it.type == type }
            when (keepAllowStep(readFailed = rows == null, storedValue = row?.value, attempt = attempt)) {
                KeepAllowStep.RESTORE -> if (row != null) {
                    writeRow(row.principal, row.type, ContentPermission.VALUE_ALLOW)
                    onRestored()
                }
                KeepAllowStep.LOOK_AGAIN ->
                    postDelayed(KEEP_ALLOW_POLL_MS) { keepAllowAfterRefusal(origin, kind, attempt + 1, onRestored) }
                KeepAllowStep.STOP -> Unit
            }
        }
    }

    /**
     * Stores [value] in Gecko's store over [origin]'s rows that [matching] accepts and for which
     * [onlyOver] holds, each under its own key. When there are none, a real decision is stored as
     * a new row of type [newType] (if given); a removal has nothing to do.
     */
    private fun writeDecision(
        origin: String,
        matching: (GeckoPermissionRow) -> Boolean,
        newType: String?,
        value: Int,
        onlyOver: (Int) -> Boolean = { true }
    ) = whenReady {
        rowsOf(origin) { rows ->
            val found = rows.orEmpty().filter { it.isPlain && SiteOrigins.originOf(it.uri) == origin && matching(it) }
            found.filter { onlyOver(it.value) }.forEach { writeRow(it.principal, it.type, value) }
            if (found.isEmpty() && newType != null && value != REMOVE) writeRow(principalFor(origin), newType, value)
        }
    }

    private fun allRows(onResult: (List<GeckoPermissionRow>?) -> Unit) =
        queryRows(onResult) { EventDispatcher.getInstance().queryBundle("GeckoView:GetAllPermissions") }

    private fun rowsOf(origin: String, onResult: (List<GeckoPermissionRow>?) -> Unit) = queryRows(onResult) {
        val query = GeckoBundle(2)
        query.putString("uri", origin)
        query.putInt("privateBrowsingId", 0)
        EventDispatcher.getInstance().queryBundle("GeckoView:GetPermissionsByURI", query)
    }

    /**
     * Reads Gecko's store through the same events StorageController sends, keeping the raw rows
     * (see [GeckoPermissionRow]). [onResult] gets null when the read failed.
     */
    private fun queryRows(onResult: (List<GeckoPermissionRow>?) -> Unit, query: () -> GeckoResult<GeckoBundle>) {
        runCatching {
            query().accept(
                { bundle ->
                    val rows = bundle?.getBundleArray("permissions").orEmpty().mapNotNull(::rowOf)
                    rows.filter { it.isPlain }.forEach { row ->
                        SiteOrigins.originOf(row.uri)?.let { principals[it] = row.principal }
                        // A row's address is a displaySpec, as Gecko shows the host
                        SiteOrigins.rememberDisplayForm(row.uri)
                    }
                    onResult(rows)
                },
                { error ->
                    Log.w(TAG, "Could not read Gecko's site permissions", error)
                    onResult(null)
                }
            )
        }.onFailure {
            Log.w(TAG, "Could not read Gecko's site permissions", it)
            onResult(null)
        }
    }

    /** Stores [value] under [type] for [principal], as StorageController.setPermission would, with the key as given. */
    private fun writeRow(principal: String, type: String, value: Int) {
        val data = GeckoBundle(5)
        data.putString("principal", principal)
        data.putString("perm", type)
        data.putInt("newValue", value)
        data.putBoolean("privateMode", false)
        data.putBoolean("allowPermanentPrivateBrowsing", false)
        runCatching { EventDispatcher.getInstance().dispatch("GeckoView:SetPermission", data) }
            .onFailure { Log.w(TAG, "Could not store a site permission", it) }
    }

    /**
     * The principal to store a new row for [origin] under: one Gecko reported for it, or else the
     * JSON form Gecko's `principalToJSON` writes for a plain web origin (kind 1 is a content
     * principal, key 0 its address).
     */
    private fun principalFor(origin: String): String =
        principals[origin] ?: JSONObject().put("1", JSONObject().put("0", "$origin/")).toString()

    private fun rememberPrincipal(perm: ContentPermission) {
        if (perm.privateMode || !perm.contextId.isNullOrEmpty()) return
        val origin = SiteOrigins.originOf(perm.uri) ?: return
        val principal = runCatching { perm.toJson().optString("principal") }.getOrNull()
        if (!principal.isNullOrEmpty()) principals[origin] = principal
    }

    private fun entryOf(row: GeckoPermissionRow): SitePermissionEntry? {
        if (!row.isPlain) return null
        val key = grantKeyOf(row) ?: return null
        val state = when (row.value) {
            ContentPermission.VALUE_ALLOW ->
                if (temporaryGrants.any { it.key == key } || key in leftoverGrants) return null else SitePermissionState.ALLOW
            ContentPermission.VALUE_DENY -> SitePermissionState.BLOCK
            else -> return null
        }
        return SitePermissionEntry(key.origin, SiteOrigins.displayHost(key.origin), key.kind, state, key.embedded)
    }

    private fun grantKeyOf(row: GeckoPermissionRow): GrantKey? = decisionKeyOf(row)?.let { GrantKey.decode(it) }

    private fun markTemporaryGrant(handle: GeckoSessionHandle, key: GrantKey) {
        temporaryGrants += TemporaryGrant(handle.tabId, pageOrigins[handle.tabId], key)
        store.temporaryGrants = store.temporaryGrants + key.encode()
    }

    /** A lasting decision replaced these one-time grants: they must not be taken back. */
    private fun forgetTemporaryGrants(matching: (GrantKey) -> Boolean) {
        temporaryGrants.removeAll { matching(it.key) }
        leftoverGrants.removeAll(matching)
        val onDisk = store.temporaryGrants
        val kept = onDisk.filterNot { encoded -> GrantKey.decode(encoded)?.let(matching) == true }.toSet()
        if (kept.size != onDisk.size) store.temporaryGrants = kept
    }

    /**
     * Ends tab [tabId]'s one-time grants: all of them, or those from a site other than [stillOn].
     * Gecko's ALLOW is removed once no tab holds the grant. The note on disk stays until the next
     * start, in case Gecko stores a grant late, after it was taken back here.
     */
    private fun releaseTemporaryGrants(tabId: String, stillOn: String?, everything: Boolean) {
        val released = temporaryGrants.filter { it.tabId == tabId && (everything || it.tabOrigin != stillOn) }
        if (released.isEmpty()) return
        temporaryGrants.removeAll(released)
        released.map { it.key }.distinct()
            .filter { key -> temporaryGrants.none { it.key == key } }
            .forEach { key ->
                // Only an ALLOW is taken back: anything else was decided since
                writeDecision(
                    key.origin,
                    matching = { kindMatches(it.type, key.kind, key.embedded) },
                    newType = null,
                    value = REMOVE,
                    onlyOver = { it == ContentPermission.VALUE_ALLOW }
                )
            }
    }

    private fun askToPostNotifications() {
        if (isGranted(Manifest.permission.POST_NOTIFICATIONS)) return
        // Wanted by the app, not by one page: never withdrawn
        androidPermissions?.request(listOf(Manifest.permission.POST_NOTIFICATIONS)) {}
    }

    /**
     * Makes sure the app holds [permissions], asking Android if needed; [onResult] says whether it
     * does. The request is for tab [tabId]'s page: if that page goes first, it is withdrawn (no
     * dialog for it later) and counts as refused.
     */
    private fun ensureAndroidPermissions(tabId: String, permissions: List<String>, onResult: (Boolean) -> Unit) {
        // Android 12 and later offer approximate location only when both are asked for together
        val asked = if (permissions.any { it in LOCATION_PERMISSIONS }) (permissions + LOCATION_PERMISSIONS).distinct() else permissions
        if (holdsEnough(permissions, asked.filter(::isGranted).toSet())) {
            onResult(true)
            return
        }
        val requester = androidPermissions
        if (requester == null) {
            onResult(false)
            return
        }
        var settled = false
        var withdraw: (() -> Unit)? = null
        val settle = { granted: Boolean ->
            if (!settled) {
                settled = true
                withdraw?.let { androidRequests[tabId]?.remove(it) }
                onResult(granted)
            }
        }
        val pending = requester.request(asked) { granted -> settle(holdsEnough(permissions, granted)) }
        val withdrawThis = {
            pending.cancel()
            settle(false)
        }
        withdraw = withdrawThis
        if (!settled) androidRequests.getOrPut(tabId) { mutableListOf() } += withdrawThis
    }

    private fun withdrawAndroidRequests(tabId: String) {
        androidRequests.remove(tabId)?.toList()?.forEach { it() }
    }

    private fun isGranted(permission: String): Boolean =
        androidPermissions?.isGranted(permission)
            ?: (ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED)

    private fun isPrivate(handle: GeckoSessionHandle): Boolean =
        runCatching { handle.session.settings.usePrivateMode }.getOrDefault(false)

    private fun whenReady(block: () -> Unit) {
        if (ready) block() else waitingForReady += block
    }

    private fun markReady() {
        if (ready) return
        ready = true
        val waiting = waitingForReady.toList()
        waitingForReady.clear()
        waiting.forEach { it() }
    }

    /** What [keepAllowAfterRefusal] does next. */
    internal enum class KeepAllowStep { RESTORE, LOOK_AGAIN, STOP }

    companion object {
        private const val FRAME_STORAGE_PREFIX = "3rdPartyFrameStorage^"
        private const val STORAGE_PREFIX = "3rdPartyStorage^"

        /**
         * The types Gecko stores the local network decisions under, for the page's origin: the
         * names GeckoView 161 converts PERMISSION_LOCAL_NETWORK_ACCESS and
         * PERMISSION_LOCAL_DEVICE_ACCESS to and from (`ContentPermission.convertType`), which
         * are also the keys necko's LNAPermissionRequest asks under.
         */
        internal const val LOCAL_NETWORK_TYPE = "local-network"
        internal const val LOCAL_DEVICE_TYPE = "loopback-network"

        /**
         * Which decision [row] is, as "origin KIND [embedded]" (the form one-time grants and the
         * user's Blocks are noted in), or null for a row RemBrowse does not manage.
         */
        internal fun decisionKeyOf(row: GeckoPermissionRow): String? {
            val (kind, embedded) = kindOfType(row.type) ?: return null
            val origin = SiteOrigins.originOf(row.uri) ?: return null
            return listOfNotNull(origin, kind.name, embedded).joinToString(" ")
        }

        /**
         * The rows the start-up clean-up removes: ALLOWs that are [leftoverGrants] (one-time grants
         * an earlier run could not take back), and, when [clearStaleDenials], the refusals Gecko
         * stored before RemBrowse answered requests, except the [userDenials] made since.
         */
        internal fun rowsToClean(
            rows: List<GeckoPermissionRow>,
            leftoverGrants: Set<String>,
            userDenials: Set<String>,
            clearStaleDenials: Boolean
        ): List<GeckoPermissionRow> = rows.filter { row ->
            if (!row.isPlain) return@filter false
            val key = decisionKeyOf(row)
            val leftover = row.value == ContentPermission.VALUE_ALLOW && key != null && key in leftoverGrants
            val stale = clearStaleDenials && row.value == ContentPermission.VALUE_DENY &&
                row.type in STALE_DENIAL_TYPES && (key == null || key !in userDenials)
            leftover || stale
        }

        /**
         * After location was refused only for lack of Android's permission: put the user's Allow
         * back once Gecko's PROMPT has replaced it, keep looking while the Allow is still there
         * (or the store could not be read) for a while, and leave any other value alone: it was
         * decided since.
         */
        internal fun keepAllowStep(readFailed: Boolean, storedValue: Int?, attempt: Int): KeepAllowStep = when {
            !readFailed && storedValue == ContentPermission.VALUE_PROMPT -> KeepAllowStep.RESTORE
            (readFailed || storedValue == ContentPermission.VALUE_ALLOW) && attempt < KEEP_ALLOW_ATTEMPTS ->
                KeepAllowStep.LOOK_AGAIN
            else -> KeepAllowStep.STOP
        }

        /**
         * The kind a content permission request is about, or null for one answered without asking.
         * The two local network kinds come from the engine's Local Network Access checks (on by
         * default since GeckoView 143), for a page on the internet reaching the local network or
         * this device.
         */
        internal fun kindFor(type: Int): SitePermissionKind? = when (type) {
            PermissionDelegate.PERMISSION_GEOLOCATION -> SitePermissionKind.LOCATION
            PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> SitePermissionKind.NOTIFICATIONS
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> SitePermissionKind.PROTECTED_CONTENT
            PermissionDelegate.PERMISSION_STORAGE_ACCESS -> SitePermissionKind.STORAGE_ACCESS
            PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> SitePermissionKind.LOCAL_NETWORK
            PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> SitePermissionKind.LOCAL_DEVICE
            else -> null
        }

        /**
         * The kind a row of Gecko's store is a decision about, with the embedded site or origin
         * for storage access (as the key names it), or null for a row RemBrowse does not manage.
         */
        internal fun kindOfType(type: String): Pair<SitePermissionKind, String?>? = when (type) {
            "geolocation" -> SitePermissionKind.LOCATION to null
            "desktop-notification" -> SitePermissionKind.NOTIFICATIONS to null
            "media-key-system-access" -> SitePermissionKind.PROTECTED_CONTENT to null
            LOCAL_NETWORK_TYPE -> SitePermissionKind.LOCAL_NETWORK to null
            LOCAL_DEVICE_TYPE -> SitePermissionKind.LOCAL_DEVICE to null
            else -> storageSuffixOf(type)?.let { SitePermissionKind.STORAGE_ACCESS to it }
        }

        /**
         * The embedded site (or origin) a storage access key names: "3rdPartyFrameStorage^" plus
         * a site for grants through the Storage Access API, "3rdPartyStorage^" plus an origin for
         * those Gecko's heuristics make (after a sign-in popup, say).
         */
        internal fun storageSuffixOf(type: String): String? {
            val suffix = when {
                type.startsWith(FRAME_STORAGE_PREFIX) -> type.removePrefix(FRAME_STORAGE_PREFIX)
                type.startsWith(STORAGE_PREFIX) -> type.removePrefix(STORAGE_PREFIX)
                else -> return null
            }
            return SiteOrigins.originOf(suffix)
        }

        /** The key Gecko stores a storage access decision for embedded site [site] under. */
        internal fun frameStorageType(site: String): String = FRAME_STORAGE_PREFIX + site

        /**
         * The answer to a content permission nobody is asked about. Persistent storage and silent
         * autoplay are harmless; sound starting by itself is refused (a click on play needs no
         * permission), and so are XR, tracking and anything Gecko adds later.
         */
        internal fun silentAnswer(type: Int): Int = when (type) {
            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> ContentPermission.VALUE_ALLOW
            else -> ContentPermission.VALUE_DENY
        }

        internal fun kindForAndroidPermission(permission: String): SitePermissionKind? = when (permission) {
            Manifest.permission.CAMERA -> SitePermissionKind.CAMERA
            Manifest.permission.RECORD_AUDIO -> SitePermissionKind.MICROPHONE
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> SitePermissionKind.LOCATION
            else -> null
        }

        /**
         * Whether [granted] covers [wanted]. Either location permission will do: since Android 12
         * the user can choose approximate location, which grants only the coarse one.
         */
        internal fun holdsEnough(wanted: Collection<String>, granted: Set<String>): Boolean {
            val locationOk = wanted.none { it in LOCATION_PERMISSIONS } || LOCATION_PERMISSIONS.any { it in granted }
            return locationOk && wanted.filterNot { it in LOCATION_PERMISSIONS }.all { it in granted }
        }

        /** The camera to use: a front-facing one where its name tells, else the first. */
        internal fun pickCamera(sources: Array<out MediaSource>): MediaSource? {
            val cameras = sources.filter { it.source == MediaSource.SOURCE_CAMERA }
            return cameras.firstOrNull { it.name?.contains("front", ignoreCase = true) == true } ?: cameras.firstOrNull()
        }

        internal fun pickMicrophone(sources: Array<out MediaSource>): MediaSource? =
            sources.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE }

        /**
         * The Android permissions to ask Android for out of those Gecko [requested]: all of them,
         * once each, but the local network permission while the app targets less than API 37
         * ([targetSdk]), which it then holds without asking (see [onAndroidRequest]).
         */
        internal fun androidPermissionsToAsk(requested: List<String>, targetSdk: Int): List<String> =
            requested.distinct().filterNot { it == ACCESS_LOCAL_NETWORK && targetSdk < LOCAL_NETWORK_PERMISSION_TARGET_SDK }

        /** Android 17's local network permission (Manifest.permission.ACCESS_LOCAL_NETWORK, API 37). */
        internal const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        /** The first targetSdk whose apps have to declare and request [ACCESS_LOCAL_NETWORK]. */
        internal const val LOCAL_NETWORK_PERMISSION_TARGET_SDK = 37

        private fun androidPermissionsFor(kinds: Set<SitePermissionKind>): List<String> = buildList {
            if (SitePermissionKind.CAMERA in kinds) add(Manifest.permission.CAMERA)
            if (SitePermissionKind.MICROPHONE in kinds) add(Manifest.permission.RECORD_AUDIO)
        }

        /** The type Gecko stores [kind] under; storage access has one per embedded site instead. */
        internal fun geckoTypeName(kind: SitePermissionKind): String? = when (kind) {
            SitePermissionKind.LOCATION -> "geolocation"
            SitePermissionKind.NOTIFICATIONS -> "desktop-notification"
            SitePermissionKind.PROTECTED_CONTENT -> "media-key-system-access"
            SitePermissionKind.LOCAL_NETWORK -> LOCAL_NETWORK_TYPE
            SitePermissionKind.LOCAL_DEVICE -> LOCAL_DEVICE_TYPE
            SitePermissionKind.STORAGE_ACCESS, SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE -> null
        }

        private fun valueOf(state: SitePermissionState?): Int = when (state) {
            SitePermissionState.ALLOW -> ContentPermission.VALUE_ALLOW
            SitePermissionState.BLOCK -> ContentPermission.VALUE_DENY
            null -> REMOVE
        }

        private fun rowOf(bundle: GeckoBundle?): GeckoPermissionRow? {
            bundle ?: return null
            return GeckoPermissionRow(
                uri = bundle.getString("uri") ?: return null,
                principal = bundle.getString("principal") ?: return null,
                type = bundle.getString("perm") ?: return null,
                value = bundle.getInt("value"),
                privateMode = bundle.getBoolean("privateMode"),
                contextId = bundle.getString("contextId")
            )
        }
    }
}
