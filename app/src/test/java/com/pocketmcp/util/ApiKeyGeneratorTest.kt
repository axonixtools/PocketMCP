package com.pocketmcp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyGeneratorTest {

    @Test
    fun `generates keys of the requested length`() {
        assertEquals(32, ApiKeyGenerator.generate().length)
        assertEquals(8, ApiKeyGenerator.generate(8).length)
        assertEquals(64, ApiKeyGenerator.generate(64).length)
    }

    @Test
    fun `generates alphanumeric keys only`() {
        val key = ApiKeyGenerator.generate(200)
        assertTrue(key.all { it.isLetterOrDigit() })
    }

    @Test
    fun `generates unique keys`() {
        assertNotEquals(ApiKeyGenerator.generate(), ApiKeyGenerator.generate())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-positive length`() {
        ApiKeyGenerator.generate(0)
    }
}
