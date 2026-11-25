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
    /**
     * What the developer says the extension collects and sends them, as the engine names it (see
     * [ExtensionDataCollection]): [ExtensionDataCollection.NONE] for an extension that declares it
     * collects nothing, empty for one that declares nothing at all. Without the optional item,
     * which is [offersTechnicalData].
     */
    val dataCollection: List<String> = emptyList(),
    /**
     * Whether the extension offers to collect technical and interaction data if the user agrees
     * (only when it is installed): a checkbox, unchecked.
     */
    val offersTechnicalData: Boolean = false,
    /** The answer: whether to go ahead, and whether the user agreed to share technical data. */
    val onDecision: (allow: Boolean, shareTechnicalData: Boolean) -> Unit
)

/**
 * The data an extension can say it collects and sends its developer (its manifest's
 * `data_collection_permissions`; GeckoView's `WebExtension.DATA_COLLECTION_PERMISSIONS`), in
 * plain words for the permission prompt.
 */
object ExtensionDataCollection {
    /** The declaration that the extension collects nothing. */
    const val NONE = "none"

    /**
     * Technical and interaction data: the one kind an extension may only ask for as optional, so
     * the user can say no to it and still install the extension.
     */
    const val TECHNICAL_AND_INTERACTION = "technicalAndInteraction"

    private val words = mapOf(
        "authenticationInfo" to "Sign-in details, such as usernames and passwords",
        "bookmarksInfo" to "Your bookmarks",
        "browsingActivity" to "Your browsing history: the sites you visit, and when",
        "financialAndPaymentInfo" to "Financial and payment details, such as card numbers",
        "healthInfo" to "Health information, such as medical history",
        "locationInfo" to "Your location",
        "personalCommunications" to "Personal messages, such as emails and chats",
        "personallyIdentifyingInfo" to "Details that identify you, such as your name, email address or phone number",
        "searchTerms" to "What you search for",
        TECHNICAL_AND_INTERACTION to "Technical and interaction data: your device and browser versions, " +
            "the extension's settings, and how you use it",
        "websiteActivity" to "What you do on websites, such as clicks and scrolling",
        "websiteContent" to "The content of the sites you visit, such as text and pictures"
    )

    /** The kinds of data that have words here: every one the engine knows but [NONE]. */
    val described: Set<String> get() = words.keys

    /** [permission] in plain words; a kind added to the engine later is shown by its name. */
    fun describe(permission: String): String = words[permission] ?: permission

    /**
     * What a prompt of [kind] shows of the data collection permissions the engine passed with it
     * ([permissions]): the kinds collected, in the extension's order and once each, or just
     * [NONE] when the extension says it collects nothing; and whether the optional technical
     * data is offered with a checkbox. An install prompt is passed the optional item with the
     * required ones, and only there is it the user's choice; asked for later, by an optional
     * permission request, it is one of the kinds allowed or refused together.
     */
    fun forPrompt(kind: ExtensionPromptKind, permissions: List<String>): PromptDataCollection {
        val names = permissions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val offersTechnicalData = kind == ExtensionPromptKind.INSTALL && TECHNICAL_AND_INTERACTION in names
        val collected = if (offersTechnicalData) names - TECHNICAL_AND_INTERACTION else names
        return PromptDataCollection(
            collected = if (NONE in collected) listOf(NONE) else collected,
            offersTechnicalData = offersTechnicalData
        )
    }

    data class PromptDataCollection(val collected: List<String>, val offersTechnicalData: Boolean)
}

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
