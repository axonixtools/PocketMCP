package com.pocketmcp.tools

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class GlobalActionTool : McpToolHandler {
    override val name = "global_action"
    override val description = "Run Android global actions such as home, back, recents, and lock screen."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "One of: home, back, recents, notifications, quick_settings, power_dialog, lock_screen, close_current_app."
                )
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: android.content.Context): McpToolCallResult {
        val action = (argString(args, "action") ?: "").trim().lowercase()
        if (action.isBlank()) {
            return resultError("Missing required argument: action")
        }
        if (!PhoneAccessibilityService.isEnabled()) {
            return resultError(
                "Accessibility automation is disabled. Enable PocketMCP Accessibility Service in Android Accessibility settings."
            )
        }

        return when (action) {
            "home" -> runSingleAction(action, AccessibilityService.GLOBAL_ACTION_HOME)
            "back" -> runSingleAction(action, AccessibilityService.GLOBAL_ACTION_BACK)
            "recents" -> runSingleAction(action, AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> runSingleAction(action, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> runSingleAction(action, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "power_dialog" -> runSingleAction(action, AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            "lock_screen" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    resultError("lock_screen requires Android 9+.")
                } else {
                    runSingleAction(action, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
            "close_current_app" -> closeCurrentApp()
            else -> resultError(
                "Invalid action '$action'. Use home, back, recents, notifications, quick_settings, power_dialog, lock_screen, or close_current_app."
            )
        }
    }

    private fun runSingleAction(action: String, actionCode: Int): McpToolCallResult {
        val ok = PhoneAccessibilityService.runGlobalAction(actionCode)
        return if (!ok) {
            resultError("Global action '$action' failed.")
        } else {
            resultJson(
                buildJsonObject {
                    put("action", action)
                    put("success", true)
                }
            )
        }
    }

    private suspend fun closeCurrentApp(): McpToolCallResult {
        val swiped = closeForegroundAppBestEffort()

        val payload = buildJsonObject {
            put("action", "close_current_app")
            put("success", swiped)
            put("note", "Best effort only. Android does not allow third-party apps to force-stop arbitrary packages.")
        }
        return resultJson(payload)
    }
}
