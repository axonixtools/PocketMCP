package com.pocketmcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pocketmcp.MainActivity
import com.pocketmcp.R
import com.pocketmcp.server.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

        private const val CHANNEL_ID = "mcp_server_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mcpServer: McpServer? = null
    private var currentPort: Int = 8080
    private var apiKey: String? = null
    private var lastError: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                val portExtra = intent?.getIntExtra(EXTRA_PORT, currentPort) ?: currentPort
                currentPort = if (portExtra in 1..65535) portExtra else 8080
                apiKey = intent?.getStringExtra(EXTRA_API_KEY)?.trim()?.takeIf { it.isNotEmpty() }
                startForegroundInternal(getString(R.string.server_starting_notification, currentPort))
                startServer()
            }

            ACTION_STOP -> stopServer("Stopped")
            ACTION_QUERY_STATUS -> {
                broadcastStatus("Status")
                if (mcpServer?.isRunning() != true) {
                    stopSelf(startId)
                }
            }
        }

        return if (mcpServer?.isRunning() == true) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
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
        val update = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_ERROR, error)
            putExtra(EXTRA_PORT, currentPort)
            putExtra(EXTRA_SECURED, !apiKey.isNullOrBlank())
        }
        sendBroadcast(update)
    }

    private fun startForegroundInternal(message: String) {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(message))
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

}
