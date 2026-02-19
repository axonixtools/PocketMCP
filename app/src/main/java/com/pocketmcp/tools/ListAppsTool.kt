package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ListAppsTool : McpToolHandler {
    override val name = "list_apps"
    override val description = "List launchable installed apps, with optional search query."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Optional app-name/package search query.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Maximum number of apps to return, 1-300 (default: 50).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val query = argString(args, "query")?.trim().orEmpty()
        val limit = (argInt(args, "limit") ?: 50).coerceIn(1, 300)
        val allApps = getLaunchableApps(context)

        val selected = if (query.isBlank()) {
            allApps.take(limit)
        } else {
            searchLaunchableApps(allApps, query, limit)
        }

        val payload = buildJsonObject {
            put("query", query)
            put("count", selected.size)
            put("total_launchable", allApps.size)
            put("apps", buildJsonArray {
                selected.forEach { app ->
                    add(
                        buildJsonObject {
                            put("app_name", app.appName)
                            put("package_name", app.packageName)
                        }
                    )
                }
            })
        }
        return resultJson(payload)
    }
}
