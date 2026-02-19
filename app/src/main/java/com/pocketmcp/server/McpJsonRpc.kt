package com.pocketmcp.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
    val id: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    val id: JsonElement? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val TOOL_NOT_FOUND = -32001
}

@Serializable
data class McpInitializeResult(
    val protocolVersion: String,
    val serverInfo: McpServerInfo,
    val capabilities: McpCapabilities
)

@Serializable
data class McpServerInfo(
    val name: String,
    val version: String
)

@Serializable
data class McpCapabilities(
    val tools: JsonElement
)

@Serializable
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class McpToolsListResult(
    val tools: List<McpToolInfo>
)

@Serializable
data class McpToolCallResult(
    val content: List<McpContent>,
    val isError: Boolean = false,
    val toolError: String? = null
)

@Serializable
data class McpContent(
    val type: String,
    val text: String? = null,
    val source: String? = null
)
