package com.pocketmcp

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.security.SecurePrefs
import com.pocketmcp.service.McpServerService
import com.pocketmcp.tools.NotificationListenerService
import com.pocketmcp.ui.components.AppTabBar
import com.pocketmcp.ui.components.NavPage
import com.pocketmcp.ui.screens.*
import com.pocketmcp.ui.theme.PocketMCPTheme
import com.pocketmcp.util.ApiKeyGenerator
import com.pocketmcp.util.McpConfigBuilder
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.time.LocalTime
import java.time.format.DateTimeFormatter

public class MainActivity : ComponentActivity() {

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    public data class LogEvent(
        public val timestamp: String,
        public val message: String,
        public val level: LogLevel
    )
    public enum class LogLevel { INFO, TOOL, REQUEST, ERROR }

    private val logEvents = mutableStateListOf<LogEvent>()

    // State holders for Compose
    private var isServerRunning by mutableStateOf(false)
    private var serverStatusText by mutableStateOf("")
    private var serverStatusDetail by mutableStateOf("")
    private var serverHasError by mutableStateOf(false)
    private var isSecured by mutableStateOf(false)
    private var ipAddress by mutableStateOf("")
    private var endpoint by mutableStateOf("")
    private var portText by mutableStateOf("8080")
    private var apiKeyText by mutableStateOf("")
    private var mcpConfigText by mutableStateOf("")

    // Server stats
    private var uptimeMs by mutableStateOf(0L)
    private var requestCount by mutableStateOf(0)
    private var toolCallCount by mutableStateOf(0)
    private var errorCount by mutableStateOf(0)
    private var connectionCount by mutableStateOf(0)

