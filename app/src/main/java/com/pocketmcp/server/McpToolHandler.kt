package com.pocketmcp.server

import android.content.Context
import kotlinx.serialization.json.JsonObject

interface McpToolHandler {
    val name: String
    val description: String
    val inputSchema: JsonObject
    suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult
}
