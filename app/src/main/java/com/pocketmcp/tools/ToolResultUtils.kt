package com.pocketmcp.tools

import com.pocketmcp.server.McpContent
import com.pocketmcp.server.McpToolCallResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val toolJson = Json { prettyPrint = false }

internal fun resultText(text: String): McpToolCallResult {
    return McpToolCallResult(content = listOf(McpContent(type = "text", text = text)))
}

internal fun resultJson(value: JsonElement): McpToolCallResult {
    return resultText(toolJson.encodeToString(JsonElement.serializer(), value))
}

internal fun resultError(message: String): McpToolCallResult {
    return McpToolCallResult(
        content = listOf(McpContent(type = "text", text = message)),
        isError = true,
        toolError = message
    )
}

internal fun argString(args: JsonObject?, key: String): String? {
    return args?.get(key)?.jsonPrimitive?.contentOrNull
}

internal fun argInt(args: JsonObject?, key: String): Int? {
    return args?.get(key)?.jsonPrimitive?.intOrNull
}

internal fun argDouble(args: JsonObject?, key: String): Double? {
    return args?.get(key)?.jsonPrimitive?.doubleOrNull
}

internal fun argBoolean(args: JsonObject?, key: String): Boolean? {
    return args?.get(key)?.jsonPrimitive?.booleanOrNull
}
