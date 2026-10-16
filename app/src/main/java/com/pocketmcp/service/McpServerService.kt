package com.pocketmcp.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pocketmcp.MainActivity
import com.pocketmcp.R
import com.pocketmcp.server.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.net.nsd.NsdServiceInfo
import android.net.nsd.NsdManager
import android.util.Log

class McpServerService : Service() {

    companion object {
        const val ACTION_START = "com.pocketmcp.service.START"
        const val ACTION_STOP = "com.pocketmcp.service.STOP"
        const val ACTION_QUERY_STATUS = "com.pocketmcp.service.QUERY_STATUS"
        const val ACTION_STATUS = "com.pocketmcp.service.STATUS"

        const val EXTRA_PORT = "extra_port"
        const val EXTRA_API_KEY = "extra_api_key"

        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_ERROR = "extra_error"
        const val EXTRA_SECURED = "extra_secured"

        const val EXTRA_UPTIME_MS = "extra_uptime_ms"
        const val EXTRA_REQUEST_COUNT = "extra_request_count"
        const val EXTRA_TOOL_CALL_COUNT = "extra_tool_call_count"
        const val EXTRA_ERROR_COUNT = "extra_error_count"
        const val EXTRA_CONNECTION_COUNT = "extra_connection_count"

        // Persisted so UpdateResumeReceiver can restart the server after a self-update.
        const val STATE_PREFS_NAME = "pocket_mcp_state"
        const val KEY_SERVER_SHOULD_RUN = "server_should_run"
        const val KEY_LAST_PORT = "last_port"

        private const val CHANNEL_ID = "mcp_server_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mcpServer: McpServer? = null
    private var currentPort: Int = 8080
    private var apiKey: String? = null
    private var lastError: String? = null

    // mDNS variables
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                val portExtra = intent?.getIntExtra(EXTRA_PORT, currentPort) ?: currentPort
                currentPort = if (portExtra in 1..65535) portExtra else 8080
                apiKey = intent?.getStringExtra(EXTRA_API_KEY)?.trim()?.takeIf { it.isNotEmpty() }
                try {
                    startForegroundInternal(getString(R.string.server_starting_notification, currentPort))
                } catch (error: Exception) {
                    Log.e("McpServerService", "Failed to enter foreground: ${error.message}", error)
                    lastError = error.message ?: "Failed to start foreground service"
                    broadcastStatus("Failed", lastError)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                recordServerState(true)
                startServer()
            }

            ACTION_STOP -> {
                recordServerState(false)
                stopServer("Stopped")
            }
            ACTION_QUERY_STATUS -> {
                broadcastStatus("Status")
                if (mcpServer?.isRunning() != true) {
                    stopSelf(startId)
                }
            }
        }

        return if (mcpServer?.isRunning() == true) START_STICKY else START_NOT_STICKY
    }

    /**
     * Remembers whether the server should be running so [UpdateResumeReceiver]
     * can restart it after the app updates itself. Persists across process
     * death, which is exactly what a package replacement looks like.
     */
    private fun recordServerState(shouldRun: Boolean) {
        getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVER_SHOULD_RUN, shouldRun)
            .putInt(KEY_LAST_PORT, currentPort)
            .apply()
    }

    override fun onDestroy() {
        tearDownMdns()
        mcpServer?.stop()
        mcpServer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startServer() {
        if (mcpServer?.isRunning() == true) {
            updateNotification(getString(R.string.server_running_notification, currentPort))
            broadcastStatus("Running")
            return
        }

        serviceScope.launch {
            runCatching {
                val server = McpServer(
                    context = applicationContext,
                    port = currentPort,
                    apiKey = apiKey
                )
                server.start()
                mcpServer = server
            }.onSuccess {
                lastError = null
                updateNotification(getString(R.string.server_running_notification, currentPort))
                broadcastStatus("Running")
                setupMdns(currentPort)
            }.onFailure { error ->
                val message = error.message ?: "Unknown server error"
                lastError = message
                broadcastStatus("Failed", message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopServer(status: String) {
        serviceScope.launch {
            runCatching {
                mcpServer?.stop()
                tearDownMdns()
            }
            mcpServer = null
            lastError = null
            broadcastStatus(status)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun broadcastStatus(status: String, error: String? = lastError) {
        val running = mcpServer?.isRunning() == true
        val server = mcpServer
        val update = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_ERROR, error)
            putExtra(EXTRA_PORT, currentPort)
            putExtra(EXTRA_SECURED, !apiKey.isNullOrBlank())
            putExtra(EXTRA_UPTIME_MS, server?.uptimeMs ?: 0L)
            putExtra(EXTRA_REQUEST_COUNT, server?.requestCount ?: 0)
            putExtra(EXTRA_TOOL_CALL_COUNT, server?.toolCallCount ?: 0)
            putExtra(EXTRA_ERROR_COUNT, server?.errorCount ?: 0)
            putExtra(EXTRA_CONNECTION_COUNT, server?.activeConnections ?: 0)
        }
        sendBroadcast(update)
    }

    private fun startForegroundInternal(message: String) {
        ensureNotificationChannel()
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceTypes())
            } catch (error: RuntimeException) {
                // Android 14+ can refuse while-in-use types (location/microphone)
                // when the service restarts from the background (e.g. after an
                // in-app update). Fall back to the dataSync-only type so the
                // server still comes back up.
                Log.w(
                    "McpServerService",
                    "Falling back to dataSync foreground type: ${error.message}"
                )
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Android 14+ enforces foreground-service type permissions at
     * [startForeground]. A service declaring `microphone` in the manifest
     * throws [SecurityException] there unless RECORD_AUDIO is granted, which
     * would kill the app on a fresh install, so types are added only after
     * the matching runtime permission is present. The `location` type keeps
     * `get_location` able to take an active fix while the screen is off.
     */
    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return types
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun updateNotification(message: String) {
        ensureNotificationChannel()
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.server_running_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            1001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupMdns(port: Int) {
        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "PocketMCP-${Build.MODEL}"
            serviceType = "_mcp._tcp."
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d("McpServerService", "mDNS Service registered: ${NsdServiceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("McpServerService", "mDNS Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d("McpServerService", "mDNS Service unregistered")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("McpServerService", "mDNS Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("McpServerService", "Failed to register mDNS: ${e.message}")
        }
    }

    private fun tearDownMdns() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.e("McpServerService", "Failed to unregister mDNS: ${e.message}")
        }
        registrationListener = null
    }

}
