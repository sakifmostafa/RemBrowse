package com.rembrowser.rembrowse.model

import android.graphics.Bitmap

data class AmoAddon(
    val id: String,
    val slug: String,
    val name: String,
    val summary: String,
    val iconUrl: String?,
    val author: String,
    val rating: Double,
    val weeklyDownloads: Long,
    val version: String,
    val xpiUrl: String
)

data class InstalledExtension(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val hasBrowserAction: Boolean = false,
    val badgeText: String? = null,
    /** The toolbar button's current icon (extensions such as VPNs change it to show state). */
    val icon: Bitmap? = null,
    /** Badge colours the extension chose, as ARGB; null means the browser's own. */
    val badgeBackgroundColor: Int? = null,
    val badgeTextColor: Int? = null
)

/** Why the engine is asking the user to review an extension's permissions. */
enum class ExtensionPromptKind {
    /** A new extension is about to be installed. */
    INSTALL,

    /** An installed extension updated and the new version needs more permissions. */
    UPDATE,

    /** A running extension is requesting optional permissions. */
    OPTIONAL_PERMISSIONS
}

data class ExtensionInstallPrompt(
    val extensionId: String,
    val extensionName: String,
    val version: String,
    val permissions: List<String>,
    val kind: ExtensionPromptKind = ExtensionPromptKind.INSTALL,
    val onDecision: (Boolean) -> Unit
)

/**
 * The content blocker RemBrowse sets up by default: uBlock Origin, installed from
 * addons.mozilla.org on first launch so it is always the current Mozilla-signed build. Chosen
 * over AdGuard AdBlocker (the default until 0.13.8): a smaller package, search ads blocked by
 * default (AdGuard allows them unless changed), and trackers that hide behind a site's own
 * subdomains unmasked through Firefox's DNS API.
 */
object DefaultAdBlocker {
    const val ID = "uBlock0@raymondhill.net"
    const val NAME = "uBlock Origin"

    /** Used when the catalog lookup is unavailable. */
    const val FALLBACK_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
}

/**
 * The default ad blocker until 0.13.8. Where it was switched on, [DefaultAdBlocker] is set up
 * once in its place and this one is switched off: archived, still installed with its settings
 * and filters, for anyone who wants it back.
 */
object ArchivedAdBlocker {
    const val ID = "adguardadblocker@adguard.com"
    const val NAME = "AdGuard AdBlocker"
}

data class ExtensionPopupState(
    val extensionId: String,
    val extensionName: String,
    val sessionId: String,
    /**
     * The size, in dp (CSS pixels), the popup's content asked for, measured by the built-in
     * popup helper the way desktop Firefox sizes its popup panel. Null until it has reported,
     * and always for popups made for Android, which take the default size.
     */
    val contentWidth: Int? = null,
    val contentHeight: Int? = null
)

enum class ExtensionsTab {
    INSTALLED,
    DISCOVER_AMO,
    INSTALL_XPI
}

data class ExtensionsUiState(
    val selectedTab: ExtensionsTab = ExtensionsTab.INSTALLED,
    val installedExtensions: List<InstalledExtension> = emptyList(),
    val catalogAddons: List<AmoAddon> = emptyList(),
    val searchQuery: String = "",
    val isLoadingCatalog: Boolean = false,
    /** The last catalog request failed; shown inline with a retry action. */
    val catalogError: String? = null,
    val isInstalling: Boolean = false,
    val installingIdOrUrl: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val pendingPrompt: ExtensionInstallPrompt? = null,
    val activePopup: ExtensionPopupState? = null,
    val isInstallingAdBlocker: Boolean = false,
    /** Set only when the user asked for the ad blocker and it could not be installed. */
    val adBlockerError: String? = null
) {
    val adBlocker: InstalledExtension?
        get() = installedExtensions.firstOrNull { it.id == DefaultAdBlocker.ID }

    val isAdBlockerOn: Boolean
        get() = adBlocker?.enabled == true
}
