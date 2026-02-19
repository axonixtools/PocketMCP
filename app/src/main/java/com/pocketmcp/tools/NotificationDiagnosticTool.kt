package com.pocketmcp.tools

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive

class NotificationDiagnosticTool : McpToolHandler {
    override val name = "notification_diagnostic"
    override val description = "Diagnose notification listener service status and provide setup instructions"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Diagnostic action: 'status', 'enable_instructions', 'test_permission'")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("action"))
        })
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = argString(args, "action")?.trim()?.lowercase()
            ?: return resultError("Action is required")
        
        return when (action) {
            "status" -> getNotificationStatus(context)
            "enable_instructions" -> getEnableInstructions(context)
            "test_permission" -> testNotificationPermission(context)
            else -> resultError("Unsupported action. Use: status, enable_instructions, test_permission")
        }
    }

    private fun getNotificationStatus(context: Context): McpToolCallResult {
        val packageName = context.packageName
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        
        val isServiceEnabled = !TextUtils.isEmpty(enabledListeners) && 
                           enabledListeners.contains(packageName)
        
        val payload = buildJsonObject {
            put("action", "status")
            put("package_name", packageName)
            put("enabled_listeners", enabledListeners ?: "")
            put("service_enabled", isServiceEnabled)
            put("permission_granted", isNotificationPermissionGranted(context))
            put("service_declared", true)
            put("setup_status", if (isServiceEnabled) "✅ Fully Configured" else "❌ Needs Setup")
            
            if (!isServiceEnabled) {
                putJsonObject("setup_required") {
                    put("step_1", "Enable Notification Access in Android Settings")
                    put("step_2", "Select 'PocketMCP Notification Listener' from list")
                    put("step_3", "Toggle the switch to enable")
                    put("step_4", "Restart PocketMCP app")
                }
            }
        }
        return resultJson(payload)
    }

    private fun getEnableInstructions(context: Context): McpToolCallResult {
        val payload = buildJsonObject {
            put("action", "enable_instructions")
            put("title", "How to Enable Notification Listener")
            putJsonObject("steps") {
                put("step_1", "Open Android Settings")
                put("step_2", "Go to 'Apps & notifications' or 'Apps'")
                put("step_3", "Tap 'Special access' or 'Special app access'")
                put("step_4", "Tap 'Notification access'")
                put("step_5", "Find 'PocketMCP Notification Listener' in the list")
                put("step_6", "Toggle the switch to ON position")
                put("step_7", "Grant permissions if prompted")
                put("step_8", "Restart PocketMCP app")
            }
            putJsonObject("alternative_paths") {
                put("path_1", "Settings > Apps > Special access > Notification access")
                put("path_2", "Settings > Notifications > Notification access")
                put("path_3", "Settings > Privacy > Notification access")
            }
            put("note", "The exact path may vary slightly depending on your Android version and manufacturer")
        }
        return resultJson(payload)
    }

    private fun testNotificationPermission(context: Context): McpToolCallResult {
        val hasPermission = isNotificationPermissionGranted(context)
        val payload = buildJsonObject {
            put("action", "test_permission")
            put("permission_granted", hasPermission)
            put("permission_name", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")
            put("manifest_declaration", true)
            
            if (!hasPermission) {
                put("issue", "Permission not granted in system settings")
                put("solution", "Enable notification access manually in Android Settings")
            }
        }
        return resultJson(payload)
    }

    private fun isNotificationPermissionGranted(context: Context): Boolean {
        val packageName = context.packageName
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return !TextUtils.isEmpty(enabledListeners) && 
               enabledListeners.contains(packageName)
    }
}
