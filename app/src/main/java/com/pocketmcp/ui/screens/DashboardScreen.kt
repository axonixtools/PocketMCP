package com.pocketmcp.ui.screens

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.pocketmcp.ui.components.AppButton
import com.pocketmcp.ui.components.AppButtonStyle
import com.pocketmcp.ui.components.CodeBlock
import com.pocketmcp.ui.components.GroupedCard
import com.pocketmcp.ui.components.GroupedSection
import com.pocketmcp.ui.components.HairlineDivider
import com.pocketmcp.ui.components.ListRow
import com.pocketmcp.ui.components.RowIcon
import com.pocketmcp.ui.components.sectionEntrance
import com.pocketmcp.ui.theme.AppTheme

@Composable
fun DashboardScreen(
    isRunning: Boolean,
    statusText: String,
    statusDetail: String,
    hasError: Boolean,
    isSecured: Boolean,
    ipAddress: String,
    endpoint: String,
    port: String,
    apiKey: String,
    mcpConfigPreview: String,
    uptimeMs: Long,
    requestCount: Int,
    toolCallCount: Int,
    errorCount: Int,
    connectionCount: Int,
    onToggleServer: () -> Unit,
    onPortChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onGenerateKey: () -> Unit,
    onCopyEndpoint: () -> Unit,
    onCopyConfig: () -> Unit,
    onCopyIp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

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
                text = "PocketMCP",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Mobile MCP Server",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.secondaryLabel
            )
        }

        StatusSection(
            isRunning = isRunning,
            statusText = statusText,
            statusDetail = statusDetail,
            hasError = hasError,
            onToggleServer = onToggleServer
        )

        AnimatedVisibility(
            visible = isRunning,
            enter = expandVertically(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                    fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                    fadeOut(animationSpec = tween(120))
        ) {
            StatsSection(
                uptimeMs = uptimeMs,
                requestCount = requestCount,
                toolCallCount = toolCallCount,
                errorCount = errorCount,
                connectionCount = connectionCount
            )
        }

        ConnectionSection(
            ipAddress = ipAddress,
            endpoint = endpoint,
            isSecured = isSecured,
            onCopyEndpoint = onCopyEndpoint,
            onCopyIp = onCopyIp,
            networkType = getNetworkType(context)
        )

        ConfigurationSection(
            port = port,
            apiKey = apiKey,
            onPortChange = onPortChange,
            onApiKeyChange = onApiKeyChange,
            onGenerateKey = onGenerateKey
        )

        QuickConnectSection(
            mcpConfig = mcpConfigPreview,
            onCopyConfig = onCopyConfig
        )

        QrCodeSection(endpoint = endpoint)
    }
}

@Composable
private fun StatusSection(
    isRunning: Boolean,
    statusText: String,
    statusDetail: String,
    hasError: Boolean,
    onToggleServer: () -> Unit
) {
    val colors = AppTheme.colors
    val statusColor by animateColorAsState(
        targetValue = when {
            hasError -> MaterialTheme.colorScheme.error
            isRunning -> colors.success
            else -> colors.secondaryLabel
        },
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "statusColor"
    )

    val pulse = rememberInfiniteTransition(label = "statusPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    GroupedCard(modifier = Modifier.sectionEntrance(1)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    if (isRunning && !hasError) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                    alpha = pulseAlpha
                                }
                                .background(statusColor, CircleShape)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, CircleShape)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = statusDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasError) MaterialTheme.colorScheme.error else colors.secondaryLabel
                    )
                }
            }

            AppButton(
                text = if (isRunning) "Stop Server" else "Start Server",
                style = if (isRunning) AppButtonStyle.Destructive else AppButtonStyle.Filled,
                onClick = onToggleServer
            )
        }
    }
}

@Composable
private fun StatsSection(
    uptimeMs: Long,
    requestCount: Int,
    toolCallCount: Int,
    errorCount: Int,
    connectionCount: Int
) {
    GroupedSection(title = "Server Stats", modifier = Modifier.sectionEntrance(2)) {
        GroupedCard {
            ListRow(
                title = "Uptime",
                trailing = {
                    Text(
                        text = formatUptime(uptimeMs),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        color = AppTheme.colors.secondaryLabel
                    )
                },
                showDivider = true
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "Requests", value = requestCount)
                StatItem(label = "Tool Calls", value = toolCallCount)
                StatItem(label = "Errors", value = errorCount)
                StatItem(label = "Clients", value = connectionCount)
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: Int) {
    val animatedValue by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "statValue"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = animatedValue.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.secondaryLabel
        )
    }
}

