package com.rembrowser.rembrowse.model

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
    val badgeText: String? = null
)

data class ExtensionInstallPrompt(
    val extensionId: String,
    val extensionName: String,
    val version: String,
    val permissions: List<String>,
    val onDecision: (Boolean) -> Unit
)

data class ExtensionPopupState(
    val extensionId: String,
    val extensionName: String,
    val sessionId: String
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
    val isInstalling: Boolean = false,
    val installingIdOrUrl: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val pendingPrompt: ExtensionInstallPrompt? = null,
    val activePopup: ExtensionPopupState? = null
)
