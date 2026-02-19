package com.pocketmcp.tools

import android.content.Context
import android.provider.Settings
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json

class NotificationTool : McpToolHandler {
    override val name = "notifications"
    override val description = "List recent or active notifications, open a notification, and manage listener status."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Action: 'list', 'active', 'open', 'clear', 'status'")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Maximum notifications to return (default: 20, max: 100)")
            }
            putJsonObject("key") {
                put("type", "string")
                put("description", "Notification key to open (preferred for action='open').")
            }
            putJsonObject("query") {
                put("type", "string")
                put("description", "Package/title/text search term for action='open'.")
            }
            putJsonObject("index") {
                put("type", "integer")
                put("description", "1-based index among matches for action='open' (default: 1).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = (argString(args, "action") ?: "list").trim().lowercase()
        val limit = (argInt(args, "limit") ?: 20).coerceIn(1, 100)
        val key = argString(args, "key")?.trim()
        val query = argString(args, "query")?.trim()
        val index = (argInt(args, "index") ?: 1).coerceAtLeast(1)

        return when (action) {
            "list" -> getNotifications(limit)
            "active" -> getActiveNotifications(limit)
            "open" -> openNotification(key, query, index)
            "clear" -> clearNotifications()
            "status" -> getNotificationStatus(context)
            else -> resultError("Invalid action. Use: list, active, open, clear, status")
        }
    }

    private fun getNotifications(limit: Int): McpToolCallResult {
        val notifications = NotificationManager.getRecentNotifications(limit)
        val payload = buildJsonObject {
            put("action", "list")
            put("count", notifications.size)
            put("limit", limit)
            put("total_stored", NotificationManager.getNotificationCount())
            put("notifications", buildJsonArray {
                notifications.forEach { notification ->
                    add(Json.parseToJsonElement(notification))
                }
            })
        }
        return resultJson(payload)
    }

    private fun getActiveNotifications(limit: Int): McpToolCallResult {
        val notifications = NotificationListenerService.getActiveNotificationsSnapshot(limit)
        val payload = buildJsonObject {
            put("action", "active")
            put("count", notifications.size)
            put("limit", limit)
            put("service_connected", NotificationListenerService.isConnected())
            put("notifications", buildJsonArray {
                notifications.forEach { notification ->
                    add(Json.parseToJsonElement(notification))
                }
            })
        }
        return resultJson(payload)
    }

    private fun openNotification(key: String?, query: String?, index: Int): McpToolCallResult {
        val result = NotificationListenerService.openNotification(
            key = key,
            query = query,
            index = index
        )

        if (!result.success) {
            return resultError(result.error ?: "Failed to open notification.")
        }

        val payload = buildJsonObject {
            put("action", "open")
            put("success", true)
            put("key", result.key ?: "")
            put("package_name", result.packageName ?: "")
            put("title", result.title ?: "")
            put("text", result.text ?: "")
        }
        return resultJson(payload)
    }

    private fun clearNotifications(): McpToolCallResult {
        NotificationManager.clearNotifications()
        val payload = buildJsonObject {
            put("action", "clear")
            put("success", true)
            put("message", "Notification history cleared")
        }
        return resultJson(payload)
    }

    private fun getNotificationStatus(context: Context): McpToolCallResult {
        val enabled = isNotificationListenerEnabled(context)
        val payload = buildJsonObject {
            put("action", "status")
            put("enabled", enabled)
            put("service_connected", NotificationListenerService.isConnected())
            put("stored_count", NotificationManager.getNotificationCount())
            put("message", if (enabled) "Notification listener is active" else "Notification listener is disabled")
            if (!enabled) {
                put("instructions", "Enable PocketMCP Notification Listener in Android Settings > Apps > Special Access > Notification Access")
            }
        }
        return resultJson(payload)
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(packageName) == true
    }
}