private fun formatUptime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun ConnectionSection(
    ipAddress: String,
    endpoint: String,
    isSecured: Boolean,
    onCopyEndpoint: () -> Unit,
    onCopyIp: () -> Unit,
    networkType: String
) {
    val colors = AppTheme.colors
    val networkIcon = when (networkType) {
        "WiFi" -> Icons.Rounded.Wifi
        "Cellular" -> Icons.Rounded.SignalCellularAlt
        else -> Icons.Rounded.NetworkCheck
    }

    GroupedSection(title = "Connection", modifier = Modifier.sectionEntrance(3)) {
        GroupedCard {
            ListRow(
                leading = { RowIcon(networkIcon) },
                title = "Network",
                trailing = {
                    Text(
                        text = networkType,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.secondaryLabel
                    )
                },
                showDivider = true
            )
            ListRow(
                leading = { RowIcon(Icons.Rounded.Router) },
                title = "Local IP",
                trailing = {
                    Text(
                        text = ipAddress,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                showDivider = true,
                onClick = onCopyIp
            )
            ListRow(
                leading = { RowIcon(Icons.Rounded.Link) },
                title = "Endpoint",
                trailing = {
                    Text(
                        text = endpoint,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                showDivider = true,
                onClick = onCopyEndpoint
            )
            ListRow(
                leading = { RowIcon(Icons.Rounded.Lock) },
                title = "Authentication",
                trailing = {
                    Text(
                        text = if (isSecured) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSecured) colors.success else colors.warning
                    )
                }
            )
        }
    }
}

@Composable
private fun ConfigurationSection(
    port: String,
    apiKey: String,
    onPortChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onGenerateKey: () -> Unit
) {
    GroupedSection(title = "Configuration", modifier = Modifier.sectionEntrance(4)) {
        GroupedCard {
            ConfigTextFieldRow(
                label = "Port",
                value = port,
                onValueChange = { onPortChange(it.filter { c -> c.isDigit() }) },
                keyboardType = KeyboardType.Number,
                fieldMaxWidth = 100.dp
            )
            HairlineDivider()
            ConfigTextFieldRow(
                label = "API Key",
                value = apiKey,
                onValueChange = onApiKeyChange,
                keyboardType = KeyboardType.Text,
                monospace = true,
                fieldMaxWidth = 200.dp
            )
            HairlineDivider()
            ActionRow(text = "Generate New Key", onClick = onGenerateKey)
        }
    }
}

@Composable
private fun ConfigTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    monospace: Boolean = false,
    fieldMaxWidth: Dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.weight(1f))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                textAlign = TextAlign.End,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onBackground
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.widthIn(max = fieldMaxWidth)
        )
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressBackground by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f) else Color.Transparent,
        animationSpec = tween(120),
        label = "actionPress"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(pressBackground)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun QuickConnectSection(
    mcpConfig: String,
    onCopyConfig: () -> Unit
) {
    GroupedSection(title = "Quick Connect", modifier = Modifier.sectionEntrance(5)) {
        GroupedCard {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                CodeBlock(text = mcpConfig)
            }
            HairlineDivider()
            ListRow(
                leading = { RowIcon(Icons.Rounded.ContentCopy) },
                title = "Copy MCP client config",
                onClick = onCopyConfig
            )
        }
    }
}

@Composable
private fun QrCodeSection(endpoint: String) {
    if (endpoint.isBlank() || endpoint.contains("unknown")) return
    if (LocalInspectionMode.current) return

    val qrBitmap = remember(endpoint) {
        generateQrBitmap(endpoint, 400)
    }

    GroupedSection(title = "Scan to Connect", modifier = Modifier.sectionEntrance(6)) {
        GroupedCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (qrBitmap != null) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.ImageView(ctx).apply {
                                setImageBitmap(qrBitmap)
                            }
                        },
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .padding(8.dp)
                    )
                    Text(
                        text = "Scan with any MCP client to connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.secondaryLabel
                    )
                } else {
                    Text(
                        text = "Unable to generate QR code",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}

private fun getNetworkType(context: android.content.Context): String {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = cm.activeNetwork ?: return "Unknown"
        val caps = cm.getNetworkCapabilities(network) ?: return "Unknown"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Unknown"
        }
    } else {
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        when (info?.type) {
            ConnectivityManager.TYPE_WIFI -> "WiFi"
            ConnectivityManager.TYPE_MOBILE -> "Cellular"
            ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
            else -> "Unknown"
        }
    }
}
