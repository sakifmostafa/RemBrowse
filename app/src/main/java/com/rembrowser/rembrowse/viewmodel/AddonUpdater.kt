package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.AddonUpdatePreferences
import com.rembrowser.rembrowse.engine.ExtensionManager
import com.rembrowser.rembrowse.model.AddonUpdateItem
import com.rembrowser.rembrowse.model.AddonUpdateOutcome
import com.rembrowser.rembrowse.model.AddonUpdateResult
import com.rembrowser.rembrowse.model.AddonUpdateTarget
import com.rembrowser.rembrowse.model.AddonUpdatesUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the user's add-ons up to date, as desktop Firefox does.
 *
 * While RemBrowse runs ([start]), add-ons are checked shortly after it starts if the last check
 * was a day ago or more, and every day after that, unless the switch is off. Nothing is scheduled
 * while the app is closed. [checkNow] checks at once, whatever the switch says.
 *
 * Every add-on the user installed is checked, switched on or off, all at once, so one waiting
 * on its permission review holds up none of the others; the browser's built-in ones are left
 * alone. A new version that asks for more permissions goes through the usual review. When the
 * user says no, automatic checks don't ask about that version again; [checkNow] does. A check
 * made offline is not counted, so the next look tries again; an add-on whose download fails
 * waits for the next check.
 */
class AddonUpdater(
    private val extensionManager: ExtensionManager,
    private val preferences: AddonUpdatePreferences,
    private val scope: CoroutineScope,
    /** Whether the device has a network connection. */
    private val isOnline: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long after [start] the first check waits, to stay clear of the browser starting up. */
    private val startDelayMillis: Long = START_DELAY_MILLIS
) {
    private val _uiState = MutableStateFlow(
        AddonUpdatesUiState(
            updateAutomatically = preferences.updateAutomatically,
            lastCheckedMillis = preferences.lastCheckedMillis
        )
    )
    val uiState: StateFlow<AddonUpdatesUiState> = _uiState.asStateFlow()

    private var schedule: Job? = null
    private var requestedCheck: Job? = null

    // One check at a time: a check asked for during an automatic one waits for it to finish
    private val oneCheck = Mutex()

    /** Starts the daily schedule; it ends with [scope]. Calling it again does nothing. */
    fun start() {
        if (schedule?.isActive == true) return
        schedule = scope.launch {
            delay(startDelayMillis)
            while (true) {
                runCheck(automatic = true)
                delay(untilNextLook())
            }
        }
    }

    /** "Check for updates": checks every add-on now and says how it went. */
    fun checkNow() {
        if (requestedCheck?.isActive == true) return
        _uiState.update { it.copy(checking = true) }
        requestedCheck = scope.launch {
            try {
                val outcome = runCheck(automatic = false)
                _uiState.update { it.copy(outcome = outcome) }
            } finally {
                _uiState.update { it.copy(checking = false) }
            }
        }
    }

    fun setUpdateAutomatically(enabled: Boolean) {
        preferences.updateAutomatically = enabled
        _uiState.update { it.copy(updateAutomatically = enabled) }
        // Switched back on after a day or more: the check due runs now, not at the next look
        if (enabled && schedule?.isActive == true && isCheckDue()) {
            scope.launch { runCheck(automatic = true) }
        }
    }

    /** Whether an automatic check should run now. */
    internal fun isCheckDue(): Boolean {
        if (!preferences.updateAutomatically) return false
        val last = preferences.lastCheckedMillis ?: return true
        val now = clock()
        // A clock set back would otherwise hold off checks until it caught up
        return now < last || now - last >= CHECK_INTERVAL_MILLIS
    }

    /**
     * How long the schedule waits before it looks again: until the next check is due, but at
     * least a minute and at most an hour, so a changed clock is noticed. A check still due after
     * its look (it was made offline) is tried again in an hour, as is one the switch holds off.
     */
    internal fun untilNextLook(): Long {
        val last = preferences.lastCheckedMillis
        if (last == null || !preferences.updateAutomatically || isCheckDue()) return MAX_LOOK_INTERVAL_MILLIS
        return (last + CHECK_INTERVAL_MILLIS - clock()).coerceIn(MIN_LOOK_INTERVAL_MILLIS, MAX_LOOK_INTERVAL_MILLIS)
    }

    /**
     * Null for an automatic check that wasn't due (by the time it got its turn). Never throws but
     * for cancellation: a failure here must neither crash the app nor end the daily schedule.
     */
    private suspend fun runCheck(automatic: Boolean): AddonUpdateOutcome? = oneCheck.withLock {
        try {
            checkLocked(automatic)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not counted as a check, so the next look tries again
            AddonUpdateOutcome.Unavailable
        }
    }

    private suspend fun checkLocked(automatic: Boolean): AddonUpdateOutcome? {
        if (automatic && !isCheckDue()) return null
        if (!isOnline()) return AddonUpdateOutcome.Offline
        val installed = extensionManager.addonUpdateTargets().getOrNull() ?: return AddonUpdateOutcome.Unavailable

        val declined = preferences.declinedUpdates
        val items = checkEach(installed.filterNot { it.builtIn }, if (automatic) declined else emptyMap())
        val stillDeclined = declinesAfter(declined, items, installed.map { it.id }.toSet())
        if (stillDeclined != declined) preferences.declinedUpdates = stillDeclined
        val now = clock()
        preferences.lastCheckedMillis = now
        // A later automatic check makes what the last one asked for said out of date
        _uiState.update { it.copy(lastCheckedMillis = now, outcome = if (automatic) null else it.outcome) }
        return AddonUpdateOutcome.Checked(items)
    }

    /**
     * Checks [targets] side by side. An update the user had already declined, [declined]'s
     * version for its add-on, is declined again without asking.
     */
    private suspend fun checkEach(
        targets: List<AddonUpdateTarget>,
        declined: Map<String, String>
    ): List<AddonUpdateItem> = coroutineScope {
        targets.map { target ->
            async {
                val result = try {
                    extensionManager.updateAddon(target.id, declined[target.id])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Promised never to happen; one add-on's failure must not end the others' checks
                    AddonUpdateResult.Failed()
                }
                // The list in Settings and the toolbar show the new version straight away
                if (result is AddonUpdateResult.Updated) extensionManager.refreshInstalled()
                AddonUpdateItem(target.id, target.name, result)
            }
        }.awaitAll()
    }

    companion object {
        const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        const val START_DELAY_MILLIS = 30_000L
        const val MIN_LOOK_INTERVAL_MILLIS = 60_000L
        const val MAX_LOOK_INTERVAL_MILLIS = 60L * 60 * 1000
    }
}

/**
 * The declined updates to remember after a check: those of add-ons still installed
 * ([installedIds]), with the versions just declined added and those of add-ons that have since
 * updated dropped.
 */
internal fun declinesAfter(
    before: Map<String, String>,
    items: List<AddonUpdateItem>,
    installedIds: Set<String>
): Map<String, String> {
    val after = before.filterKeys { it in installedIds }.toMutableMap()
    for (item in items) {
        when (val result = item.result) {
            is AddonUpdateResult.Declined -> result.version?.let { after[item.id] = it }
            is AddonUpdateResult.Updated -> after.remove(item.id)
            else -> Unit
        }
    }
    return after
}
