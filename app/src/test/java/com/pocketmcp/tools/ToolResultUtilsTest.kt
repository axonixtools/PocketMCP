package com.pocketmcp.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultUtilsTest {

    @Test
    fun `resultText wraps text content`() {
        val result = resultText("hello")
        assertEquals("hello", result.content.single().text)
        assertEquals("text", result.content.single().type)
        assertTrue(!result.isError)
    }

    @Test
    fun `resultError marks the result as an error`() {
        val result = resultError("boom")
        assertTrue(result.isError)
        assertEquals("boom", result.toolError)
        assertEquals("boom", result.content.single().text)
    }

    @Test
    fun `resultJson serializes the payload into text content`() {
        val result = resultJson(buildJsonObject { put("ok", true) })
        val payload = Json.parseToJsonElement(result.content.single().text!!).jsonObject
        assertTrue(payload.getValue("ok").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `argString reads a string and tolerates missing keys`() {
        val args = buildJsonObject { put("name", "pocket") }
        assertEquals("pocket", argString(args, "name"))
        assertNull(argString(args, "missing"))
        assertNull(argString(null, "name"))
    }

    @Test
    fun `argInt parses numbers and rejects non-numeric values`() {
        val args = buildJsonObject { put("count", 3) }
        assertEquals(3, argInt(args, "count"))
        assertNull(argInt(args, "missing"))
    }

    @Test
    fun `argBoolean only accepts real booleans`() {
        val bad = buildJsonObject { put("enabled", "not-a-bool") }
        assertNull(argBoolean(bad, "enabled"))
        val good = buildJsonObject { put("enabled", true) }
        assertEquals(true, argBoolean(good, "enabled"))
    }

    @Test
    fun `argStringList trims entries and drops blanks`() {
        val args = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    add(JsonPrimitive(" a "))
                    add(JsonPrimitive("   "))
                    add(JsonPrimitive("b"))
                }
            )
        }
        assertEquals(listOf("a", "b"), argStringList(args, "items"))
    }

    @Test
    fun `argJsonObject and argJsonArray return null on type mismatch`() {
        val args = buildJsonObject {
            put("obj", buildJsonObject { put("k", "v") })
            put("arr", buildJsonArray { add(1) })
            put("scalar", "x")
        }
        assertEquals("v", argJsonObject(args, "obj")?.get("k")?.jsonPrimitive?.content)
        assertEquals(1, argJsonArray(args, "arr")?.size)
        assertNull(argJsonObject(args, "scalar"))
        assertNull(argJsonArray(args, "scalar"))
    }
}
