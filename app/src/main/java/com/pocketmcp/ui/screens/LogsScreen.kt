package com.pocketmcp.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketmcp.MainActivity
import com.pocketmcp.ui.components.CodeBlock
import com.pocketmcp.ui.components.GroupedCard
import com.pocketmcp.ui.components.GroupedSection
import com.pocketmcp.ui.components.HairlineDivider
import com.pocketmcp.ui.components.ListRow
import com.pocketmcp.ui.components.RowChevron
import com.pocketmcp.ui.components.SegmentedControl
import com.pocketmcp.ui.components.sectionEntrance
import com.pocketmcp.ui.theme.AppTheme

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
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sectionEntrance(0),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Logs",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${logEvents.size} events",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.secondaryLabel
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClearLogs() }
                    .defaultMinSize(minHeight = 44.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Clear",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        GroupedSection(title = "Runtime", modifier = Modifier.sectionEntrance(1)) {
            GroupedCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    CodeBlock(
                        text = logEvents.takeLast(8).joinToString("\n") {
                            "[${it.timestamp}] ${it.message}"
                        }.ifBlank { "No events yet..." }
                    )
                }
            }
        }

        SegmentedControl(
            labels = listOf("All", "Errors", "Tools", "Requests"),
            selectedIndex = selectedFilter.ordinal,
            onSelect = { index -> selectedFilter = LogFilter.entries[index] },
            modifier = Modifier.sectionEntrance(2)
        )

        GroupedSection(title = "Events", modifier = Modifier.sectionEntrance(3)) {
            AnimatedContent(
                targetState = filteredEvents,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(120))
                },
                label = "filteredEvents"
            ) { events ->
                GroupedCard {
                    if (events.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No events match this filter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.colors.secondaryLabel
                            )
                        }
                    } else {
                        val shown = events.takeLast(50).reversed()
                        shown.forEachIndexed { index, event ->
                            LogEventRow(
                                event = event,
                                showDivider = index != shown.lastIndex
                            )
                        }
                    }
                }
            }
        }

        GroupedCard(modifier = Modifier.sectionEntrance(4)) {
            ListRow(
                title = "View All Notification History",
                trailing = { RowChevron() },
                onClick = onViewAllNotifications
            )
        }
    }
}

@Composable
private fun LogEventRow(
    event: MainActivity.LogEvent,
    showDivider: Boolean
) {
    val colors = AppTheme.colors
    val levelColor = when (event.level) {
        MainActivity.LogLevel.ERROR -> MaterialTheme.colorScheme.error
        MainActivity.LogLevel.TOOL -> colors.success
        MainActivity.LogLevel.REQUEST -> MaterialTheme.colorScheme.primary
        MainActivity.LogLevel.INFO -> colors.secondaryLabel
    }
    val levelLabel = when (event.level) {
        MainActivity.LogLevel.ERROR -> "ERROR"
        MainActivity.LogLevel.TOOL -> "TOOL"
        MainActivity.LogLevel.REQUEST -> "REQUEST"
        MainActivity.LogLevel.INFO -> "INFO"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = levelLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = levelColor
            )
            Text(
                text = event.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiaryLabel
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = event.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
    if (showDivider) HairlineDivider()
}
