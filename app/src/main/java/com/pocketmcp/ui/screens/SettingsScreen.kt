package com.pocketmcp.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pocketmcp.ui.components.*
import com.pocketmcp.ui.theme.*

@Composable
fun SettingsScreen(
    isApiAuthEnabled: Boolean,
    isTailscaleEnabled: Boolean,
    isNotificationAccessEnabled: Boolean,
    isFileStorageEnabled: Boolean,
    isDeveloperModeEnabled: Boolean,
    accessibilityState: String,
    safetyFilterMode: String,
    onToggleApiAuth: (Boolean) -> Unit,
    onToggleTailscale: (Boolean) -> Unit,
    onToggleNotificationAccess: () -> Unit,
    onToggleFileStorage: (Boolean) -> Unit,
    onToggleDeveloperMode: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onCycleSafetyFilter: () -> Unit,
    onManageKeys: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Header
        Column {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = NeuralOnBackground
            )
            Text(
                text = "Configure server and permissions",
                style = MaterialTheme.typography.bodyMedium,
                color = NeuralOnSurfaceVariant
            )
        }

        // Authentication Section
        SettingsSection(
            title = "Authentication",
            accentColor = NeuralSuccess,
            icon = Icons.Rounded.Security
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsToggleRow(
                    title = "API Key Auth",
                    subtitle = "Require key for MCP requests",
                    icon = Icons.Rounded.VpnKey,
                    checked = isApiAuthEnabled,
                    onCheckedChange = onToggleApiAuth
                )
                SettingsToggleRow(
                    title = "Tailscale",
                    subtitle = "Enable Tailscale networking",
                    icon = Icons.Rounded.NetworkCheck,
                    checked = isTailscaleEnabled,
                    onCheckedChange = onToggleTailscale
                )
                SettingsActionRow(
                    title = "Manage Keys",
                    subtitle = "Copy current API key",
                    icon = Icons.Rounded.Key,
                    accentColor = NeuralSuccess,
                    onClick = onManageKeys
                )
            }
        }

        // System Permissions Section
        SettingsSection(
            title = "System Permissions",
            accentColor = NeuralPrimary,
            icon = Icons.Rounded.AdminPanelSettings
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionRow(
                    title = "Accessibility Service",
                    subtitle = accessibilityState,
                    icon = Icons.Rounded.Accessibility,
                    accentColor = if (accessibilityState.contains("Enabled")) NeuralSuccess else NeuralWarning,
                    onClick = onOpenAccessibilitySettings
                )
                SettingsToggleRow(
                    title = "Notification Access",
                    subtitle = "Read notification stream",
                    icon = Icons.Rounded.NotificationsActive,
                    checked = isNotificationAccessEnabled,
                    onCheckedChange = { onToggleNotificationAccess() }
                )
                SettingsToggleRow(
                    title = "File Storage",
                    subtitle = "Access media and documents",
                    icon = Icons.Rounded.FolderOpen,
                    checked = isFileStorageEnabled,
                    onCheckedChange = onToggleFileStorage
                )
            }
        }

        // Advanced Section
        SettingsSection(
            title = "Advanced",
            accentColor = NeuralTertiary,
            icon = Icons.Rounded.Tune
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionRow(
                    title = "Safety Filter",
                    subtitle = "Mode: $safetyFilterMode",
                    icon = Icons.Rounded.Shield,
                    accentColor = when (safetyFilterMode) {
                        "Strict" -> NeuralSuccess
                        "Balanced" -> NeuralWarning
                        else -> NeuralError
                    },
                    onClick = onCycleSafetyFilter
                )
                SettingsToggleRow(
                    title = "Developer Mode",
                    subtitle = "Show debug information",
                    icon = Icons.Rounded.Code,
                    checked = isDeveloperModeEnabled,
                    onCheckedChange = onToggleDeveloperMode
                )
            }
        }

        // Version footer
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "PocketMCP v0.2.6",
                style = MaterialTheme.typography.labelSmall,
                color = NeuralOnSurfaceMuted
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    accentColor: Color,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NeuralOnBackground
            )
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            active = false,
            cornerRadius = 20.dp,
            contentPadding = PaddingValues(8.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(NeuralSurfaceHighlight.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) NeuralSuccess else NeuralOnSurfaceMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = NeuralOnSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NeuralOnSurfaceVariant
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NeuralSuccess,
                checkedTrackColor = NeuralSuccess.copy(alpha = 0.3f),
                uncheckedThumbColor = NeuralOnSurfaceMuted,
                uncheckedTrackColor = NeuralSurfaceHighlight
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = NeuralOnSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = accentColor
            )
        }

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = NeuralOnSurfaceMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}
