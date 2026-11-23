package com.rembrowser.rembrowse.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rembrowser.rembrowse.model.SiteOrigins
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionState
import org.json.JSONObject

/**
 * RemBrowse's own site permission records. Gecko keeps the decisions for location, notifications
 * and the like itself, but not camera and microphone decisions, so those are kept here, per
 * origin. Also holds the engine's bookkeeping about Gecko's store.
 */
interface SitePermissionStore {
    /** The decisions kept for [origin]. */
    fun decisions(origin: String): Map<SitePermissionKind, SitePermissionState>

    /** Every decision kept here. */
    fun entries(): List<SitePermissionEntry>

    /** Remembers [state] for [kind] on [origin], or forgets it when [state] is null. */
    fun set(origin: String, kind: SitePermissionKind, state: SitePermissionState?)

    /** Forgets every decision kept for [origin]. */
    fun clear(origin: String)

    /**
     * "Allow this time" grants that Gecko wrote to its own store as if they were lasting, which
     * the engine takes back when the tab leaves the site. Kept on disk so that a grant the app
     * had no chance to take back (it was stopped first) is undone at the next start.
     */
    var temporaryGrants: Set<String>

    /**
     * True once the refusals Gecko recorded before RemBrowse answered permission requests have
     * been forgotten. Gecko stored a lasting "deny" for every site that asked while nothing
     * answered, which would otherwise read as the user's own Block.
     */
    var staleDenialsCleared: Boolean

    /**
     * Blocks the user made while [staleDenialsCleared] was still false (the clean-up could not
     * read Gecko's store yet). The clean-up must not take them for stale refusals.
     */
    var userDenials: Set<String>
}

/** Keeps everything in memory only: for tests and previews. */
class InMemorySitePermissionStore : SitePermissionStore {
    private val decisionsByOrigin = linkedMapOf<String, MutableMap<SitePermissionKind, SitePermissionState>>()

    override fun decisions(origin: String): Map<SitePermissionKind, SitePermissionState> =
        decisionsByOrigin[origin].orEmpty().toMap()

    override fun entries(): List<SitePermissionEntry> = entriesOf(decisionsByOrigin)

    override fun set(origin: String, kind: SitePermissionKind, state: SitePermissionState?) {
        applyDecision(decisionsByOrigin, origin, kind, state)
    }

    override fun clear(origin: String) {
        decisionsByOrigin.remove(origin)
    }

    override var temporaryGrants: Set<String> = emptySet()
    override var staleDenialsCleared: Boolean = false
    override var userDenials: Set<String> = emptySet()
}

/**
 * Stores the decisions as JSON in their own preferences file. They are few and change only when
 * the user answers, so writing them whole each time is fine; reads come from memory.
 */
class SharedPrefsSitePermissionStore(
    private val prefs: SharedPreferences
) : SitePermissionStore {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val decisionsByOrigin: MutableMap<String, MutableMap<SitePermissionKind, SitePermissionState>> by lazy {
        load()
    }

    @Synchronized
    override fun decisions(origin: String): Map<SitePermissionKind, SitePermissionState> =
        decisionsByOrigin[origin].orEmpty().toMap()

    @Synchronized
    override fun entries(): List<SitePermissionEntry> = entriesOf(decisionsByOrigin)

    @Synchronized
    override fun set(origin: String, kind: SitePermissionKind, state: SitePermissionState?) {
        if (applyDecision(decisionsByOrigin, origin, kind, state)) save()
    }

    @Synchronized
    override fun clear(origin: String) {
        if (decisionsByOrigin.remove(origin) != null) save()
    }

    override var temporaryGrants: Set<String>
        get() = prefs.getStringSet(KEY_TEMPORARY_GRANTS, null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_TEMPORARY_GRANTS, value.toSet()).apply()

    override var staleDenialsCleared: Boolean
        get() = prefs.getBoolean(KEY_STALE_DENIALS_CLEARED, false)
        set(value) = prefs.edit().putBoolean(KEY_STALE_DENIALS_CLEARED, value).apply()

    override var userDenials: Set<String>
        get() = prefs.getStringSet(KEY_USER_DENIALS, null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_USER_DENIALS, value.toSet()).apply()

    private fun load(): MutableMap<String, MutableMap<SitePermissionKind, SitePermissionState>> {
        val result = linkedMapOf<String, MutableMap<SitePermissionKind, SitePermissionState>>()
        val raw = prefs.getString(KEY_DECISIONS, null) ?: return result
        try {
            val sites = JSONObject(raw)
            sites.keys().forEach { origin ->
                val kinds = sites.optJSONObject(origin) ?: return@forEach
                val decisions = mutableMapOf<SitePermissionKind, SitePermissionState>()
                kinds.keys().forEach { kindName ->
                    val kind = SitePermissionKind.entries.firstOrNull { it.name == kindName }
                    val state = SitePermissionState.entries.firstOrNull { it.name == kinds.optString(kindName) }
                    if (kind != null && state != null) decisions[kind] = state
                }
                if (decisions.isNotEmpty()) result[origin] = decisions
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable site permissions", e)
        }
        return result
    }

    private fun save() {
        val sites = JSONObject()
        decisionsByOrigin.forEach { (origin, decisions) ->
            sites.put(origin, JSONObject().apply { decisions.forEach { (kind, state) -> put(kind.name, state.name) } })
        }
        prefs.edit().putString(KEY_DECISIONS, sites.toString()).apply()
    }

    private companion object {
        const val TAG = "SitePermissionStore"
        const val PREFS_NAME = "rembrowse_site_permissions"
        const val KEY_DECISIONS = "decisions"
        const val KEY_TEMPORARY_GRANTS = "temporary_grants"
        const val KEY_STALE_DENIALS_CLEARED = "stale_denials_cleared"
        const val KEY_USER_DENIALS = "user_denials"
    }
}

/** Changes [decisions] and says whether anything changed. */
private fun applyDecision(
    decisions: MutableMap<String, MutableMap<SitePermissionKind, SitePermissionState>>,
    origin: String,
    kind: SitePermissionKind,
    state: SitePermissionState?
): Boolean {
    val forSite = decisions[origin]
    if (state == null) {
        if (forSite?.remove(kind) == null) return false
        if (forSite.isEmpty()) decisions.remove(origin)
        return true
    }
    if (forSite?.get(kind) == state) return false
    decisions.getOrPut(origin) { mutableMapOf() }[kind] = state
    return true
}

private fun entriesOf(decisions: Map<String, Map<SitePermissionKind, SitePermissionState>>): List<SitePermissionEntry> =
    decisions.flatMap { (origin, kinds) ->
        kinds.map { (kind, state) -> SitePermissionEntry(origin, SiteOrigins.displayHost(origin), kind, state) }
    }
