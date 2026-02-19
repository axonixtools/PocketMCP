package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ScrollScreenTool : McpToolHandler {
    override val name = "scroll_screen"
    override val description = "Scroll the currently visible app screen by simulated swipe."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("direction") {
                put("type", "string")
                put("description", "One of: up, down, left, right.")
            }
            putJsonObject("distance_ratio") {
                put("type", "number")
                put("description", "Swipe distance ratio between 0.15 and 0.9 (default: 0.55).")
            }
            putJsonObject("duration_ms") {
                put("type", "integer")
                put("description", "Gesture duration between 120 and 1500 ms (default: 320).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        if (!PhoneAccessibilityService.isEnabled()) {
            return resultError(
                "Accessibility automation is disabled. Enable PocketMCP Accessibility Service in Android Accessibility settings."
            )
        }

        val direction = (argString(args, "direction") ?: "").trim().lowercase()
        if (direction !in setOf("up", "down", "left", "right")) {
            return resultError("Invalid direction. Use up, down, left, or right.")
        }

        val distanceRatio = (argDouble(args, "distance_ratio") ?: 0.55).toFloat().coerceIn(0.15f, 0.9f)
        val durationMs = (argInt(args, "duration_ms") ?: 320).toLong().coerceIn(120L, 1500L)
        val ok = PhoneAccessibilityService.runSwipe(direction, distanceRatio, durationMs)
        if (!ok) {
            return resultError("Scroll gesture failed.")
        }

        val payload = buildJsonObject {
            put("direction", direction)
            put("distance_ratio", distanceRatio)
            put("duration_ms", durationMs)
            put("success", true)
        }
        return resultJson(payload)
    }
}
