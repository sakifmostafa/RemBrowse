package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.ui.theme.tones

private enum class SettingsSection(val label: String, val icon: ImageVector) {
    APPEARANCE("Appearance", Icons.Outlined.Palette),
    PRIVACY("Privacy", Icons.Outlined.Shield),
    ABOUT("About", Icons.Outlined.Info)
}

/**
 * Settings panel: section list on the left (a chip row on narrow windows), content on the
 * right. Only sections that change real behaviour are listed.
 */
@Composable
fun SettingsDialog(
    settings: BrowserSettings,
    versionName: String,
    onSetDarkTheme: (Boolean) -> Unit,
    onSetAccent: (AccentColor) -> Unit,
    onSetEnhancedPrivacy: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var section by rememberSaveable { mutableStateOf(SettingsSection.APPEARANCE) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .testTag("settings_dialog"),
            shape = MaterialTheme.shapes.large,
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = 12.dp
        ) {
            BoxWithConstraints {
                val wide = maxWidth >= 560.dp
                val body: @Composable (Modifier) -> Unit = { modifier ->
                    Column(
                        modifier = modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(text = section.label, style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        when (section) {
                            SettingsSection.APPEARANCE -> AppearanceSection(settings, onSetDarkTheme, onSetAccent)
                            SettingsSection.PRIVACY -> PrivacySection(settings, onSetEnhancedPrivacy)
                            SettingsSection.ABOUT -> AboutSection(versionName)
                        }
                    }
                }

                if (wide) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .width(200.dp)
                                .fillMaxHeight()
                                .background(colors.surfaceContainerLow)
                                .padding(12.dp)
                                .selectableGroup(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 12.dp)
                            )
                            SettingsSection.entries.forEach { item ->
                                SectionNavItem(
                                    section = item,
                                    selected = item == section,
                                    onClick = { section = item },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        VerticalHairline()
                        Box(modifier = Modifier.weight(1f)) {
                            body(Modifier.fillMaxWidth())
                            RemIconButton(
                                icon = Icons.Outlined.Close,
                                label = "Close settings",
                                onClick = onDismiss,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                            )
                        }
                    }
                } else {
                    Column {
                        Row(
                            modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            RemIconButton(
                                icon = Icons.Outlined.Close,
                                label = "Close settings",
                                onClick = onDismiss
                            )
                        }
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp)
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SettingsSection.entries.forEach { item ->
                                SectionNavItem(section = item, selected = item == section, onClick = { section = item })
                            }
                        }
                        HorizontalDivider(
                            color = colors.outlineVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        body(Modifier.fillMaxWidth())
                    }
                }
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
            .testTag("settings_section_${section.name.lowercase()}")
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
private fun PrivacySection(settings: BrowserSettings, onSetEnhancedPrivacy: (Boolean) -> Unit) {
    SwitchRow(
        title = "Enhanced privacy (restricts tracking)",
        description = "Blocks trackers, fingerprinters and cryptominers on every site. " +
            "Turn it off if a site you trust stops working.",
        checked = settings.enhancedPrivacy,
        onCheckedChange = onSetEnhancedPrivacy,
        testTag = "setting_enhanced_privacy"
    )
}

@Composable
private fun AboutSection(versionName: String) {
    val colors = MaterialTheme.colorScheme
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
    testTag: String
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
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
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primaryContainer,
                checkedThumbColor = colors.onPrimaryContainer,
                checkedBorderColor = colors.primaryContainer
            )
        )
    }
}
