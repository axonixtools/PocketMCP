package com.pocketmcp.tools

import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class NotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { notification ->
            try {
                NotificationManager.addNotification(
                    serializeNotification(notification, action = "posted").toString()
                )
            } catch (_: Exception) {
                // Ignore errors to avoid crashing the notification service.
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let { notification ->
            try {
                NotificationManager.addNotification(
                    serializeNotification(notification, action = "removed").toString()
                )
            } catch (_: Exception) {
                // Ignore errors to avoid crashing the notification service.
            }
        }
    }

    companion object {
        @Volatile
        private var instance: NotificationListenerService? = null

        fun isConnected(): Boolean = instance != null

        fun getActiveNotificationsSnapshot(limit: Int = 20): List<String> {
            val service = instance ?: return emptyList()
            val snapshot = runCatching {
                service.activeNotifications?.toList().orEmpty()
            }.getOrDefault(emptyList())

            return snapshot
                .sortedByDescending { it.postTime }
                .take(limit.coerceIn(1, 100))
                .map { serializeNotification(it, action = "active").toString() }
        }

        fun openNotification(
            key: String? = null,
            query: String? = null,
            index: Int = 1
        ): OpenNotificationResult {
            val service = instance ?: return OpenNotificationResult(
                success = false,
                error = "Notification listener is not connected."
            )

            val active = runCatching { service.activeNotifications?.toList().orEmpty() }
                .getOrDefault(emptyList())
            if (active.isEmpty()) {
                return OpenNotificationResult(
                    success = false,
                    error = "No active notifications available."
                )
            }

            val sorted = active.sortedByDescending { it.postTime }
            val target = when {
                !key.isNullOrBlank() -> sorted.firstOrNull { it.key == key.trim() }
                !query.isNullOrBlank() -> {
                    val normalized = query.trim()
                    val matches = sorted.filter { sbn ->
                        val payload = serializeNotification(sbn, action = "active")
                        val packageName = payload["package_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val title = payload["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val text = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        packageName.contains(normalized, ignoreCase = true) ||
                            title.contains(normalized, ignoreCase = true) ||
                            text.contains(normalized, ignoreCase = true)
                    }
                    matches.getOrNull(index.coerceAtLeast(1) - 1)
                }
                else -> sorted.getOrNull(index.coerceAtLeast(1) - 1)
            }

            if (target == null) {
                return OpenNotificationResult(
                    success = false,
                    error = "No matching active notification found."
                )
            }

            val pendingIntent: PendingIntent = target.notification.contentIntent
                ?: return OpenNotificationResult(
                    success = false,
                    error = "Matched notification does not expose a tappable content intent.",
                    key = target.key
                )

            return try {
                pendingIntent.send()
                val payload = serializeNotification(target, action = "opened")
                OpenNotificationResult(
                    success = true,
                    key = target.key,
                    packageName = payload["package_name"]?.jsonPrimitive?.contentOrNull,
                    title = payload["title"]?.jsonPrimitive?.contentOrNull,
                    text = payload["text"]?.jsonPrimitive?.contentOrNull
                )
            } catch (e: Exception) {
                OpenNotificationResult(
                    success = false,
                    error = "Failed to open notification: ${e.message}",
                    key = target.key
                )
            }
        }

        private fun serializeNotification(
            notification: StatusBarNotification,
            action: String
        ) = buildJsonObject {
            val extras = notification.notification.extras
            put("type", "notification")
            put("action", action)
            put("package_name", notification.packageName)
            put("title", extras.getCharSequence("android.title")?.toString() ?: "")
            put("text", extras.getCharSequence("android.text")?.toString() ?: "")
            put("big_text", extras.getCharSequence("android.bigText")?.toString() ?: "")
            put("sub_text", extras.getCharSequence("android.subText")?.toString() ?: "")
            put("info_text", extras.getCharSequence("android.infoText")?.toString() ?: "")
            put("key", notification.key)
            put("id", notification.id)
            put("post_time", notification.postTime)
            put("can_clear", notification.isClearable)
            put("ongoing", notification.isOngoing)
            put("category", notification.notification.category ?: "")
            put("priority", notification.notification.priority)
            put("visibility", notification.notification.visibility.toString())
        }
    }
}

data class OpenNotificationResult(
    val success: Boolean,
    val key: String? = null,
    val packageName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val error: String? = null
)

object NotificationManager {
    private val notifications = mutableListOf<String>()
    private val maxNotifications = 100
    
    fun addNotification(notificationJson: String) {
        synchronized(notifications) {
            notifications.add(notificationJson)
            if (notifications.size > maxNotifications) {
                notifications.removeAt(0)
            }
        }
    }
    
    fun getRecentNotifications(count: Int = 50): List<String> {
        return synchronized(notifications) {
            notifications.takeLast(count)
        }
    }
    
    fun clearNotifications() {
        synchronized(notifications) {
            notifications.clear()
        }
    }
    
    fun getNotificationCount(): Int {
        return synchronized(notifications) {
            notifications.size
        }
    }
}
