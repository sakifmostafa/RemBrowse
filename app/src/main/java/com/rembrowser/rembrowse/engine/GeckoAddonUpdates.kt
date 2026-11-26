package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.AddonUpdateResult
import org.mozilla.geckoview.WebExtension.InstallException.ErrorCodes

/*
 * How GeckoView 161 reports `WebExtensionController.update()` (GeckoViewWebExtension.sys.mjs,
 * `updateWebExtension`, and WebExtension.InstallException.fromQueryException):
 * - null when there is no newer version, when the update check itself fails (offline, the
 *   update server unreachable: Gecko reports that as "no update"), or when updates are off;
 * - the updated extension once the new version is installed;
 * - an InstallException otherwise: ERROR_USER_CANCELED when the review of new permissions
 *   (onUpdatePrompt) is answered with DENY, ERROR_POSTPONED when the extension delays the
 *   update until it restarts, ERROR_NETWORK_FAILURE when the download fails, and the other
 *   install errors (a damaged file, a missing signature, a blocked add-on…) as for an install.
 * The review is offered the *new* version's metadata, so its version is the one declined.
 */

/** An update [GeckoExtensionManager] is waiting on, for the review of new permissions it may bring. */
internal class AddonUpdateUnderway(
    /** The version the user already declined, which is declined again without asking. */
    private val declinedVersion: String?
) {
    /** The new version, once its review was asked for. */
    var offeredVersion: String? = null
        private set

    /** The review was answered without showing it, as [declinedVersion] was offered again. */
    var declinedUnasked: Boolean = false
        private set

    /** Notes the [version] on offer; true when it is to be declined without asking the user. */
    fun declineUnasked(version: String?): Boolean {
        offeredVersion = version
        declinedUnasked = version != null && version == declinedVersion
        return declinedUnasked
    }
}

/** What an update that failed with [installErrorCode] (null: not an install error) amounts to. */
internal fun addonUpdateFailure(installErrorCode: Int?, underway: AddonUpdateUnderway): AddonUpdateResult =
    when (installErrorCode) {
        ErrorCodes.ERROR_USER_CANCELED ->
            AddonUpdateResult.Declined(underway.offeredVersion, asked = !underway.declinedUnasked)
        ErrorCodes.ERROR_POSTPONED -> AddonUpdateResult.Postponed
        ErrorCodes.ERROR_NETWORK_FAILURE -> AddonUpdateResult.Failed(network = true)
        else -> AddonUpdateResult.Failed()
    }
