package com.pocketmcp.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationAdd
import androidx.compose.material.icons.rounded.NotificationImportant
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ScreenSearchDesktop
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketmcp.ui.components.AppSearchBar
import com.pocketmcp.ui.components.FilterChipRow
import com.pocketmcp.ui.components.GroupedCard
import com.pocketmcp.ui.components.ListRow
import com.pocketmcp.ui.components.RowIcon
import com.pocketmcp.ui.components.sectionEntrance
import com.pocketmcp.ui.theme.AppTheme

enum class ToolCategory { ALL, SYSTEM, COMMUNICATION, SENSORS, MEDIA, AUTOMATION }

private data class ToolInfo(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val category: ToolCategory,
    val status: ToolStatus
)

enum class ToolStatus {
    READY, PERMISSION_REQUIRED, UNAVAILABLE
}

@Composable
fun ToolsScreen(
    accessibilityEnabled: Boolean = false,
    notificationAccessEnabled: Boolean = false,
    mediaAccessEnabled: Boolean = false,
    smsPermissionGranted: Boolean = false,
    contactsPermissionGranted: Boolean = false,
    callPermissionGranted: Boolean = false,
    locationPermissionGranted: Boolean = false,
    recordAudioPermissionGranted: Boolean = false,
    cameraPermissionGranted: Boolean = false,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ToolCategory.ALL) }
    val scrollState = rememberScrollState()

    fun statusFor(requirement: Boolean) = if (requirement) ToolStatus.READY else ToolStatus.PERMISSION_REQUIRED

    val tools = remember(
        accessibilityEnabled,
        notificationAccessEnabled,
        mediaAccessEnabled,
        smsPermissionGranted,
        contactsPermissionGranted,
        callPermissionGranted,
        locationPermissionGranted,
        recordAudioPermissionGranted,
        cameraPermissionGranted
    ) {
        listOf(
            ToolInfo("Device Info", "Battery, memory, model, OS version", Icons.Rounded.PhoneAndroid, ToolCategory.SYSTEM, ToolStatus.READY),
            ToolInfo("Shell", "Execute secure shell commands", Icons.Rounded.Terminal, ToolCategory.SYSTEM, ToolStatus.READY),
            ToolInfo("Launch App", "Open any installed application", Icons.AutoMirrored.Rounded.Launch, ToolCategory.SYSTEM, ToolStatus.READY),
            ToolInfo("List Apps", "Enumerate all installed packages", Icons.Rounded.Apps, ToolCategory.SYSTEM, ToolStatus.READY),
            ToolInfo("File Read", "Read files from allowed storage", Icons.AutoMirrored.Rounded.InsertDriveFile, ToolCategory.SYSTEM, statusFor(mediaAccessEnabled)),
            ToolInfo("Send Message", "SMS text messaging", Icons.AutoMirrored.Rounded.Message, ToolCategory.COMMUNICATION, statusFor(smsPermissionGranted)),
            ToolInfo("Make Call", "Initiate phone calls", Icons.Rounded.Call, ToolCategory.COMMUNICATION, statusFor(callPermissionGranted)),
            ToolInfo("Search Contacts", "Query device contact list", Icons.Rounded.Contacts, ToolCategory.COMMUNICATION, statusFor(contactsPermissionGranted)),
            ToolInfo("WhatsApp Send", "Send WhatsApp messages", Icons.AutoMirrored.Rounded.Chat, ToolCategory.COMMUNICATION, statusFor(accessibilityEnabled)),
            ToolInfo("WhatsApp Business", "Send WhatsApp Business messages", Icons.Rounded.Business, ToolCategory.COMMUNICATION, statusFor(accessibilityEnabled)),
            ToolInfo("WhatsApp Automation", "Advanced WhatsApp automation", Icons.Rounded.SmartToy, ToolCategory.COMMUNICATION, statusFor(accessibilityEnabled)),
            ToolInfo("Social Media", "Instagram, YouTube, X/Twitter", Icons.Rounded.Share, ToolCategory.COMMUNICATION, statusFor(accessibilityEnabled)),
            ToolInfo("Location", "GPS and network location", Icons.Rounded.LocationOn, ToolCategory.SENSORS, statusFor(locationPermissionGranted)),
            ToolInfo("Flashlight", "Toggle camera LED", Icons.Rounded.FlashlightOn, ToolCategory.SENSORS, statusFor(cameraPermissionGranted)),
            ToolInfo("Volume", "Control system audio levels", Icons.AutoMirrored.Rounded.VolumeUp, ToolCategory.SENSORS, ToolStatus.READY),
            ToolInfo("Screenshot", "Capture screen content", Icons.Rounded.Screenshot, ToolCategory.SENSORS, statusFor(accessibilityEnabled)),
            ToolInfo("Phone Alert", "Ring and vibrate phone", Icons.Rounded.NotificationImportant, ToolCategory.SENSORS, ToolStatus.READY),
            ToolInfo("Voice Record", "Record audio from microphone", Icons.Rounded.Mic, ToolCategory.MEDIA, statusFor(recordAudioPermissionGranted)),
            ToolInfo("Transcribe Audio", "Speech-to-text from mic", Icons.Rounded.Translate, ToolCategory.MEDIA, statusFor(recordAudioPermissionGranted)),
            ToolInfo("Transcribe File", "Audio file transcription", Icons.Rounded.AudioFile, ToolCategory.MEDIA, statusFor(mediaAccessEnabled)),
            ToolInfo("WhatsApp Audio", "Transcribe WhatsApp voice notes", Icons.Rounded.Voicemail, ToolCategory.MEDIA, statusFor(mediaAccessEnabled)),
            ToolInfo("HTTP Request", "Proxy network requests", Icons.Rounded.Http, ToolCategory.SYSTEM, ToolStatus.READY),
            ToolInfo("Scroll Screen", "Automated UI scrolling", Icons.Rounded.Swipe, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled)),
            ToolInfo("Tap", "Programmatic screen taps", Icons.Rounded.TouchApp, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled)),
            ToolInfo("Search Screen", "Find and type in search fields", Icons.Rounded.ScreenSearchDesktop, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled)),
            ToolInfo("Screen State", "Capture accessibility tree", Icons.Rounded.CropFree, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled)),
            ToolInfo("Notifications", "Read active notifications", Icons.Rounded.Notifications, ToolCategory.SYSTEM, statusFor(notificationAccessEnabled)),
            ToolInfo("Smart Notifications", "Advanced notification management", Icons.Rounded.NotificationsActive, ToolCategory.SYSTEM, statusFor(notificationAccessEnabled)),
            ToolInfo("Notification Diagnostic", "Notification service diagnostics", Icons.Rounded.NotificationAdd, ToolCategory.SYSTEM, statusFor(notificationAccessEnabled)),
            ToolInfo("Global Action", "Home, back, recents, lock", Icons.Rounded.Gesture, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled)),
            ToolInfo("Workflow", "Multi-step automation chains", Icons.Rounded.AutoFixHigh, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled)),
            ToolInfo("App Actions", "Run preset app actions", Icons.AutoMirrored.Rounded.Rule, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled)),
            ToolInfo("Human Command", "Natural language command router", Icons.Rounded.Psychology, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled))
        )
    }

    val filteredTools = tools.filter { tool ->
        val categoryMatches = selectedCategory == ToolCategory.ALL || tool.category == selectedCategory
        val searchMatches = searchQuery.isBlank() ||
                tool.name.contains(searchQuery, ignoreCase = true) ||
                tool.description.contains(searchQuery, ignoreCase = true)
        categoryMatches && searchMatches
    }

    val permissionNeededCount = tools.count { it.status == ToolStatus.PERMISSION_REQUIRED }
    val isFiltering = searchQuery.isNotBlank() || selectedCategory != ToolCategory.ALL

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(modifier = Modifier.sectionEntrance(0)) {
            Text(
                text = "Tools",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (isFiltering) {
                    "${filteredTools.size} of ${tools.size} tools"
                } else {
                    "${tools.size} tools available"
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.secondaryLabel
            )
        }

        Column(
            modifier = Modifier.sectionEntrance(1),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppSearchBar(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search tools"
            )

            FilterChipRow(
                labels = listOf("All", "System", "Comm", "Sensors", "Media", "Auto"),
                selectedIndex = selectedCategory.ordinal,
                onSelect = { index -> selectedCategory = ToolCategory.entries[index] }
            )
        }

        if (permissionNeededCount > 0 && !isFiltering) {
            PermissionBanner(count = permissionNeededCount)
        }

        if (filteredTools.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .sectionEntrance(3)
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.SearchOff,
                        contentDescription = null,
                        tint = AppTheme.colors.tertiaryLabel,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No tools match your search",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.secondaryLabel
                    )
                }
            }
        } else {
            AnimatedContent(
                targetState = filteredTools,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(120))
                },
                label = "filteredTools"
            ) { visibleTools ->
                GroupedCard(modifier = Modifier.sectionEntrance(3)) {
                    visibleTools.forEachIndexed { index, tool ->
                        ToolRow(
                            tool = tool,
                            showDivider = index != visibleTools.lastIndex
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionBanner(count: Int) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sectionEntrance(2)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.warning.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = colors.warning,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "Permissions needed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "$count tools require setup to work",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryLabel
            )
        }
    }
}

@Composable
private fun ToolRow(
    tool: ToolInfo,
    showDivider: Boolean
) {
    val colors = AppTheme.colors
    val statusColor = when (tool.status) {
        ToolStatus.READY -> colors.success
        ToolStatus.PERMISSION_REQUIRED -> colors.warning
        ToolStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
    }
    val statusLabel = when (tool.status) {
        ToolStatus.READY -> null
        ToolStatus.PERMISSION_REQUIRED -> "Setup"
        ToolStatus.UNAVAILABLE -> "Unavailable"
    }

    ListRow(
        leading = { RowIcon(tool.icon) },
        title = tool.name,
        subtitle = tool.description,
        trailing = if (statusLabel != null) {
            {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    maxLines = 1
                )
            }
        } else {
            null
        },
        showDivider = showDivider
    )
}
