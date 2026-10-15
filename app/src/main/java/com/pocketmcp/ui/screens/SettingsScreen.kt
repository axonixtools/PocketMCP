package com.pocketmcp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pocketmcp.BuildConfig
import com.pocketmcp.ui.components.GroupedCard
import com.pocketmcp.ui.components.GroupedSection
import com.pocketmcp.ui.components.ListRow
import com.pocketmcp.ui.components.RowChevron
import com.pocketmcp.ui.components.RowTile
import com.pocketmcp.ui.components.sectionEntrance
import com.pocketmcp.ui.theme.AppTheme
import com.pocketmcp.ui.theme.BlueLight
import com.pocketmcp.ui.theme.GrayLight
import com.pocketmcp.ui.theme.GreenLight
import com.pocketmcp.ui.theme.IndigoLight
import com.pocketmcp.ui.theme.OrangeLight
import com.pocketmcp.ui.theme.RedLight

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
    val colors = AppTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.sectionEntrance(0)
        )

        GroupedSection(title = "Authentication", modifier = Modifier.sectionEntrance(1)) {
            GroupedCard {
                SettingsToggleRow(
                    title = "API Key Auth",
                    subtitle = "Require key for MCP requests",
                    icon = Icons.Rounded.VpnKey,
                    tileColor = GreenLight,
                    checked = isApiAuthEnabled,
                    onCheckedChange = onToggleApiAuth,
                    showDivider = true
                )
                SettingsToggleRow(
                    title = "Tailscale",
                    subtitle = "Enable Tailscale networking",
                    icon = Icons.Rounded.NetworkCheck,
                    tileColor = IndigoLight,
                    checked = isTailscaleEnabled,
                    onCheckedChange = onToggleTailscale,
                    showDivider = true
                )
                SettingsActionRow(
                    title = "Manage Keys",
                    subtitle = "Copy current API key",
                    icon = Icons.Rounded.Key,
                    tileColor = GrayLight,
                    onClick = onManageKeys
                )
            }
        }

        GroupedSection(title = "System Permissions", modifier = Modifier.sectionEntrance(2)) {
            GroupedCard {
                SettingsActionRow(
                    title = "Accessibility Service",
                    subtitle = accessibilityState,
                    subtitleColor = if (accessibilityState.contains("Enabled")) colors.success else colors.warning,
                    icon = Icons.Rounded.Accessibility,
                    tileColor = BlueLight,
                    onClick = onOpenAccessibilitySettings,
                    showDivider = true
                )
                SettingsToggleRow(
                    title = "Notification Access",
                    subtitle = "Read notification stream",
                    icon = Icons.Rounded.NotificationsActive,
                    tileColor = RedLight,
                    checked = isNotificationAccessEnabled,
                    onCheckedChange = { onToggleNotificationAccess() },
                    showDivider = true
                )
                SettingsToggleRow(
                    title = "File Storage",
                    subtitle = "Access media and documents",
                    icon = Icons.Rounded.FolderOpen,
                    tileColor = OrangeLight,
                    checked = isFileStorageEnabled,
                    onCheckedChange = onToggleFileStorage
                )
            }
        }

        GroupedSection(title = "Advanced", modifier = Modifier.sectionEntrance(3)) {
            GroupedCard {
                SettingsActionRow(
                    title = "Safety Filter",
                    subtitle = "Mode: $safetyFilterMode",
                    subtitleColor = when (safetyFilterMode) {
                        "Strict" -> colors.success
                        "Balanced" -> colors.warning
                        else -> MaterialTheme.colorScheme.error
                    },
                    icon = Icons.Rounded.Shield,
                    tileColor = OrangeLight,
                    onClick = onCycleSafetyFilter,
                    showDivider = true
                )
                SettingsToggleRow(
                    title = "Developer Mode",
                    subtitle = "Show debug information",
                    icon = Icons.Rounded.Code,
                    tileColor = GrayLight,
                    checked = isDeveloperModeEnabled,
                    onCheckedChange = onToggleDeveloperMode
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .sectionEntrance(4),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "PocketMCP v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryLabel
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tileColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = false
) {
    ListRow(
        leading = { RowTile(icon, tileColor) },
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = iosSwitchColors()
            )
        },
        showDivider = showDivider
    )
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tileColor: Color,
    onClick: () -> Unit,
    subtitleColor: Color? = null,
    showDivider: Boolean = false
) {
    ListRow(
        leading = { RowTile(icon, tileColor) },
        title = title,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        trailing = { RowChevron() },
        showDivider = showDivider,
        onClick = onClick
    )
}

@Composable
private fun iosSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = AppTheme.colors.success,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = AppTheme.colors.fieldFill,
    uncheckedBorderColor = Color.Transparent
)
