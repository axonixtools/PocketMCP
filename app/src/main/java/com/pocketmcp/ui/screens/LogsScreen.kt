package com.pocketmcp.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketmcp.MainActivity
import com.pocketmcp.ui.components.*
import com.pocketmcp.ui.theme.*

enum class LogFilter { ALL, ERRORS, TOOLS, REQUESTS }

@Composable
fun LogsScreen(
    logEvents: List<MainActivity.LogEvent>,
    onClearLogs: () -> Unit,
    onViewAllNotifications: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(LogFilter.ALL) }
    val scrollState = rememberScrollState()

    val filteredEvents = remember(logEvents, selectedFilter) {
        when (selectedFilter) {
            LogFilter.ALL -> logEvents
            LogFilter.ERRORS -> logEvents.filter { it.level == MainActivity.LogLevel.ERROR }
            LogFilter.TOOLS -> logEvents.filter {
                it.level == MainActivity.LogLevel.TOOL || it.message.contains("tool", ignoreCase = true)
            }
            LogFilter.REQUESTS -> logEvents.filter {
                it.level == MainActivity.LogLevel.REQUEST || it.message.contains("request", ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Server Logs",
                    style = MaterialTheme.typography.headlineMedium,
                    color = NeuralOnBackground
                )
                Text(
                    text = "${logEvents.size} events",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeuralOnSurfaceVariant
                )
            }
            IconButton(
                onClick = onClearLogs,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(NeuralSurfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = "Clear logs",
                    tint = NeuralError
                )
            }
        }

        // Live terminal
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            active = true,
            glowColor = NeuralSecondary
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(NeuralSuccess, CircleShape)
                    )
                    Text(
                        text = "Runtime Log",
                        style = MaterialTheme.typography.labelLarge,
                        color = NeuralSecondary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = NeuralBackground.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, NeuralOutline)
                ) {
                    Text(
                        text = logEvents.takeLast(8).joinToString("\n") {
                            "[${it.timestamp}] ${it.message}"
                        }.ifBlank { "No events yet..." },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 20.sp
                        ),
                        color = NeuralOnSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }
        }

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LogFilterChip(
                label = "All",
                isSelected = selectedFilter == LogFilter.ALL,
                color = NeuralPrimary,
                onClick = { selectedFilter = LogFilter.ALL }
            )
            LogFilterChip(
                label = "Errors",
                isSelected = selectedFilter == LogFilter.ERRORS,
                color = NeuralError,
                onClick = { selectedFilter = LogFilter.ERRORS }
            )
            LogFilterChip(
                label = "Tools",
                isSelected = selectedFilter == LogFilter.TOOLS,
                color = NeuralSuccess,
                onClick = { selectedFilter = LogFilter.TOOLS }
            )
            LogFilterChip(
                label = "Requests",
                isSelected = selectedFilter == LogFilter.REQUESTS,
                color = NeuralSecondary,
                onClick = { selectedFilter = LogFilter.REQUESTS }
            )
        }

        // Event cards from real data
        AnimatedContent(
            targetState = filteredEvents,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            label = "events"
        ) { list ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No events match this filter",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NeuralOnSurfaceMuted
                        )
                    }
                } else {
                    list.takeLast(50).forEach { event ->
                        LogEventCard(event = event)
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onViewAllNotifications() },
            color = NeuralSurfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, NeuralOutline)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "View All Notification History",
                    style = MaterialTheme.typography.labelLarge,
                    color = NeuralOnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LogFilterChip(
    label: String,
    isSelected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) color.copy(alpha = 0.15f) else NeuralSurfaceVariant.copy(alpha = 0.4f),
        animationSpec = tween(200),
        label = "chipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) color.copy(alpha = 0.5f) else NeuralOutline,
        animationSpec = tween(200),
        label = "chipBorder"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
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
private fun LogEventCard(event: MainActivity.LogEvent) {
    val (accentColor, label) = when (event.level) {
        MainActivity.LogLevel.ERROR -> NeuralError to "ERROR"
        MainActivity.LogLevel.TOOL -> NeuralSuccess to "TOOL"
        MainActivity.LogLevel.REQUEST -> NeuralSecondary to "REQ"
        MainActivity.LogLevel.INFO -> NeuralPrimary to "INFO"
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        active = false,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(accentColor, RoundedCornerShape(2.dp))
                    .align(Alignment.CenterVertically)
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = accentColor
                    )
                    Text(
                        text = event.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = NeuralOnSurfaceMuted
                    )
                }
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuralOnSurfaceVariant
                )
            }
        }
    }
}
