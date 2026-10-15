package com.pocketmcp.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpConfigBuilderTest {

    @Test
    fun `config is valid json and points at the endpoint`() {
        val config = McpConfigBuilder.build("http://192.0.2.10:8080/mcp", "abc123")
        val server = Json.parseToJsonElement(config)
            .jsonObject.getValue("mcpServers").jsonObject.getValue("phone").jsonObject
        assertEquals("streamableHttp", server.getValue("type").jsonPrimitive.content)
        assertEquals("http://192.0.2.10:8080/mcp", server.getValue("url").jsonPrimitive.content)
        val headers = server.getValue("headers").jsonObject
        assertEquals("abc123", headers.getValue("X-API-Key").jsonPrimitive.content)
    }

    @Test
    fun `config without key omits the headers block`() {
        val config = McpConfigBuilder.build("http://192.0.2.2:8080/mcp", "")
        assertFalse(config.contains("headers"))
        val server = Json.parseToJsonElement(config)
            .jsonObject.getValue("mcpServers").jsonObject.getValue("phone").jsonObject
        assertFalse("headers" in server)
    }

    @Test
    fun `quotes in the key are escaped so the json stays valid`() {
        val config = McpConfigBuilder.build("http://192.0.2.2:8080/mcp", "we\"ird")
        val headers = Json.parseToJsonElement(config)
            .jsonObject.getValue("mcpServers").jsonObject.getValue("phone").jsonObject
            .getValue("headers").jsonObject
        assertEquals("we\"ird", headers.getValue("X-API-Key").jsonPrimitive.content)
    }
}
