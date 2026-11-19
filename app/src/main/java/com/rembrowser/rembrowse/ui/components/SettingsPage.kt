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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.R
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.ExtensionsUiState
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.ui.theme.tones

/** Settings rows are capped to this width so a label and its switch stay close on wide windows. */
private val ContentMaxWidth = 760.dp

private val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.APPEARANCE -> Icons.Outlined.Palette
        SettingsSection.SEARCH -> Icons.Outlined.Search
        SettingsSection.PRIVACY -> Icons.Outlined.Shield
        SettingsSection.EXTENSIONS -> Icons.Outlined.Extension
        SettingsSection.ABOUT -> Icons.Outlined.Info
    }

/** What the settings page can change. Grouped so the page is not a wall of parameters. */
class SettingsActions(
    val onSetDarkTheme: (Boolean) -> Unit,
    val onSetAccent: (AccentColor) -> Unit,
    val onSetSearchEngine: (SearchEngine) -> Unit,
    val onSetEnhancedPrivacy: (Boolean) -> Unit,
    val onSetAdBlocker: (Boolean) -> Unit
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
    actions: SettingsActions,
    extensionActions: ExtensionsPanelActions,
    modifier: Modifier = Modifier
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
        val wide = maxWidth >= 640.dp

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
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .widthIn(max = ContentMaxWidth)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    SectionHeader(title = section.label, subtitle = null)
                    when (section) {
                        SettingsSection.APPEARANCE -> AppearanceSection(
                            settings = settings,
                            onSetDarkTheme = actions.onSetDarkTheme,
                            onSetAccent = actions.onSetAccent
                        )
                        SettingsSection.SEARCH -> SearchSection(
                            selected = settings.searchEngine,
                            onSelect = actions.onSetSearchEngine
                        )
                        SettingsSection.PRIVACY -> PrivacySection(
                            settings = settings,
                            adBlockerOn = extensionsState.isAdBlockerOn,
                            adBlockerBusy = extensionsState.isInstallingAdBlocker,
                            adBlockerError = extensionsState.adBlockerError,
                            onSetAdBlocker = actions.onSetAdBlocker,
                            onSetEnhancedPrivacy = actions.onSetEnhancedPrivacy
                        )
                        SettingsSection.ABOUT -> AboutSection(versionName)
                        SettingsSection.EXTENSIONS -> Unit
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
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 16.dp)
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

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
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
    Text(text = "Search engine", style = MaterialTheme.typography.titleSmall)
    Text(
        text = "Used when you type words, rather than an address, into the address bar or the command palette.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
    )
    Column(modifier = Modifier.selectableGroup()) {
        SearchEngine.entries.forEach { engine ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = engine == selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(engine) }
                    )
                    .testTag("search_engine_${engine.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = engine == selected, onClick = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = engine.label, style = MaterialTheme.typography.bodyLarge)
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
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
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
    onSetDarkTheme: (Boolean) -> Unit,
    onSetAccent: (AccentColor) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Text(text = "Theme color", style = MaterialTheme.typography.titleSmall)
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
                    .clip(CircleShape)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSetAccent(accent) }
                    )
                    .semantics { contentDescription = accent.label }
                    .testTag("accent_${accent.name.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                // The selected swatch gets a ring, so the choice does not rely on hue alone
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
    SwitchRow(
        title = "Use dark theme",
        description = null,
        checked = settings.useDarkTheme,
        onCheckedChange = onSetDarkTheme,
        testTag = "setting_dark_theme"
    )
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
            adBlockerBusy -> "Installing AdGuard AdBlocker…"
            adBlockerError != null -> adBlockerError
            else -> "Uses the AdGuard AdBlocker extension from addons.mozilla.org. To filter pages " +
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
private fun AboutSection(versionName: String) {
    val colors = MaterialTheme.colorScheme
    Image(
        painter = painterResource(R.drawable.rembrowse_mark),
        contentDescription = "RemBrowse logo",
        modifier = Modifier.size(72.dp)
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = "RemBrowse $versionName", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "A tiled browser for Android laptops, built on Mozilla GeckoView with native " +
            "WebExtension support.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant
    )
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
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
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
