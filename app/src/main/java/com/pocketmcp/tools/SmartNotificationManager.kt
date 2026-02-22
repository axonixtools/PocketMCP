package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

class SmartNotificationManager : McpToolHandler {
    override val name = "smart_notification_manager"
    override val description = "Advanced notification management with filtering, auto-response, and intelligent categorization."
    
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Action: list, filter, auto_respond, mark_read, clear, monitor")
            }
            putJsonObject("filters") {
                put("type", "object")
                put("description", "Notification filtering criteria.")
                putJsonObject("properties") {
                    putJsonObject("apps") {
                        put("type", "array")
                        put("description", "Specific app package names to include/exclude.")
                    }
                    putJsonObject("keywords") {
                        put("type", "array")
                        put("description", "Keywords to search for in notification text.")
                    }
                    putJsonObject("time_range") {
                        put("type", "object")
                        put("description", "Time range filter (last_minutes, last_hours).")
                    }
                    putJsonObject("priority") {
                        put("type", "string")
                        put("description", "Priority filter: high, normal, low")
                    }
                }
            }
            putJsonObject("auto_response") {
                put("type", "object")
                put("description", "Auto-response configuration.")
                putJsonObject("properties") {
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "Response message template.")
                    }
                    putJsonObject("conditions") {
                        put("type", "object")
                        put("description", "Conditions for auto-response.")
                    }
                }
            }
            putJsonObject("monitor_duration_ms") {
                put("type", "integer")
                put("description", "Duration to monitor for new notifications (default: 10000).")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Maximum number of notifications to return (default: 50).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = argString(args, "action")?.trim()?.lowercase()
            ?: return resultError("action is required")

        return when (action) {
            "list" -> listNotifications(args, context)
            "filter" -> filterNotifications(args, context)
            "auto_respond" -> autoRespondToNotifications(args, context)
            "mark_read" -> markNotificationsAsRead(args, context)
            "clear" -> clearNotifications(args, context)
            "monitor" -> monitorNotifications(args, context)
            else -> resultError("Unknown action: $action")
        }
    }

    private suspend fun listNotifications(args: JsonObject?, context: Context): McpToolCallResult {
        val limit = argInt(args, "limit") ?: 50
        val notifications = getRecentNotifications(limit)
        
        val categorizedNotifications = categorizeNotifications(notifications)
        val analytics = generateNotificationAnalytics(notifications)

        val payload = buildJsonObject {
            put("action", "list")
            put("success", true)
            put("total_count", notifications.size)
            put("categories", categorizedNotifications)
            put("analytics", analytics)
            put("notifications", buildJsonArray {
                notifications.forEach { notification ->
                    add(buildJsonObject {
                        put("id", notification.id)
                        put("app_name", notification.appName)
                        put("package_name", notification.packageName)
                        put("title", notification.title)
                        put("text", notification.text)
                        put("time", notification.time)
                        put("priority", notification.priority)
                        put("category", categorizeNotification(notification))
                    })
                }
            })
        }

        return resultJson(payload)
    }

    private suspend fun filterNotifications(args: JsonObject?, context: Context): McpToolCallResult {
        val filters = argJsonObject(args, "filters") ?: buildJsonObject {}
        val allNotifications = getRecentNotifications(200) // Get more for filtering
        
        val filteredNotifications = applyFilters(allNotifications, filters)
        val filterStats = generateFilterStats(allNotifications, filteredNotifications, filters)

        val payload = buildJsonObject {
            put("action", "filter")
            put("success", true)
            put("original_count", allNotifications.size)
            put("filtered_count", filteredNotifications.size)
            put("filters_applied", filters)
            put("filter_stats", filterStats)
            put("notifications", buildJsonArray {
                filteredNotifications.forEach { notification ->
                    add(buildJsonObject {
                        put("id", notification.id)
                        put("app_name", notification.appName)
                        put("package_name", notification.packageName)
                        put("title", notification.title)
                        put("text", notification.text)
                        put("time", notification.time)
                        put("priority", notification.priority)
                        put("relevance_score", calculateRelevanceScore(notification, filters))
                    })
                }
            })
        }

        return resultJson(payload)
    }

    private suspend fun autoRespondToNotifications(args: JsonObject?, context: Context): McpToolCallResult {
        val autoResponse = argJsonObject(args, "auto_response") 
            ?: return resultError("auto_response configuration required")
        val filters = argJsonObject(args, "filters") ?: buildJsonObject {}
        
        val message = argString(autoResponse, "message") 
            ?: return resultError("Response message required")
        val conditions = argJsonObject(autoResponse, "conditions") ?: buildJsonObject {}

        val notifications = getRecentNotifications(50)
        val eligibleNotifications = notifications.filter { notification ->
            meetsAutoResponseConditions(notification, conditions)
        }

        val responses = mutableListOf<JsonObject>()
        for (notification in eligibleNotifications) {
            try {
                val responseResult = sendAutoResponse(notification, message, context)
                responses.add(responseResult)
                delay(1000) // Rate limiting
            } catch (error: Exception) {
                responses.add(buildJsonObject {
                    put("notification_id", notification.id)
                    put("success", false)
                    put("error", error.message)
                })
            }
        }

        val payload = buildJsonObject {
            put("action", "auto_respond")
            put("success", true)
            put("eligible_count", eligibleNotifications.size)
            put("responses_sent", responses.count { it["success"]?.toString()?.toBooleanStrictOrNull() == true })
            put("responses", buildJsonArray { responses.forEach { add(it) } })
        }

        return resultJson(payload)
    }

    private suspend fun monitorNotifications(args: JsonObject?, context: Context): McpToolCallResult {
        val durationMs = (argInt(args, "monitor_duration_ms") ?: 10000).toLong()
        val filters = argJsonObject(args, "filters") ?: buildJsonObject {}
        
        val startTime = System.currentTimeMillis()
        val initialNotifications = getRecentNotifications(100)
        val newNotifications = mutableListOf<NotificationData>()
        
        while (System.currentTimeMillis() - startTime < durationMs) {
            delay(2000) // Check every 2 seconds
            val currentNotifications = getRecentNotifications(100)
            val latest = currentNotifications.filter { notification ->
                notification.time > startTime && 
                !initialNotifications.any { it.id == notification.id } &&
                meetsFilterConditions(notification, filters)
            }
            
            newNotifications.addAll(latest)
        }

        val payload = buildJsonObject {
            put("action", "monitor")
            put("success", true)
            put("monitor_duration_ms", System.currentTimeMillis() - startTime)
            put("new_notifications_count", newNotifications.size)
            put("new_notifications", buildJsonArray {
                newNotifications.forEach { notification ->
                    add(buildJsonObject {
                        put("id", notification.id)
                        put("app_name", notification.appName)
                        put("package_name", notification.packageName)
                        put("title", notification.title)
                        put("text", notification.text)
                        put("time", notification.time)
                        put("priority", notification.priority)
                        put("arrival_time_ms", notification.time - startTime)
                    })
                }
            })
        }

        return resultJson(payload)
    }

    private suspend fun markNotificationsAsRead(args: JsonObject?, context: Context): McpToolCallResult {
        val notificationIds = argStringList(args, "notification_ids")
            ?: return resultError("notification_ids array required")

        val results = mutableListOf<JsonObject>()
        for (id in notificationIds) {
            try {
                val success = markNotificationAsRead(id)
                results.add(buildJsonObject {
                    put("notification_id", id)
                    put("success", success)
                })
            } catch (error: Exception) {
                results.add(buildJsonObject {
                    put("notification_id", id)
                    put("success", false)
                    put("error", error.message)
                })
            }
        }

        val payload = buildJsonObject {
            put("action", "mark_read")
            put("success", true)
            put("processed_count", results.size)
            put("successful_count", results.count { it["success"]?.toString()?.toBooleanStrictOrNull() == true })
            put("results", buildJsonArray { results.forEach { add(it) } })
        }

        return resultJson(payload)
    }

    private suspend fun clearNotifications(args: JsonObject?, context: Context): McpToolCallResult {
        val filters = argJsonObject(args, "filters") ?: buildJsonObject {}
        val notifications = getRecentNotifications(200)
        val notificationsToClear = applyFilters(notifications, filters)

        val clearedCount = clearNotifications(notificationsToClear)

        val payload = buildJsonObject {
            put("action", "clear")
            put("success", true)
            put("total_notifications", notifications.size)
            put("matched_filters", notificationsToClear.size)
            put("cleared_count", clearedCount)
        }

        return resultJson(payload)
    }

    // Helper methods
    private fun categorizeNotifications(notifications: List<NotificationData>): JsonObject {
        val categories = mutableMapOf<String, Int>()
        
        notifications.forEach { notification ->
            val category = categorizeNotification(notification)
            categories[category] = categories.getOrDefault(category, 0) + 1
        }

        return buildJsonObject {
            categories.forEach { (category, count) ->
                put(category, count)
            }
        }
    }

    private fun categorizeNotification(notification: NotificationData): String {
        val packageName = notification.packageName.lowercase()
        val title = notification.title.lowercase()
        val text = notification.text.lowercase()

        return when {
            packageName.contains("whatsapp") || packageName.contains("telegram") || 
            packageName.contains("messenger") || packageName.contains("instagram") -> "messaging"
            
            packageName.contains("gmail") || packageName.contains("outlook") || 
            packageName.contains("email") -> "email"
            
            packageName.contains("youtube") || packageName.contains("spotify") || 
            packageName.contains("netflix") -> "media"
            
            packageName.contains("facebook") || packageName.contains("twitter") || 
            packageName.contains("instagram") -> "social"
            
            packageName.contains("maps") || packageName.contains("uber") || 
            packageName.contains("lyft") -> "navigation"
            
            title.contains("call") || text.contains("call") -> "calls"
            
            title.contains("battery") || title.contains("storage") || 
            title.contains("memory") -> "system"
            
            else -> "other"
        }
    }

    private fun generateNotificationAnalytics(notifications: List<NotificationData>): JsonObject {
        val now = System.currentTimeMillis()
        val lastHour = notifications.filter { it.time > now - 3600000 }
        val last24Hours = notifications.filter { it.time > now - 86400000 }
        
        val priorityDistribution = mutableMapOf<String, Int>()
        notifications.forEach { notification ->
            val priority = notification.priority ?: "normal"
            priorityDistribution[priority] = priorityDistribution.getOrDefault(priority, 0) + 1
        }

        return buildJsonObject {
            put("total_count", notifications.size)
            put("last_hour_count", lastHour.size)
            put("last_24_hours_count", last24Hours.size)
            put("priority_distribution", buildJsonObject {
                priorityDistribution.forEach { (priority, count) ->
                    put(priority, count)
                }
            })
            put("most_active_apps", buildJsonArray {
                notifications.groupBy { it.packageName }
                    .mapValues { it.value.size }
                    .toList()
                    .sortedByDescending { it.second }
                    .take(5)
                    .forEach { (app, count) ->
                        add(buildJsonObject {
                            put("package_name", app)
                            put("count", count)
                        })
                    }
            })
        }
    }

    private fun calculateRelevanceScore(notification: NotificationData, filters: JsonObject): Double {
        var score = 1.0
        
        // Boost score for priority
        when (notification.priority) {
            "high" -> score *= 1.5
            "low" -> score *= 0.7
            else -> score *= 1.0
        }
        
        // Boost score for recent notifications
        val now = System.currentTimeMillis()
        val ageHours = (now - notification.time) / 3600000.0
        score *= maxOf(0.1, 1.0 - (ageHours / 24.0))
        
        return score
    }

    // Data class for notification handling
    data class NotificationData(
        val id: String,
        val appName: String,
        val packageName: String,
        val title: String,
        val text: String,
        val time: Long,
        val priority: String?
    )

    // Placeholder methods - these would need to be implemented based on actual notification handling
    private suspend fun getRecentNotifications(limit: Int): List<NotificationData> {
        // This would integrate with the existing NotificationTool
        return emptyList()
    }

    private fun applyFilters(notifications: List<NotificationData>, filters: JsonObject): List<NotificationData> {
        return notifications.filter { meetsFilterConditions(it, filters) }
    }

    private fun meetsFilterConditions(notification: NotificationData, filters: JsonObject): Boolean {
        // Implement filtering logic based on filters object
        return true
    }

    private fun meetsAutoResponseConditions(notification: NotificationData, conditions: JsonObject): Boolean {
        // Implement auto-response condition checking
        return false
    }

    private suspend fun sendAutoResponse(notification: NotificationData, message: String, context: Context): JsonObject {
        // Implement auto-response logic
        return buildJsonObject {
            put("notification_id", notification.id)
            put("success", false)
            put("error", "Auto-response not implemented")
        }
    }

    private fun markNotificationAsRead(notificationId: String): Boolean {
        // Implement mark as read logic
        return false
    }

    private fun clearNotifications(notifications: List<NotificationData>): Int {
        // Implement clear notifications logic
        return 0
    }

    private fun generateFilterStats(original: List<NotificationData>, filtered: List<NotificationData>, filters: JsonObject): JsonObject {
        return buildJsonObject {
            put("original_count", original.size)
            put("filtered_count", filtered.size)
            put("filter_efficiency", if (original.isNotEmpty()) filtered.size.toDouble() / original.size else 0.0)
        }
    }
}
