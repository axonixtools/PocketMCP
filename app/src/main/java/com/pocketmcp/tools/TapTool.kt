package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class TapTool : McpToolHandler {
    override val name = "tap"
    override val description =
        "Tap UI elements via accessibility by visible text/content description, or tap screen coordinates."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Visible text or content description to tap (case-insensitive by default).")
            }
            putJsonObject("exact") {
                put("type", "boolean")
                put("description", "When true, text/description match must be exact.")
            }
            putJsonObject("occurrence") {
                put("type", "integer")
                put("description", "1-based match index when multiple nodes match text (default: 1).")
                put("minimum", 1)
            }
            putJsonObject("x") {
                put("type", "integer")
                put("description", "X coordinate in screen pixels. Use with y for coordinate-based tap.")
            }
            putJsonObject("y") {
                put("type", "integer")
                put("description", "Y coordinate in screen pixels. Use with x for coordinate-based tap.")
            }
            putJsonObject("duration_ms") {
                put("type", "integer")
                put("description", "Tap duration between 40 and 600 ms (default: 80).")
                put("minimum", 40)
                put("maximum", 600)
            }
        }
        put(
            "description",
            "Provide either text-based arguments (text, optional exact/occurrence) OR coordinates (x and y)."
        )
        put("examples", buildJsonArray {
            add(buildJsonObject {
                put("text", "Allow")
            })
            add(buildJsonObject {
                put("text", "Send")
                put("occurrence", 2)
            })
            add(buildJsonObject {
                put("x", 540)
                put("y", 1780)
            })
        })
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        if (!PhoneAccessibilityService.isEnabled()) {
            return resultError(
                "Accessibility automation is disabled. Enable PocketMCP Accessibility Service in Android Accessibility settings."
            )
        }

        val text = argString(args, "text")?.trim().orEmpty()
        val x = argInt(args, "x")
        val y = argInt(args, "y")
        val durationMs = (argInt(args, "duration_ms") ?: 80).toLong().coerceIn(40L, 600L)

        val hasCoordinates = x != null || y != null
        val hasText = text.isNotBlank()

        if (hasCoordinates && hasText) {
            return resultError("Use either text-based tap or coordinate-based tap, not both in the same call.")
        }

        if (!hasCoordinates && !hasText) {
            return resultError("Missing target. Provide text, or provide both x and y coordinates.")
        }

        if (hasCoordinates) {
            if (x == null || y == null) {
                return resultError("Coordinate tap requires both x and y.")
            }

            val tapped = PhoneAccessibilityService.runTap(x, y, durationMs)
            if (!tapped) {
                return resultError("Tap gesture failed at coordinates ($x, $y).")
            }

            return resultJson(
                buildJsonObject {
                    put("mode", "coordinates")
                    put("x", x)
                    put("y", y)
                    put("duration_ms", durationMs)
                    put("success", true)
                }
            )
        }

        val exact = argBoolean(args, "exact") ?: false
        val occurrence = (argInt(args, "occurrence") ?: 1).coerceAtLeast(1)
        val result = PhoneAccessibilityService.tapVisibleNodeByText(text, exact, occurrence)
        if (!result.success) {
            return resultError(
                result.error ?: "Failed to tap '$text'. Use screen_state to inspect visible text and try again."
            )
        }

        return resultJson(
            buildJsonObject {
                put("mode", "text")
                put("query", text)
                put("exact", exact)
                put("occurrence", occurrence)
                put("success", true)
                put("package_name", result.packageName ?: "")
                put("matched_text", result.matchedText ?: "")
                put("matched_description", result.matchedDescription ?: "")
            }
        )
    }
}
