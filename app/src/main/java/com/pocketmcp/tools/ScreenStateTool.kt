package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ScreenStateTool : McpToolHandler {
    override val name: String = "screen_state"
    override val description: String =
        "Summarize the current screen using accessibility: foreground app and visible text/content descriptions."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("max_nodes") {
                put("type", "integer")
                put("description", "Maximum number of text nodes to return, 5-200 (default: 80).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        if (!PhoneAccessibilityService.isEnabled()) {
            return resultError(
                "Accessibility automation is disabled. Enable PocketMCP Accessibility Service in Android Accessibility settings."
            )
        }

        val maxNodes = (argInt(args, "max_nodes") ?: 80).coerceIn(5, 200)
        val snapshot = PhoneAccessibilityService.captureScreenSnapshot(maxNodes)
            ?: return resultError(
                "No active window is available from accessibility. Unlock your phone and open an app first."
            )

        val payload = buildJsonObject {
            put("foreground_package", snapshot.packageName)
            put("root_class", snapshot.rootClassName)
            put("node_count", snapshot.nodes.size)
            put(
                "nodes",
                buildJsonArray {
                    snapshot.nodes.forEach { node ->
                        add(
                            buildJsonObject {
                                put("text", node.text)
                                put("content_description", node.contentDescription)
                                put("class_name", node.className)
                                put("clickable", node.clickable)
                                putJsonObject("bounds") {
                                    put("left", node.bounds.left)
                                    put("top", node.bounds.top)
                                    put("right", node.bounds.right)
                                    put("bottom", node.bounds.bottom)
                                }
                            }
                        )
                    }
                }
            )
        }

        return resultJson(payload)
    }
}

