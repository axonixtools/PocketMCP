package com.pocketmcp.ui.screens

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketmcp.ui.components.*
import com.pocketmcp.ui.theme.*

enum class ToolCategory { ALL, SYSTEM, COMMUNICATION, SENSORS, MEDIA, AUTOMATION }

private data class ToolInfo(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val category: ToolCategory,
    val status: ToolStatus,
    val accentColor: Color
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
            ToolInfo("Device Info", "Battery, memory, model, OS version", Icons.Rounded.PhoneAndroid, ToolCategory.SYSTEM, ToolStatus.READY, NeuralPrimary),
            ToolInfo("Shell", "Execute secure shell commands", Icons.Rounded.Terminal, ToolCategory.SYSTEM, ToolStatus.READY, NeuralPrimary),
            ToolInfo("Launch App", "Open any installed application", Icons.Rounded.Launch, ToolCategory.SYSTEM, ToolStatus.READY, NeuralPrimary),
            ToolInfo("List Apps", "Enumerate all installed packages", Icons.Rounded.Apps, ToolCategory.SYSTEM, ToolStatus.READY, NeuralPrimary),
            ToolInfo("File Read", "Read files from allowed storage", Icons.Rounded.InsertDriveFile, ToolCategory.SYSTEM, statusFor(mediaAccessEnabled), NeuralPrimary),
            ToolInfo("Send Message", "SMS text messaging", Icons.Rounded.Message, ToolCategory.COMMUNICATION, statusFor(smsPermissionGranted), NeuralSecondary),
            ToolInfo("Make Call", "Initiate phone calls", Icons.Rounded.Call, ToolCategory.COMMUNICATION, statusFor(callPermissionGranted), NeuralSecondary),
            ToolInfo("Search Contacts", "Query device contact list", Icons.Rounded.Contacts, ToolCategory.COMMUNICATION, statusFor(contactsPermissionGranted), NeuralSecondary),
            ToolInfo("WhatsApp Send", "Send WhatsApp messages", Icons.Rounded.Chat, ToolCategory.COMMUNICATION, statusFor(accessibilityEnabled), NeuralSecondary),
            ToolInfo("WhatsApp Business", "Send WhatsApp Business messages", Icons.Rounded.Business, ToolCategory.COMMUNICATION, statusFor(accessibilityEnabled), NeuralSecondary),
            ToolInfo("WhatsApp Automation", "Advanced WhatsApp automation", Icons.Rounded.SmartToy, ToolCategory.COMMUNICATION, statusFor(accessibilityEnabled), NeuralSecondary),
            ToolInfo("Social Media", "Instagram, YouTube, X/Twitter", Icons.Rounded.Share, ToolCategory.COMMUNICATION, statusFor(accessibilityEnabled), NeuralSecondary),
            ToolInfo("Location", "GPS and network location", Icons.Rounded.LocationOn, ToolCategory.SENSORS, statusFor(locationPermissionGranted), NeuralTertiary),
            ToolInfo("Flashlight", "Toggle camera LED", Icons.Rounded.FlashlightOn, ToolCategory.SENSORS, statusFor(cameraPermissionGranted), NeuralTertiary),
            ToolInfo("Volume", "Control system audio levels", Icons.Rounded.VolumeUp, ToolCategory.SENSORS, ToolStatus.READY, NeuralTertiary),
            ToolInfo("Screenshot", "Capture screen content", Icons.Rounded.Screenshot, ToolCategory.SENSORS, statusFor(accessibilityEnabled), NeuralTertiary),
            ToolInfo("Phone Alert", "Ring and vibrate phone", Icons.Rounded.NotificationImportant, ToolCategory.SENSORS, ToolStatus.READY, NeuralTertiary),
            ToolInfo("Voice Record", "Record audio from microphone", Icons.Rounded.Mic, ToolCategory.MEDIA, statusFor(recordAudioPermissionGranted), NeuralGlowGreen),
            ToolInfo("Transcribe Audio", "Speech-to-text from mic", Icons.Rounded.Translate, ToolCategory.MEDIA, statusFor(recordAudioPermissionGranted), NeuralGlowGreen),
            ToolInfo("Transcribe File", "Audio file transcription", Icons.Rounded.AudioFile, ToolCategory.MEDIA, statusFor(mediaAccessEnabled), NeuralGlowGreen),
            ToolInfo("WhatsApp Audio", "Transcribe WhatsApp voice notes", Icons.Rounded.Voicemail, ToolCategory.MEDIA, statusFor(mediaAccessEnabled), NeuralGlowGreen),
            ToolInfo("HTTP Request", "Proxy network requests", Icons.Rounded.Http, ToolCategory.SYSTEM, ToolStatus.READY, NeuralPrimary),
            ToolInfo("Scroll Screen", "Automated UI scrolling", Icons.Rounded.Swipe, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled), NeuralWarning),
            ToolInfo("Tap", "Programmatic screen taps", Icons.Rounded.TouchApp, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled), NeuralWarning),
            ToolInfo("Search Screen", "Find and type in search fields", Icons.Rounded.ScreenSearchDesktop, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled), NeuralWarning),
            ToolInfo("Screen State", "Capture accessibility tree", Icons.Rounded.CropFree, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled), NeuralWarning),
            ToolInfo("Notifications", "Read active notifications", Icons.Rounded.Notifications, ToolCategory.SYSTEM, statusFor(notificationAccessEnabled), NeuralPrimary),
            ToolInfo("Smart Notifications", "Advanced notification management", Icons.Rounded.NotificationsActive, ToolCategory.SYSTEM, statusFor(notificationAccessEnabled), NeuralPrimary),
            ToolInfo("Notification Diagnostic", "Notification service diagnostics", Icons.Rounded.NotificationAdd, ToolCategory.SYSTEM, statusFor(notificationAccessEnabled), NeuralPrimary),
            ToolInfo("Global Action", "Home, back, recents, lock", Icons.Rounded.Gesture, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled), NeuralWarning),
            ToolInfo("Workflow", "Multi-step automation chains", Icons.Rounded.AutoFixHigh, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled), NeuralWarning),
            ToolInfo("App Actions", "Run preset app actions", Icons.Rounded.Rule, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled), NeuralWarning),
            ToolInfo("Human Command", "Natural language command router", Icons.Rounded.Psychology, ToolCategory.AUTOMATION, statusFor(accessibilityEnabled), NeuralWarning)
        )
    }

    val filteredTools = tools.filter { tool ->
        val categoryMatches = selectedCategory == ToolCategory.ALL || tool.category == selectedCategory
        val searchMatches = searchQuery.isBlank() ||
                tool.name.contains(searchQuery, ignoreCase = true) ||
                tool.description.contains(searchQuery, ignoreCase = true)
        categoryMatches && searchMatches
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Column {
            Text(
                text = "Tool Library",
                style = MaterialTheme.typography.headlineMedium,
                color = NeuralOnBackground
            )
            Text(
                text = "${tools.size} tools available",
                style = MaterialTheme.typography.bodyMedium,
                color = NeuralOnSurfaceVariant
            )
        }

        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search tools...") },
            leadingIcon = {
                Icon(Icons.Rounded.Search, null, tint = NeuralOnSurfaceMuted)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Rounded.Close, null, tint = NeuralOnSurfaceMuted)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeuralPrimary.copy(alpha = 0.6f),
                unfocusedBorderColor = NeuralOutline,
                focusedLabelColor = NeuralPrimary,
                unfocusedLabelColor = NeuralOnSurfaceMuted,
                focusedTextColor = NeuralOnSurface,
                unfocusedTextColor = NeuralOnSurface,
                focusedContainerColor = NeuralSurfaceHighlight.copy(alpha = 0.5f),
                unfocusedContainerColor = NeuralSurfaceHighlight.copy(alpha = 0.3f)
            )
        )

        // Category chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToolCategoryChip(
                label = "All",
                isSelected = selectedCategory == ToolCategory.ALL,
                color = NeuralPrimary,
                onClick = { selectedCategory = ToolCategory.ALL }
            )
            ToolCategoryChip(
                label = "System",
                isSelected = selectedCategory == ToolCategory.SYSTEM,
                color = NeuralPrimary,
                onClick = { selectedCategory = ToolCategory.SYSTEM }
            )
            ToolCategoryChip(
                label = "Comm",
                isSelected = selectedCategory == ToolCategory.COMMUNICATION,
                color = NeuralSecondary,
                onClick = { selectedCategory = ToolCategory.COMMUNICATION }
            )
            ToolCategoryChip(
                label = "Sensors",
                isSelected = selectedCategory == ToolCategory.SENSORS,
                color = NeuralTertiary,
                onClick = { selectedCategory = ToolCategory.SENSORS }
            )
            ToolCategoryChip(
                label = "Media",
                isSelected = selectedCategory == ToolCategory.MEDIA,
                color = NeuralGlowGreen,
                onClick = { selectedCategory = ToolCategory.MEDIA }
            )
        }

        // Tools grid
        AnimatedContent(
            targetState = filteredTools,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            label = "tools"
        ) { list ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        modifier = Modifier
                    )
                }
            }
        }

        if (filteredTools.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.SearchOff,
                        contentDescription = null,
                        tint = NeuralOnSurfaceMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No tools match your search",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeuralOnSurfaceMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCategoryChip(
    label: String,
    isSelected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) color.copy(alpha = 0.15f) else NeuralSurfaceVariant.copy(alpha = 0.4f),
        animationSpec = tween(200),
        label = "toolChipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) color.copy(alpha = 0.5f) else NeuralOutline,
        animationSpec = tween(200),
        label = "toolChipBorder"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) color else NeuralOnSurfaceMuted,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ToolCard(tool: ToolInfo, modifier: Modifier = Modifier) {
    val statusColor = when (tool.status) {
        ToolStatus.READY -> NeuralSuccess
        ToolStatus.PERMISSION_REQUIRED -> NeuralWarning
        ToolStatus.UNAVAILABLE -> NeuralError
    }

    val statusLabel = when (tool.status) {
        ToolStatus.READY -> "READY"
        ToolStatus.PERMISSION_REQUIRED -> "PERMISSION REQUIRED"
        ToolStatus.UNAVAILABLE -> "UNAVAILABLE"
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        active = tool.status == ToolStatus.READY,
        glowColor = tool.accentColor,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon container
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(tool.accentColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = null,
                    tint = tool.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NeuralOnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuralOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Status badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = statusColor.copy(alpha = 0.12f),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}
