package com.pocketmcp.server

import android.content.Context
import kotlinx.serialization.json.JsonObject

public interface McpToolHandler {
    public val name: String
    public val description: String
    public val inputSchema: JsonObject
    public suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult
}