    private var accessibilityEnabled by mutableStateOf(false)
    private var notificationAccessEnabled by mutableStateOf(false)
    private var mediaAccessEnabled by mutableStateOf(false)
    private var smsPermissionGranted by mutableStateOf(false)
    private var contactsPermissionGranted by mutableStateOf(false)
    private var callPermissionGranted by mutableStateOf(false)
    private var locationPermissionGranted by mutableStateOf(false)
    private var recordAudioPermissionGranted by mutableStateOf(false)
    private var cameraPermissionGranted by mutableStateOf(false)
    private var tailscaleEnabled by mutableStateOf(false)
    private var developerModeEnabled by mutableStateOf(false)
    private var safetyFilterMode by mutableStateOf(SAFETY_STRICT)

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != McpServerService.ACTION_STATUS) return

            val running = intent.getBooleanExtra(McpServerService.EXTRA_RUNNING, false)
            val status = intent.getStringExtra(McpServerService.EXTRA_STATUS)
            val error = intent.getStringExtra(McpServerService.EXTRA_ERROR)
            val secured = intent.getBooleanExtra(
                McpServerService.EXTRA_SECURED,
                !apiKeyText.isNullOrBlank()
            )

            isServerRunning = running
            serverStatusText = resolveStatusText(running, status)
            serverStatusDetail = when {
                !error.isNullOrBlank() -> "Error: $error"
                running -> "Listening for MCP connections"
                else -> "Server idle"
            }
            serverHasError = !error.isNullOrBlank()
            isSecured = secured

            uptimeMs = intent.getLongExtra(McpServerService.EXTRA_UPTIME_MS, 0L)
            requestCount = intent.getIntExtra(McpServerService.EXTRA_REQUEST_COUNT, 0)
            toolCallCount = intent.getIntExtra(McpServerService.EXTRA_TOOL_CALL_COUNT, 0)
            errorCount = intent.getIntExtra(McpServerService.EXTRA_ERROR_COUNT, 0)
            connectionCount = intent.getIntExtra(McpServerService.EXTRA_CONNECTION_COUNT, 0)

            addRuntimeLog("Status update: ${resolveStatusText(running, status)}", LogLevel.INFO)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissionStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        restoreSavedSettings()
        restoreUiPreferences()
        refreshPermissionStates()
        renderConnectionInfo()

        setContent {
            PocketMCPTheme {
                MainScreen()
            }
        }

        addRuntimeLog("Dashboard loaded", LogLevel.INFO)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(McpServerService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        queryServiceStatus()
        refreshPermissionStates()
        renderConnectionInfo()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onStop()
    }

    // --- Compose UI ---

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        var selectedPage by remember { mutableStateOf(NavPage.Server) }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        fun showSnackbar(message: String) {
            scope.launch {
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                AppTabBar(
                    selectedPage = selectedPage,
                    onPageSelected = { selectedPage = it }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                    AnimatedContent(
                        targetState = selectedPage,
                        transitionSpec = {
                            // Quiet crossfade with a hint of scale — sections cascade in individually.
                            (fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                                    scaleIn(initialScale = 0.985f, animationSpec = tween(300, easing = FastOutSlowInEasing))) togetherWith
                                    fadeOut(tween(160))
                        },
                        label = "pageTransition"
                    ) { page ->
                        when (page) {
                            NavPage.Server -> DashboardScreen(
                                isRunning = isServerRunning,
                                statusText = serverStatusText.ifBlank { "Stopped" },
                                statusDetail = serverStatusDetail.ifBlank { "Server idle" },
                                hasError = serverHasError,
                                isSecured = isSecured,
                                ipAddress = ipAddress,
                                endpoint = endpoint,
                                port = portText,
                                apiKey = apiKeyText,
                                mcpConfigPreview = mcpConfigText,
                                uptimeMs = uptimeMs,
                                requestCount = requestCount,
                                toolCallCount = toolCallCount,
                                errorCount = errorCount,
                                connectionCount = connectionCount,
                                onToggleServer = { toggleServerWithHaptic() },
                                onPortChange = {
                                    portText = it
                                    saveSettings()
                                    renderConnectionInfo()
                                },
                                onApiKeyChange = {
                                    apiKeyText = it
                                    saveSettings()
                                    renderConnectionInfo()
                                    refreshPermissionStates()
                                },
                                onGenerateKey = {
                                    apiKeyText = ApiKeyGenerator.generate()
                                    saveSettings()
                                    renderConnectionInfo()
                                    refreshPermissionStates()
                                    showSnackbar(getString(R.string.api_key_generated))
                                    addRuntimeLog("API key regenerated", LogLevel.INFO)
                                },
                                onCopyEndpoint = {
                                    copyToClipboard("MCP endpoint", endpoint)
                                    showSnackbar(getString(R.string.endpoint_copied))
                                    addRuntimeLog("Endpoint copied to clipboard", LogLevel.INFO)
                                },
                                onCopyConfig = {
                                    copyToClipboard("MCP config", mcpConfigText)
                                    showSnackbar(getString(R.string.config_copied))
                                    addRuntimeLog("MCP config copied to clipboard", LogLevel.INFO)
                                },
                                onCopyIp = {
                                    copyToClipboard("IP address", ipAddress)
                                    showSnackbar("IP address copied")
                                    addRuntimeLog("IP address copied to clipboard", LogLevel.INFO)
                                }
                            )

                            NavPage.Logs -> LogsScreen(
                                logEvents = logEvents,
                                onClearLogs = { logEvents.clear() },
                                onViewAllNotifications = {
                                    showSnackbar("Notification history view coming next")
                                    addRuntimeLog("Notification history requested", LogLevel.INFO)
                                }
                            )

                            NavPage.Tools -> ToolsScreen(
                                accessibilityEnabled = accessibilityEnabled,
                                notificationAccessEnabled = notificationAccessEnabled,
                                mediaAccessEnabled = mediaAccessEnabled,
                                smsPermissionGranted = smsPermissionGranted,
                                contactsPermissionGranted = contactsPermissionGranted,
                                callPermissionGranted = callPermissionGranted,
                                locationPermissionGranted = locationPermissionGranted,
                                recordAudioPermissionGranted = recordAudioPermissionGranted,
                                cameraPermissionGranted = cameraPermissionGranted
                            )

                            NavPage.Settings -> SettingsScreen(
                                isApiAuthEnabled = apiKeyText.isNotBlank(),
                                isTailscaleEnabled = tailscaleEnabled,
                                isNotificationAccessEnabled = notificationAccessEnabled,
                                isFileStorageEnabled = mediaAccessEnabled,
                                isDeveloperModeEnabled = developerModeEnabled,
                                accessibilityState = if (accessibilityEnabled) "Enabled in settings" else "Missing — tap to enable",
                                safetyFilterMode = safetyFilterMode,
                                onToggleApiAuth = { enabled ->
                                    if (enabled) {
                                        if (apiKeyText.isBlank()) {
                                            apiKeyText = ApiKeyGenerator.generate()
                                            showSnackbar(getString(R.string.api_key_generated))
                                        }
                                        addRuntimeLog("API key auth enabled", LogLevel.INFO)
                                    } else {
                                        apiKeyText = ""
                                        addRuntimeLog("API key auth disabled", LogLevel.INFO)
                                    }
                                    saveSettings()
                                    renderConnectionInfo()
                                    refreshPermissionStates()
                                },
                                onToggleTailscale = { enabled ->
                                    tailscaleEnabled = enabled
                                    preferences.edit().putBoolean(PREF_TAILSCALE_ENABLED, enabled).apply()
                                    addRuntimeLog(if (enabled) "Tailscale integration enabled" else "Tailscale integration disabled", LogLevel.INFO)
                                },
                                onToggleNotificationAccess = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        requestPermissionLauncher.launch(
                                            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                                        )
                                    }
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    addRuntimeLog("Opened notification listener settings", LogLevel.INFO)
                                },
                                onToggleFileStorage = { enabled ->
                                    if (enabled) {
                                        requestMediaPermissionIfMissing()
                                        addRuntimeLog("Requested file storage permission", LogLevel.INFO)
                                    } else {
                                        showSnackbar("Disable file access from Android settings")
                                        refreshPermissionStates()
                                    }
                                },
                                onToggleDeveloperMode = { enabled ->
                                    developerModeEnabled = enabled
                                    preferences.edit().putBoolean(PREF_DEVELOPER_MODE, enabled).apply()
                                    addRuntimeLog(if (enabled) "Developer mode enabled" else "Developer mode disabled", LogLevel.INFO)
                                },
                                onOpenAccessibilitySettings = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    addRuntimeLog("Opened accessibility settings", LogLevel.INFO)
                                },
                                onCycleSafetyFilter = {
                                    val next = when (safetyFilterMode) {
                                        SAFETY_STRICT -> SAFETY_BALANCED
                                        SAFETY_BALANCED -> SAFETY_OFF
                                        else -> SAFETY_STRICT
                                    }
                                    safetyFilterMode = next
                                    preferences.edit().putString(PREF_SAFETY_FILTER_MODE, next).apply()
                                    addRuntimeLog("Safety filter set to $next", LogLevel.INFO)
                                },
                                onManageKeys = {
                                    val key = if (apiKeyText.isNotBlank()) apiKeyText else {
                                        apiKeyText = ApiKeyGenerator.generate()
                                        saveSettings()
                                        renderConnectionInfo()
                                        refreshPermissionStates()
                                        apiKeyText
                                    }
                                    copyToClipboard("PocketMCP API key", key)
                                    showSnackbar("API key copied")
                                    addRuntimeLog("API key copied from settings", LogLevel.INFO)
                                }
                            )
                        }
                    }
                }
        }
    }

    // --- Business Logic ---

    private fun toggleServerWithHaptic() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (isServerRunning) {
            stopServer()
        } else {
            startServer()
        }
    }

    private fun startServer() {
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
        if (port == null) {
            addRuntimeLog("Invalid port: $portText", LogLevel.ERROR)
            return
        }

        saveSettings()

        val apiKey = apiKeyText.trim()
        if (apiKey.isBlank()) {
            addRuntimeLog("Security warning: no API key set", LogLevel.ERROR)
        }

        val startIntent = Intent(this, McpServerService::class.java).apply {
            action = McpServerService.ACTION_START
            putExtra(McpServerService.EXTRA_PORT, port)
            if (apiKey.isNotBlank()) {
                putExtra(McpServerService.EXTRA_API_KEY, apiKey)
            }
        }
        ContextCompat.startForegroundService(this, startIntent)

        isServerRunning = true
        serverStatusText = getString(R.string.server_status_starting)
        serverStatusDetail = "Initializing server..."
        serverHasError = false
        isSecured = apiKey.isNotBlank()
        addRuntimeLog("Server start requested on port $port", LogLevel.INFO)
    }

    private fun stopServer() {
        val stopIntent = Intent(this, McpServerService::class.java).apply {
            action = McpServerService.ACTION_STOP
        }
        startService(stopIntent)

        isServerRunning = false
        serverStatusText = getString(R.string.server_status_stopping)
        serverStatusDetail = "Shutting down..."
        serverHasError = false
        uptimeMs = 0L
        connectionCount = 0
        addRuntimeLog("Server stop requested", LogLevel.INFO)
    }

    private fun queryServiceStatus() {
        val queryIntent = Intent(this, McpServerService::class.java).apply {
            action = McpServerService.ACTION_QUERY_STATUS
        }
        startService(queryIntent)
    }

    private fun renderConnectionInfo() {
        val port = portText.toIntOrNull() ?: 8080
        ipAddress = resolveIpAddress()
        endpoint = "http://$ipAddress:$port/mcp"
        mcpConfigText = McpConfigBuilder.build(endpoint, apiKeyText)
        isSecured = apiKeyText.isNotBlank()
    }

    private fun restoreSavedSettings() {
        val savedPort = preferences.getInt(PREF_PORT, 8080)
        portText = savedPort.toString()

        val encryptedKey = SecurePrefs.getApiKey(this)
        val legacyKey = preferences.getString(PREF_API_KEY, null)?.takeIf { it.isNotBlank() }
        apiKeyText = when {
            !encryptedKey.isNullOrBlank() -> encryptedKey
            legacyKey != null -> {
                preferences.edit().remove(PREF_API_KEY).apply()
                SecurePrefs.setApiKey(this, legacyKey)
                addRuntimeLog("Migrated API key to encrypted storage", LogLevel.INFO)
                legacyKey
            }
            else -> ApiKeyGenerator.generate().also { SecurePrefs.setApiKey(this, it) }
        }
    }

    private fun restoreUiPreferences() {
        tailscaleEnabled = preferences.getBoolean(PREF_TAILSCALE_ENABLED, false)
        developerModeEnabled = preferences.getBoolean(PREF_DEVELOPER_MODE, false)
        safetyFilterMode = preferences.getString(PREF_SAFETY_FILTER_MODE, SAFETY_STRICT) ?: SAFETY_STRICT
    }

    private fun refreshPermissionStates() {
        accessibilityEnabled = isAccessibilityServiceEnabled()
        notificationAccessEnabled = isNotificationListenerEnabled()
        mediaAccessEnabled = hasMediaPermission()
        smsPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        contactsPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        callPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        locationPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        recordAudioPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        cameraPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun saveSettings() {
        val port = portText.toIntOrNull() ?: 8080
        preferences.edit().putInt(PREF_PORT, port).apply()
        if (apiKeyText.isBlank()) {
            SecurePrefs.clearApiKey(this)
        } else {
            SecurePrefs.setApiKey(this, apiKeyText)
        }
    }

    private fun requestMediaPermissionIfMissing() {
        val permission = mediaPermissionForVersion()
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            refreshPermissionStates()
            return
        }
        requestPermissionLauncher.launch(arrayOf(permission))
    }

    private fun mediaPermissionForVersion(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasMediaPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            mediaPermissionForVersion()
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "${packageName}/${PhoneAccessibilityService::class.java.name}"
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val expectedComponent = "${packageName}/${NotificationListenerService::class.java.name}"
        val raw = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return raw.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    private fun addRuntimeLog(message: String, level: LogLevel = LogLevel.INFO) {
        val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        if (logEvents.size >= 100) {
            logEvents.removeAt(0)
        }
        logEvents.add(LogEvent(timestamp, message, level))
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun resolveStatusText(running: Boolean, statusText: String?): String {
        val normalized = statusText?.trim()?.lowercase()
        return when (normalized) {
            null, "", "status" -> if (running) getString(R.string.server_status_running) else getString(R.string.server_status_stopped)
            "running" -> getString(R.string.server_status_running)
            "stopped", "stop", "stopping", "stopped by user" -> getString(R.string.server_status_stopped)
            "starting" -> getString(R.string.server_status_starting)
            "failed", "error" -> getString(R.string.server_status_failed)
            else -> statusText
        }
    }

    private fun resolveIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return getString(R.string.ip_unknown)
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val host = address.hostAddress ?: continue
                    if (!address.isLoopbackAddress && !host.contains(':')) {
                        return host
                    }
                }
            }
            getString(R.string.ip_unknown)
        } catch (_: Exception) {
            getString(R.string.ip_unknown)
        }
    }

    private companion object {
        private const val PREFS_NAME = "pocket_mcp_prefs"
        private const val PREF_PORT = "pref_port"
        private const val PREF_API_KEY = "pref_api_key"
        private const val PREF_TAILSCALE_ENABLED = "pref_tailscale_enabled"
        private const val PREF_DEVELOPER_MODE = "pref_developer_mode"
        private const val PREF_SAFETY_FILTER_MODE = "pref_safety_filter_mode"

        private const val SAFETY_STRICT = "Strict"
        private const val SAFETY_BALANCED = "Balanced"
        private const val SAFETY_OFF = "Off"
    }
}
