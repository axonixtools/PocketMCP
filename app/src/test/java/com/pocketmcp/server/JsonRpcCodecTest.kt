package com.pocketmcp.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JsonRpcCodecTest {

    @Test
    fun `result response includes jsonrpc version, result and id`() {
        val response = JsonRpcResponse(
            result = JsonPrimitive("ok"),
            id = JsonPrimitive(7)
        )
        val payload = Json.parseToJsonElement(encodeJsonRpcResponse(response)).jsonObject
        assertEquals("2.0", payload.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals("ok", payload.getValue("result").jsonPrimitive.content)
        assertEquals(7, payload.getValue("id").jsonPrimitive.int)
        assertFalse("error" in payload)
    }

    @Test
    fun `error response includes a structured error object`() {
        val response = JsonRpcResponse(
            error = JsonRpcError(code = McpErrorCodes.TOOL_NOT_FOUND, message = "Tool not found"),
            id = JsonPrimitive("abc")
        )
        val payload = Json.parseToJsonElement(encodeJsonRpcResponse(response)).jsonObject
        val error = payload.getValue("error").jsonObject
        assertEquals(McpErrorCodes.TOOL_NOT_FOUND, error.getValue("code").jsonPrimitive.int)
        assertEquals("Tool not found", error.getValue("message").jsonPrimitive.content)
        assertFalse("result" in payload)
    }

    @Test
    fun `response without id omits the field`() {
        val response = JsonRpcResponse(result = buildJsonObject { })
        val payload = Json.parseToJsonElement(encodeJsonRpcResponse(response)).jsonObject
        assertEquals("2.0", payload.getValue("jsonrpc").jsonPrimitive.content)
        assertFalse("id" in payload)
    }
}
