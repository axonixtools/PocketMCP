package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpToolRegistryTest {

    @Test
    fun `tools can be registered and retrieved by name`() {
        val registry = McpToolRegistry()
        val tool = FakeTool("alpha")
        registry.register(tool)

        assertSame(tool, registry.get("alpha"))
        assertNull(registry.get("missing"))
    }

    @Test
    fun `getAll returns tools sorted by name`() {
        val registry = McpToolRegistry()
        registry.register(FakeTool("zulu"))
        registry.register(FakeTool("alpha"))
        registry.register(FakeTool("mike"))

        assertEquals(listOf("alpha", "mike", "zulu"), registry.getAll().map { it.name })
    }

    @Test
    fun `duplicate tool names are rejected`() {
        val registry = McpToolRegistry()
        registry.register(FakeTool("alpha"))

        try {
            registry.register(FakeTool("alpha"))
            fail("Expected IllegalArgumentException for duplicate tool name")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("alpha"))
        }
    }

    private class FakeTool(override val name: String) : McpToolHandler {
        override val description: String = "fake tool"
        override val inputSchema: JsonObject = buildJsonObject { }

        override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult =
            resultText("ok")
    }
}
