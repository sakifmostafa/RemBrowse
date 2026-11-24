package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.R
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.DownloadsUiState
import com.rembrowser.rembrowse.model.ExtensionsUiState
import com.rembrowser.rembrowse.model.HistoryEntry
import com.rembrowser.rembrowse.model.PageRendering
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.ThemeMode
import com.rembrowser.rembrowse.viewmodel.TerminalUiState
import com.rembrowser.rembrowse.model.UpdateStatus
import com.rembrowser.rembrowse.model.UpdatesUiState
import com.rembrowser.rembrowse.ui.theme.systemColorsAvailable
import com.rembrowser.rembrowse.ui.theme.tones
import com.rembrowser.rembrowse.util.ByteSizes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Settings rows are capped to this width so a label and its switch stay close on wide windows. */
private val ContentMaxWidth = 760.dp

/**
 * From this width the section list stands beside the section instead of above it: a little
 * past where a window stops being compact (600dp), so a medium window's section still gets a
 * readable width beside the list.
 */
private val SectionsBesideMinWidth = 640.dp

private val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.APPEARANCE -> Icons.Outlined.Palette
        SettingsSection.SEARCH -> Icons.Outlined.Search
        SettingsSection.TABS -> Icons.Outlined.Tab
        SettingsSection.PRIVACY -> Icons.Outlined.Shield
        SettingsSection.SITE_PERMISSIONS -> Icons.Outlined.Tune
        SettingsSection.HISTORY -> Icons.Outlined.History
        SettingsSection.DOWNLOADS -> Icons.Outlined.FileDownload
        SettingsSection.EXTENSIONS -> Icons.Outlined.Extension
        SettingsSection.TERMINAL -> Icons.Outlined.Terminal
        SettingsSection.ABOUT -> Icons.Outlined.Info
    }

/** What the settings page can change. Grouped so the page is not a wall of parameters. */
class SettingsActions(
    val onSetThemeMode: (ThemeMode) -> Unit,
    val onSetAccent: (AccentColor) -> Unit,
    val onSetSearchEngine: (SearchEngine) -> Unit,
    val onSetRestoreTabsOnStartup: (Boolean) -> Unit,
    val onSetOpenNewTabsNextToCurrent: (Boolean) -> Unit,
    val onSetDesktopSiteByDefault: (Boolean) -> Unit,
    val onSetEnhancedPrivacy: (Boolean) -> Unit,
    val onSetAdBlocker: (Boolean) -> Unit,
    val onSetAskBeforeDownloading: (Boolean) -> Unit,
    val onSetKeepHistory: (Boolean) -> Unit = {},
    val onSetPageRendering: (PageRendering) -> Unit = {},
    val onSetSwipeNavigation: (Boolean) -> Unit = {},
    val onSetReverseSwipeDirection: (Boolean) -> Unit = {},
    val onSetSystemColors: (Boolean) -> Unit = {}
)

/**
 * RemBrowse's own settings page, shown inside a tab at `rem://settings/<section>`.
 *
 * Sections are listed on the left (as a chip row on narrow windows) and each has its own
 * address, so a section can be typed into the address bar and the tab reopens on the same
 * section after a restart. Only sections that change real behaviour are listed.
 *
 * The page is drawn entirely by the app: its tab has no engine session, and web content
 * cannot open or navigate to a `rem://` address.
 */
