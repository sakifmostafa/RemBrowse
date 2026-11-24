package com.rembrowser.rembrowse.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionState

/** What Settings → Site permissions can ask for. */
class SitePermissionActions(
    /** Reloads the decisions from the engine; called each time the section opens. */
    val onRefresh: () -> Unit = {},
    /** Changes one decision; null forgets it, so the site asks again. */
    val onSet: (entry: SitePermissionEntry, state: SitePermissionState?) -> Unit = { _, _ -> },
    /** Forgets every decision for a site, given by its origin. */
    val onReset: (origin: String) -> Unit = {}
)

/** Under the section's title: what the list is, and how to undo a decision. */
internal const val SitePermissionsSubtitle =
    "What you allowed or blocked for each site. Remove a decision and the site asks again next time."

/**
 * Settings → Site permissions: one card per site with each remembered decision, which can be
 * switched between Allowed and Blocked, or removed. A notice says when Android itself keeps
 * notifications from RemBrowse while a site is allowed to show them.
 */
@Composable
internal fun SitePermissionsSection(entries: List<SitePermissionEntry>, actions: SitePermissionActions) {
    LaunchedEffect(Unit) { actions.onRefresh() }

    val context = LocalContext.current
    // Looked at again on coming back, from Android's settings say
    var androidNotificationsOn by remember { mutableStateOf(appNotificationsOn(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { androidNotificationsOn = appNotificationsOn(context) }
    val notificationsAllowed = entries.any {
        it.kind == SitePermissionKind.NOTIFICATIONS && it.state == SitePermissionState.ALLOW
    }
    if (notificationsAllowed && !androidNotificationsOn) {
        NotificationsOffNotice(onOpenSettings = { openAppNotificationSettings(context) })
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (entries.isEmpty()) {
        EmptySitePermissions()
    } else {
        // Bounded by the sites the user answered, so a plain column inside the page's own
        // scroll container is enough, as for downloads.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.groupBy { it.origin }.forEach { (origin, decisions) ->
                SitePermissionsCard(origin = origin, decisions = decisions, actions = actions)
            }
        }
    }
}

@Composable
private fun EmptySitePermissions() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .testTag("site_permissions_empty"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "No site permissions yet", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "When a site asks to use your camera, microphone, location or notifications, " +
                "your answer appears here.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** A site, with Reset, over its decisions. */
@Composable
private fun SitePermissionsCard(
    origin: String,
    decisions: List<SitePermissionEntry>,
    actions: SitePermissionActions
) {
    val colors = MaterialTheme.colorScheme
    val host = decisions.first().displayHost
    // An http:// site is named with its scheme, so it is not mistaken for the https:// one
    val siteName = if (origin.startsWith("https://", ignoreCase = true)) host else "${origin.substringBefore("://")}://$host"
    ListCard(modifier = Modifier.testTag("site_permission_site_$origin")) {
        Column {
            Row(
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SiteIcon(url = origin, title = host)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = siteName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { actions.onReset(origin) },
                    modifier = Modifier
                        .semantics { contentDescription = "Reset permissions for $siteName" }
                        .testTag("site_permission_reset_$origin")
                ) { Text("Reset") }
            }
            HorizontalDivider(color = colors.outlineVariant)
            decisions.forEach { entry -> SitePermissionRow(entry = entry, actions = actions) }
        }
    }
}

/**
 * One decision: the whole row switches it between Allowed and Blocked (the word says which, not
 * only the switch), and the close button removes it.
 */
@Composable
private fun SitePermissionRow(entry: SitePermissionEntry, actions: SitePermissionActions) {
    val colors = MaterialTheme.colorScheme
    val label = sitePermissionLabel(entry.kind, entry.embeddedHost)
    val allowed = entry.state == SitePermissionState.ALLOW
    val tag = sitePermissionRowTag(entry)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .hapticToggleable(
                    value = allowed,
                    role = Role.Switch,
                    onValueChange = { allow ->
                        actions.onSet(entry, if (allow) SitePermissionState.ALLOW else SitePermissionState.BLOCK)
                    }
                )
                .testTag(tag)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = entry.kind.icon,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (allowed) "Allowed" else "Blocked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = allowed,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.primaryContainer,
                    checkedThumbColor = colors.onPrimaryContainer,
                    checkedBorderColor = colors.primaryContainer
                )
            )
        }
        RemIconButton(
            icon = Icons.Outlined.Close,
            label = "Forget decision: $label",
            onClick = { actions.onSet(entry, null) },
            iconSize = 18.dp,
            modifier = Modifier.testTag("${tag}_remove")
        )
    }
}

/** "site_permission_https://example.com_camera", plus "_<embedded origin>" for cookies. */
internal fun sitePermissionRowTag(entry: SitePermissionEntry): String =
    "site_permission_${entry.origin}_${entry.kind.name.lowercase()}" +
        (entry.embeddedOrigin?.let { "_$it" } ?: "")

/** Android keeps RemBrowse from posting notifications, which sites are allowed to show. */
@Composable
private fun NotificationsOffNotice(onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("site_permissions_notifications_notice")
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(end = 8.dp)) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsOff,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = "Android notifications are off for RemBrowse", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Sites you allow can't show notifications until you turn them on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("site_permissions_open_notification_settings")
            ) { Text("Open settings") }
        }
    }
}

/**
 * Whether Android lets RemBrowse post notifications: the app's notifications are on and, from
 * Android 13, the POST_NOTIFICATIONS permission is granted.
 */
private fun appNotificationsOn(context: Context): Boolean {
    val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** Android's notification settings for RemBrowse, or its app info page if those cannot open. */
private fun openAppNotificationSettings(context: Context) {
    val newTask = context !is Activity
    val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    for (intent in listOf(notifications, appInfo)) {
        if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
