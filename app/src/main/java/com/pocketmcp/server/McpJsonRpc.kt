package com.pocketmcp.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
public data class JsonRpcRequest(
    public val jsonrpc: String = "2.0",
    public val method: String,
    public val params: JsonElement? = null,
    public val id: JsonElement? = null
)

@Serializable
public data class JsonRpcResponse(
    public val jsonrpc: String = "2.0",
    public val result: JsonElement? = null,
    public val error: JsonRpcError? = null,
    public val id: JsonElement? = null
)

@Serializable
public data class JsonRpcError(
    public val code: Int,
    public val message: String,
    public val data: JsonElement? = null
)

public object McpErrorCodes {
    public const val PARSE_ERROR: Int = -32700
    public const val INVALID_REQUEST: Int = -32600
    public const val METHOD_NOT_FOUND: Int = -32601
    public const val INVALID_PARAMS: Int = -32602
    public const val INTERNAL_ERROR: Int = -32603
    public const val TOOL_NOT_FOUND: Int = -32001
}

@Serializable
public data class McpInitializeResult(
    public val protocolVersion: String,
    public val serverInfo: McpServerInfo,
    public val capabilities: McpCapabilities
)

@Serializable
public data class McpServerInfo(
    public val name: String,
    public val version: String
)

@Serializable
public data class McpCapabilities(
    public val tools: JsonElement
)

@Serializable
public data class McpToolInfo(
    public val name: String,
    public val description: String,
    public val inputSchema: JsonObject
)

@Serializable
public data class McpToolsListResult(
    public val tools: List<McpToolInfo>
)

@Serializable
public data class McpToolCallResult(
    public val content: List<McpContent>,
    public val isError: Boolean = false,
    @Transient
    public val toolError: String? = null
)

@Serializable
public data class McpContent(
    public val type: String,
    public val text: String? = null,
    public val source: String? = null
)

private val jsonRpcEncoder = Json { ignoreUnknownKeys = true; prettyPrint = false }

/**
 * Encodes a [JsonRpcResponse] into the wire format used by every transport
 * (HTTP POST, SSE, WebSocket). Absent fields are omitted entirely.
 */
internal fun encodeJsonRpcResponse(response: JsonRpcResponse): String {
    val payload = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        response.result?.let { put("result", it) }
        response.error?.let {
            put("error", jsonRpcEncoder.encodeToJsonElement(JsonRpcError.serializer(), it))
        }
        response.id?.let { put("id", it) }
    }
    return payload.toString()
}