// BringIntoViewRequester is stable from Compose Foundation 1.8; this build is on 1.7.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsPage(
    section: SettingsSection,
    onSelectSection: (SettingsSection) -> Unit,
    settings: BrowserSettings,
    versionName: String,
    extensionsState: ExtensionsUiState,
    downloadsState: DownloadsUiState,
    downloadLocation: String,
    canShowDownloadFolder: Boolean,
    actions: SettingsActions,
    extensionActions: ExtensionsPanelActions,
    downloadActions: DownloadsPanelActions,
    modifier: Modifier = Modifier,
    history: List<HistoryEntry> = emptyList(),
    historyActions: HistoryActions = HistoryActions(onOpen = {}, onRemove = {}, onClear = {}),
    /** The app's own updates, for About; null leaves the update controls out. */
    updates: UpdatesUiState? = null,
    updateActions: UpdateActions? = null,
    /** Saved hosts and keys, for Terminal; null leaves the section empty. */
    terminal: TerminalUiState? = null,
    terminalActions: TerminalSettingsActions? = null,
    /** The remembered site permission decisions, for Site permissions. */
    sitePermissions: List<SitePermissionEntry> = emptyList(),
    sitePermissionActions: SitePermissionActions = SitePermissionActions()
) {
    val colors = MaterialTheme.colorScheme

    // A Surface, not a plain box: it also sets the text colour for everything on the page.
    // Without it, unstyled text falls back to black and vanishes on the dark theme.
    Surface(
        modifier = modifier.testTag("settings_page"),
        color = colors.surfaceContainerLowest,
        contentColor = colors.onSurface
    ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= SectionsBesideMinWidth

        // The body takes the remaining space; its content is capped to a readable width inside
        // it. (A cap on the weighted element itself would be ignored.)
        val body: @Composable (Modifier) -> Unit = { bodyModifier ->
            Box(modifier = bodyModifier.fillMaxHeight()) {
            if (section == SettingsSection.EXTENSIONS) {
                // The extension lists scroll on their own, so this section gets a fixed height
                // instead of sitting inside the page's scroll container.
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = ContentMaxWidth)
                        .padding(start = 24.dp, end = 24.dp, top = 20.dp)
                ) {
                    SectionHeader(
                        title = section.label,
                        subtitle = "Add-ons from Firefox Add-ons (addons.mozilla.org) run here as they do in Firefox."
                    )
                    ExtensionsPanel(
                        uiState = extensionsState,
                        actions = extensionActions,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else if (section == SettingsSection.HISTORY) {
                // The history list scrolls on its own, like the extension lists
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = ContentMaxWidth)
                        .padding(start = 24.dp, end = 24.dp, top = 20.dp)
                ) {
                    SectionHeader(title = section.label, subtitle = null)
                    SwitchRow(
                        title = "Keep browsing history",
                        description = "Lists the web pages you open, newest first. Turning this off stops recording " +
                            "and keeps what is already here.",
                        checked = settings.keepHistory,
                        onCheckedChange = actions.onSetKeepHistory,
                        testTag = "setting_keep_history"
                    )
                    HorizontalDivider(color = colors.outlineVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    HistoryPanel(
                        entries = history,
                        keepHistory = settings.keepHistory,
                        actions = historyActions,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .widthIn(max = ContentMaxWidth)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    SectionHeader(
                        title = section.label,
                        subtitle = if (section == SettingsSection.SITE_PERMISSIONS) SitePermissionsSubtitle else null
                    )
                    when (section) {
                        SettingsSection.APPEARANCE -> AppearanceSection(
                            settings = settings,
                            onSetThemeMode = actions.onSetThemeMode,
                            onSetAccent = actions.onSetAccent,
                            onSetSystemColors = actions.onSetSystemColors,
                            onSetPageRendering = actions.onSetPageRendering
                        )
                        SettingsSection.SEARCH -> SearchSection(
                            selected = settings.searchEngine,
                            onSelect = actions.onSetSearchEngine
                        )
                        SettingsSection.TABS -> TabsSection(settings = settings, actions = actions)
                        SettingsSection.DOWNLOADS -> DownloadsSection(
                            askBeforeDownloading = settings.askBeforeDownloading,
                            onSetAskBeforeDownloading = actions.onSetAskBeforeDownloading,
                            downloadsState = downloadsState,
                            downloadLocation = downloadLocation,
                            canShowDownloadFolder = canShowDownloadFolder,
                            downloadActions = downloadActions
                        )
                        SettingsSection.PRIVACY -> PrivacySection(
                            settings = settings,
                            adBlockerOn = extensionsState.isAdBlockerOn,
                            adBlockerBusy = extensionsState.isInstallingAdBlocker,
                            adBlockerError = extensionsState.adBlockerError,
                            onSetAdBlocker = actions.onSetAdBlocker,
                            onSetEnhancedPrivacy = actions.onSetEnhancedPrivacy
                        )
                        SettingsSection.SITE_PERMISSIONS -> SitePermissionsSection(
                            entries = sitePermissions,
                            actions = sitePermissionActions
                        )
                        SettingsSection.ABOUT -> AboutSection(versionName, updates, updateActions)
                        SettingsSection.TERMINAL -> if (terminal != null && terminalActions != null) {
                            TerminalSettingsSection(state = terminal, actions = terminalActions)
                        }
                        SettingsSection.EXTENSIONS, SettingsSection.HISTORY -> Unit
                    }
                }
            }
            }
        }

        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(232.dp)
                        .fillMaxHeight()
                        .background(colors.surfaceContainerLow)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .padding(start = 12.dp, top = 8.dp, bottom = 16.dp)
                            .semantics { heading() }
                    )
                    SettingsSection.entries.forEach { item ->
                        SectionNavItem(
                            section = item,
                            selected = item == section,
                            onClick = { onSelectSection(item) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                VerticalHairline()
                body(Modifier.weight(1f))
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SettingsSection.entries.forEach { item ->
                        val selected = item == section
                        // The row scrolls, so the open section's chip is brought into view:
                        // opening Extensions directly must not leave its chip off screen.
                        val bringIntoView = remember { BringIntoViewRequester() }
                        LaunchedEffect(selected) {
                            if (selected) bringIntoView.bringIntoView()
                        }
                        SectionNavItem(
                            section = item,
                            selected = selected,
                            onClick = { onSelectSection(item) },
                            modifier = Modifier.bringIntoViewRequester(bringIntoView)
                        )
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                body(Modifier.weight(1f))
            }
        }
    }
    }
}

/** The title of a group of settings ("Theme color"), marked as a heading for screen readers. */
@Composable
internal fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = modifier.semantics { heading() })
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Text(text = title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    if (subtitle != null) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun SearchSection(selected: SearchEngine, onSelect: (SearchEngine) -> Unit) {
    val colors = MaterialTheme.colorScheme
    GroupTitle("Search engine")
    Text(
        text = "Used when you type words, rather than an address, into the address bar or the command palette.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
    )
    Column(modifier = Modifier.selectableGroup()) {
        SearchEngine.entries.forEach { engine ->
            RadioRow(
                title = engine.label,
                description = null,
                selected = engine == selected,
                onClick = { onSelect(engine) },
                testTag = "search_engine_${engine.name.lowercase()}"
            )
        }
    }
}

@Composable
private fun TabsSection(settings: BrowserSettings, actions: SettingsActions) {
    val colors = MaterialTheme.colorScheme
    GroupTitle("When RemBrowse starts")
    Spacer(modifier = Modifier.height(4.dp))
    Column(modifier = Modifier.selectableGroup()) {
        RadioRow(
            title = "Continue where you left off",
            description = "Reopens the tabs and the layout you had last time.",
            selected = settings.restoreTabsOnStartup,
            onClick = { actions.onSetRestoreTabsOnStartup(true) },
            testTag = "setting_startup_restore"
        )
        RadioRow(
            title = "Open a new tab",
            description = "Last time's tabs are closed. Bookmarks and settings are kept.",
            selected = !settings.restoreTabsOnStartup,
            onClick = { actions.onSetRestoreTabsOnStartup(false) },
            testTag = "setting_startup_new_tab"
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = colors.outlineVariant)
    Spacer(modifier = Modifier.height(16.dp))

    GroupTitle("Open new tabs")
    Spacer(modifier = Modifier.height(4.dp))
    Column(modifier = Modifier.selectableGroup()) {
        RadioRow(
            title = "At the end of the tab list",
            description = null,
            selected = !settings.openNewTabsNextToCurrent,
            onClick = { actions.onSetOpenNewTabsNextToCurrent(false) },
            testTag = "setting_new_tab_end"
        )
        RadioRow(
            title = "Next to the current tab",
            description = null,
            selected = settings.openNewTabsNextToCurrent,
            onClick = { actions.onSetOpenNewTabsNextToCurrent(true) },
            testTag = "setting_new_tab_next"
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = colors.outlineVariant)

    SwitchRow(
        title = "Ask for desktop sites",
        description = "New tabs ask websites for their desktop version rather than the mobile one. " +
            "To change it for one tab, use More → Desktop site.",
        checked = settings.desktopSiteByDefault,
        onCheckedChange = actions.onSetDesktopSiteByDefault,
        testTag = "setting_desktop_default"
    )
    HorizontalDivider(color = colors.outlineVariant)

    SwitchRow(
        title = "Swipe with two fingers to go back",
        description = "On a touchpad, swipe right with two fingers to go back and left to go forward. " +
            "Pages that scroll sideways keep the swipe until they reach their edge.",
        checked = settings.swipeNavigation,
        onCheckedChange = actions.onSetSwipeNavigation,
        testTag = "setting_swipe_navigation"
    )
    if (settings.swipeNavigation) {
        SwitchRow(
            title = "Swipe the other way",
            description = "Swipe left to go back and right to go forward. RemBrowse already follows the " +
                "touchpad's Reverse scrolling setting; use this if swipes still go the wrong way.",
            checked = settings.reverseSwipeDirection,
            onCheckedChange = actions.onSetReverseSwipeDirection,
            testTag = "setting_reverse_swipe"
        )
    }
}

@Composable
private fun DownloadsSection(
    askBeforeDownloading: Boolean,
    onSetAskBeforeDownloading: (Boolean) -> Unit,
    downloadsState: DownloadsUiState,
    downloadLocation: String,
    canShowDownloadFolder: Boolean,
    downloadActions: DownloadsPanelActions
) {
    SwitchRow(
        title = "Ask before downloading",
        description = "Shows the file's name, size and the site it comes from, and waits for your OK. " +
            "When this is off, files are saved as soon as a page offers them.",
        checked = askBeforeDownloading,
        onCheckedChange = onSetAskBeforeDownloading,
        testTag = "setting_ask_before_download"
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(16.dp))
    DownloadsPanel(
        uiState = downloadsState,
        locationLabel = downloadLocation,
        actions = downloadActions,
        showOpenFolder = canShowDownloadFolder
    )
}

/** One choice in a group. The whole row is the touch target; the button itself is decoration. */
@Composable
private fun RadioRow(
    title: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = ripple(focusRingShape = MaterialTheme.shapes.small),
                role = Role.RadioButton,
                onClick = onClick
            )
            .testTag(testTag)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionNavItem(
    section: SettingsSection,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .border(1.dp, if (selected) colors.primary else Color.Transparent, MaterialTheme.shapes.small)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = ripple(focusRingShape = MaterialTheme.shapes.small),
                role = Role.Tab,
                onClick = onClick
            )
            .testTag("settings_section_${section.path}")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = section.icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = section.label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
private fun AppearanceSection(
    settings: BrowserSettings,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetAccent: (AccentColor) -> Unit,
    onSetSystemColors: (Boolean) -> Unit,
    onSetPageRendering: (PageRendering) -> Unit,
    /** Whether the device has wallpaper colours to offer (Android 12+). */
    canUseSystemColors: Boolean = systemColorsAvailable
) {
    val colors = MaterialTheme.colorScheme
    GroupTitle("Theme")
    Spacer(modifier = Modifier.height(12.dp))
    // Three choices, one on: a connected button group. It keeps the tag of the dark theme switch
    // it replaced.
    ConnectedChoiceGroup(
        options = ThemeMode.entries,
        selected = settings.themeMode,
        onSelect = onSetThemeMode,
        label = { it.label },
        testTag = { "theme_${it.name.lowercase()}" },
        modifier = Modifier.testTag("setting_dark_theme")
    )
    ThemeMode.SYSTEM.description?.let { systemMeans ->
        Text(
            text = systemMeans,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(color = colors.outlineVariant)
    Spacer(modifier = Modifier.height(16.dp))

    GroupTitle("Theme color")
    // The accents stay chosen while the wallpaper's colours are used, for when they are turned off
    val accentsInUse = !(canUseSystemColors && settings.useSystemColors)
    if (canUseSystemColors) {
        SwitchRow(
            title = "Use system colors",
            description = "Takes its colors from your wallpaper (Material You). The theme color below " +
                "comes back when this is off.",
            checked = settings.useSystemColors,
            onCheckedChange = onSetSystemColors,
            testTag = "setting_system_colors"
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AccentColor.entries.forEach { accent ->
            val selected = accent == settings.accent
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .keyboardFocusRing(CircleShape)
                    .clip(CircleShape)
                    .selectable(
                        selected = selected,
                        interactionSource = null,
                        // The ring is keyboardFocusRing's, outside the swatch (see below)
                        indication = ripple(enableFocusIndication = false),
                        enabled = accentsInUse,
                        role = Role.RadioButton,
                        onClick = { onSetAccent(accent) }
                    )
                    // Material's disabled emphasis
                    .alpha(if (accentsInUse) 1f else 0.38f)
                    .semantics { contentDescription = accent.label }
                    .testTag("accent_${accent.name.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                // The selected swatch gets a ring, so the choice does not rely on hue alone. The
                // keyboard's ring stays outside it (keyboardFocusRing): Material's inset ring would
                // sit on this one and hide which swatch is chosen.
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .border(2.dp, colors.onSurface, CircleShape)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accent.tones().swatch)
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(color = colors.outlineVariant)
    Spacer(modifier = Modifier.height(16.dp))

    GroupTitle("Page rendering")
    Text(
        text = "How web pages are put on the screen. Try another choice if scrolling stutters; " +
            "pages that are open switch at once.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
    )
    Column(modifier = Modifier.selectableGroup()) {
        PageRendering.entries.forEach { rendering ->
            RadioRow(
                title = rendering.label,
                description = rendering.description,
                selected = rendering == settings.pageRendering,
                onClick = { onSetPageRendering(rendering) },
                testTag = "page_rendering_${rendering.name.lowercase()}"
            )
        }
    }
}

@Composable
private fun PrivacySection(
    settings: BrowserSettings,
    adBlockerOn: Boolean,
    adBlockerBusy: Boolean,
    adBlockerError: String?,
    onSetAdBlocker: (Boolean) -> Unit,
    onSetEnhancedPrivacy: (Boolean) -> Unit
) {
    SwitchRow(
        title = "Block ads and trackers",
        description = when {
            adBlockerBusy -> "Installing uBlock Origin…"
            adBlockerError != null -> adBlockerError
            else -> "Uses the uBlock Origin extension from addons.mozilla.org. To filter pages " +
                "it can read and change the sites you visit. Manage or remove it under Extensions."
        },
        checked = adBlockerOn,
        onCheckedChange = onSetAdBlocker,
        testTag = "setting_ad_blocker",
        enabled = !adBlockerBusy,
        isError = adBlockerError != null && !adBlockerBusy
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SwitchRow(
        title = "Enhanced privacy (restricts tracking)",
        description = "Also blocks known fingerprinters, cryptominers and email trackers. " +
            "Standard tracking protection stays on either way. Turn this off if a site stops working.",
        checked = settings.enhancedPrivacy,
        onCheckedChange = onSetEnhancedPrivacy,
        testTag = "setting_enhanced_privacy"
    )
}

@Composable
private fun AboutSection(versionName: String, updates: UpdatesUiState?, updateActions: UpdateActions?) {
    val colors = MaterialTheme.colorScheme
    Image(
        painter = painterResource(R.drawable.rembrowse_mark),
        contentDescription = "RemBrowse logo",
        modifier = Modifier.size(72.dp)
    )
    Spacer(modifier = Modifier.height(12.dp))
    GroupTitle("RemBrowse $versionName")
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "A private, native browser made for Googlebook, where SSH is just another tab.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant
    )
    if (updates != null && updateActions != null) {
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = colors.outlineVariant)
        Spacer(modifier = Modifier.height(16.dp))
        UpdatesBlock(state = updates, actions = updateActions)
    }
}

/** Where the app's own update stands, the one button that moves it on, and the daily check. */
@Composable
private fun UpdatesBlock(state: UpdatesUiState, actions: UpdateActions) {
    val colors = MaterialTheme.colorScheme
    val status = state.status
    val lastChecked = state.lastCheckedMillis?.let(::lastCheckedText)

    GroupTitle("Updates")
    Text(
        text = when (status) {
            UpdateStatus.Idle -> lastChecked?.let { withFullStop("Last checked $it") } ?: "See whether a newer RemBrowse is out."
            UpdateStatus.Checking -> "Checking for updates…"
            UpdateStatus.UpToDate -> "RemBrowse is up to date." + (lastChecked?.let { " " + withFullStop("Last checked $it") } ?: "")
            is UpdateStatus.Available ->
                "RemBrowse ${status.update.versionName} is available (${ByteSizes.format(status.update.size)})."
            is UpdateStatus.Downloading ->
                "Downloading RemBrowse ${status.update.versionName}: ${ByteSizes.format(status.receivedBytes)} " +
                    "of ${ByteSizes.format(status.update.size)}"
            is UpdateStatus.ReadyToInstall ->
                "RemBrowse ${status.update.versionName} is downloaded. Android asks you to confirm the update; " +
                    "your tabs and settings are kept."
            is UpdateStatus.Failed -> status.message
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (status is UpdateStatus.Failed) colors.error else colors.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 2.dp, bottom = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("update_status")
    )
    if (status is UpdateStatus.Downloading) {
        LinearProgressIndicator(
            progress = { status.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            UpdateStatus.Idle, UpdateStatus.UpToDate, UpdateStatus.Checking -> PrimaryDialogButton(
                label = "Check for updates",
                enabled = status != UpdateStatus.Checking,
                onClick = actions.onCheck,
                modifier = Modifier.testTag("update_action")
            )
            is UpdateStatus.Available -> PrimaryDialogButton(
                label = "Download and install",
                onClick = actions.onDownload,
                modifier = Modifier.testTag("update_action")
            )
            is UpdateStatus.Downloading -> RemOutlinedButton(
                onClick = actions.onCancel,
                modifier = Modifier.testTag("update_cancel")
            ) { Text("Cancel") }
            is UpdateStatus.ReadyToInstall -> PrimaryDialogButton(
                label = "Install",
                onClick = actions.onInstall,
                modifier = Modifier.testTag("update_action")
            )
            is UpdateStatus.Failed -> PrimaryDialogButton(
                label = if (status.update != null) "Try again" else "Check for updates",
                onClick = if (status.update != null) actions.onDownload else actions.onCheck,
                modifier = Modifier.testTag("update_action")
            )
        }
    }
    state.message?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 12.dp)
                .testTag("update_message")
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    SwitchRow(
        title = "Check for updates automatically",
        description = "Once a day RemBrowse looks on GitHub for a newer version and lets you know. " +
            "Nothing is downloaded until you choose to.",
        checked = state.checkAutomatically,
        onCheckedChange = actions.onSetCheckAutomatically,
        testTag = "setting_check_updates"
    )
}

/** [text] ending in one full stop, also when it ends with a time such as "9:23 a.m." */
internal fun withFullStop(text: String): String = if (text.endsWith(".")) text else "$text."

/** "today at 10:42", or "on 18 Nov 2026". */
private fun lastCheckedText(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val locale = Locale.getDefault()
    return if (time.toLocalDate() == LocalDate.now(zone)) {
        "today at " + time.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    } else {
        "on " + time.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

/** The whole row toggles, which gives the switch a full-width touch target and one label. */
@Composable
private fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    enabled: Boolean = true,
    isError: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .hapticToggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(testTag)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primaryContainer,
                checkedThumbColor = colors.onPrimaryContainer,
                checkedBorderColor = colors.primaryContainer
            )
        )
    }
}
