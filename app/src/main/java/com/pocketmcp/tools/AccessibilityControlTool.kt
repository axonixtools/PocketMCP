package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import com.pocketmcp.util.AccessibilitySetup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Reports and toggles the PocketMCP accessibility service.
 *
 * Toggling requires the one-time `WRITE_SECURE_SETTINGS` grant that
 * [AccessibilitySetup] documents; without it this tool can only report status
 * and hand back the exact ADB command to run.
 */
class AccessibilityControlTool : McpToolHandler {
    override val name = "accessibility_control"
    override val description =
        "Check or toggle the PocketMCP accessibility service. Enabling from the app " +
            "requires a one-time ADB grant (WRITE_SECURE_SETTINGS); status always works."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "status (default), enable, or disable")
                putJsonArray("enum") {
                    add("status")
                    add("enable")
                    add("disable")
                }
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = (argString(args, "action") ?: "status").lowercase()
        when (action) {
            "status" -> return resultJson(statusPayload(context))
            "enable" -> return toggle(context, true)
            "disable" -> return toggle(context, false)
            else -> return resultError("Unknown action '$action'. Use status, enable, or disable.")
        }
    }

    private fun toggle(context: Context, enabled: Boolean): McpToolCallResult {
        if (!AccessibilitySetup.hasSecureSettingsAccess(context)) {
            return resultError(
                "WRITE_SECURE_SETTINGS is not granted, so the app cannot change secure settings " +
                    "itself. Run this once with the phone connected over ADB: " +
                    AccessibilitySetup.ADB_GRANT_COMMAND
            )
        }
        val changed = AccessibilitySetup.setServiceEnabled(context, enabled)
        if (!changed) {
            return resultError("Failed to update accessibility secure settings.")
        }
        val actual = AccessibilitySetup.isServiceEnabled(context)
        val payload = buildJsonObject {
            put("success", true)
            put("requested", enabled)
            put("accessibility_enabled", actual)
        }
        return resultJson(payload)
    }

    private fun statusPayload(context: Context): JsonObject = buildJsonObject {
        put("accessibility_enabled", AccessibilitySetup.isServiceEnabled(context))
        put("secure_settings_granted", AccessibilitySetup.hasSecureSettingsAccess(context))
        put("service_component", AccessibilitySetup.SERVICE_COMPONENT)
        put("adb_grant_command", AccessibilitySetup.ADB_GRANT_COMMAND)
        put("adb_enable_command", AccessibilitySetup.ADB_ENABLE_COMMAND)
    }
}
