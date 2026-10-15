package com.pocketmcp.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketmcp.ui.components.*
import com.pocketmcp.ui.theme.*
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

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
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        DashboardHeader(isRunning = isRunning)

        StatusSection(
            isRunning = isRunning,
            statusText = statusText,
            statusDetail = statusDetail,
            hasError = hasError,
            onToggleServer = onToggleServer
        )

        if (isRunning) {
            StatsCard(
                uptimeMs = uptimeMs,
                requestCount = requestCount,
                toolCallCount = toolCallCount,
                errorCount = errorCount,
                connectionCount = connectionCount
            )
        }

        ConnectionCard(
            ipAddress = ipAddress,
            endpoint = endpoint,
            isSecured = isSecured,
            onCopyEndpoint = onCopyEndpoint,
            onCopyIp = onCopyIp,
            networkType = getNetworkType(context)
        )

        ConfigCard(
            port = port,
            apiKey = apiKey,
            onPortChange = onPortChange,
            onApiKeyChange = onApiKeyChange,
            onGenerateKey = onGenerateKey
        )

        QuickConnectCard(
            mcpConfig = mcpConfigPreview,
            onCopyConfig = onCopyConfig
        )

        QrCodeCard(endpoint = endpoint)
    }
}

@Composable
private fun DashboardHeader(isRunning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "PocketMCP",
                style = MaterialTheme.typography.headlineMedium,
                color = NeuralOnBackground
            )
            Text(
                text = "Mobile MCP Server",
                style = MaterialTheme.typography.bodyMedium,
                color = NeuralOnSurfaceVariant
            )
        }

        val chipColor = if (isRunning) NeuralSuccess else NeuralError
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = chipColor.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, chipColor.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(chipColor, CircleShape)
                )
                Text(
                    text = if (isRunning) "ONLINE" else "OFFLINE",
                    style = MaterialTheme.typography.labelMedium,
                    color = chipColor
                )
            }
        }
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
    val targetColor = when {
        hasError -> NeuralError
        isRunning -> NeuralSuccess
        else -> NeuralOnSurfaceMuted
    }

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "statusColor"
    )

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        active = isRunning,
        glowColor = if (isRunning) NeuralSuccess else NeuralPrimary
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatusOrb(
                isRunning = isRunning,
                modifier = Modifier.padding(vertical = 8.dp),
                size = 100.dp
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = animatedColor
                )
                Text(
                    text = statusDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasError) NeuralError else NeuralOnSurfaceVariant
                )
            }

            val buttonColor = if (isRunning) NeuralError else NeuralPrimary
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onToggleServer() },
                color = buttonColor.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, buttonColor.copy(alpha = 0.4f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isRunning) "Stop Server" else "Start Server",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = buttonColor
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    uptimeMs: Long,
    requestCount: Int,
    toolCallCount: Int,
    errorCount: Int,
    connectionCount: Int
) {
    val uptimeText = formatUptime(uptimeMs)

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        active = true,
        glowColor = NeuralSecondary
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader(title = "Server Stats", accentColor = NeuralSecondary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "Uptime", value = uptimeText, color = NeuralPrimary)
                StatItem(label = "Requests", value = requestCount.toString(), color = NeuralSecondary)
                StatItem(label = "Tools", value = toolCallCount.toString(), color = NeuralSuccess)
                StatItem(label = "Errors", value = errorCount.toString(), color = NeuralError)
                StatItem(label = "Clients", value = connectionCount.toString(), color = NeuralTertiary)
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NeuralOnSurfaceMuted
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
private fun ConnectionCard(
    ipAddress: String,
    endpoint: String,
    isSecured: Boolean,
    onCopyEndpoint: () -> Unit,
    onCopyIp: () -> Unit,
    networkType: String
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader(title = "Connection", accentColor = NeuralSecondary)

            val networkIcon = when (networkType) {
                "WiFi" -> Icons.Rounded.Wifi
                "Cellular" -> Icons.Rounded.SignalCellularAlt
                else -> Icons.Rounded.NetworkCheck
            }
            val networkColor = when (networkType) {
                "WiFi" -> NeuralSuccess
                "Cellular" -> NeuralWarning
                else -> NeuralOnSurfaceMuted
            }

            CompactGlassCard {
                Icon(
                    imageVector = networkIcon,
                    contentDescription = null,
                    tint = networkColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Network",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeuralOnSurfaceMuted
                    )
                    Text(
                        text = networkType,
                        style = MaterialTheme.typography.bodyMedium,
                        color = networkColor
                    )
                }
            }

            CompactGlassCard(onClick = onCopyIp) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = NeuralSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Local IP",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeuralOnSurfaceMuted
                    )
                    Text(
                        text = ipAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeuralOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            CompactGlassCard(onClick = onCopyEndpoint) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = NeuralPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Endpoint",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeuralOnSurfaceMuted
                    )
                    Text(
                        text = endpoint,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = NeuralPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy",
                    tint = NeuralOnSurfaceMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            val authColor = if (isSecured) NeuralSuccess else NeuralWarning
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(authColor, CircleShape)
                )
                Text(
                    text = if (isSecured) "Authentication Enabled" else "Authentication Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = authColor
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(
    port: String,
    apiKey: String,
    onPortChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onGenerateKey: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader(title = "Configuration", accentColor = NeuralTertiary)

            OutlinedTextField(
                value = port,
                onValueChange = { onPortChange(it.filter { c -> c.isDigit() }) },
                label = { Text("Port") },
                leadingIcon = {
                    Icon(Icons.Rounded.SettingsEthernet, null, tint = NeuralOnSurfaceMuted)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
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

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key") },
                leadingIcon = {
                    Icon(Icons.Rounded.VpnKey, null, tint = NeuralOnSurfaceMuted)
                },
                trailingIcon = {
                    TextButton(
                        onClick = onGenerateKey,
                        colors = ButtonDefaults.textButtonColors(contentColor = NeuralPrimary)
                    ) {
                        Text("Generate", style = MaterialTheme.typography.labelMedium)
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
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
        }
    }
}

@Composable
private fun QuickConnectCard(
    mcpConfig: String,
    onCopyConfig: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(title = "Quick Connect", accentColor = NeuralPrimary)
                IconButton(onClick = onCopyConfig) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy Config",
                        tint = NeuralOnSurfaceMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NeuralBackground.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, NeuralOutline)
            ) {
                SelectionContainer {
                    Text(
                        text = mcpConfig,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        color = NeuralOnSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCodeCard(endpoint: String) {
    if (endpoint.isBlank() || endpoint.contains("unknown")) return
    if (LocalInspectionMode.current) return

    val qrBitmap = remember(endpoint) {
        generateQrBitmap(endpoint, 400)
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SectionHeader(title = "Scan to Connect", accentColor = NeuralPrimary)

            if (qrBitmap != null) {
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            setImageBitmap(qrBitmap)
                        }
                    },
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(8.dp)
                )
            } else {
                Text(
                    text = "Unable to generate QR code",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuralError
                )
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
