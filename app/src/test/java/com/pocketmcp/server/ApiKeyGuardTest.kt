package com.pocketmcp.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyGuardTest {

    @Test
    fun `guard is disabled when no key is configured`() {
        val guard = ApiKeyGuard(expectedKey = null)
        assertFalse(guard.enabled)
        assertTrue(guard.authorize(null))
        assertTrue(guard.authorize("anything"))
    }

    @Test
    fun `guard is disabled when key is blank`() {
        val guard = ApiKeyGuard(expectedKey = "   ")
        assertFalse(guard.enabled)
    }

    @Test
    fun `correct key is authorized`() {
        val guard = ApiKeyGuard(expectedKey = "secret")
        assertTrue(guard.enabled)
        assertTrue(guard.authorize("secret"))
    }

    @Test
    fun `wrong or missing key is rejected`() {
        val guard = ApiKeyGuard(expectedKey = "secret")
        assertFalse(guard.authorize("wrong"))
        assertFalse(guard.authorize(null))
        assertFalse(guard.authorize(""))
    }

    @Test
    fun `host is throttled after too many failures`() {
        val guard = ApiKeyGuard(expectedKey = "secret", maxFailures = 3, failureWindowMs = 60_000)
        assertFalse(guard.isThrottled("192.0.2.5", nowMs = 1_000))
        repeat(3) { guard.recordFailure("192.0.2.5", nowMs = 1_000) }
        assertTrue(guard.isThrottled("192.0.2.5", nowMs = 1_000))
        assertFalse(guard.isThrottled("192.0.2.6", nowMs = 1_000))
    }

    @Test
    fun `throttle expires after the failure window`() {
        val guard = ApiKeyGuard(expectedKey = "secret", maxFailures = 2, failureWindowMs = 10_000)
        guard.recordFailure("192.0.2.5", nowMs = 0)
        guard.recordFailure("192.0.2.5", nowMs = 0)
        assertTrue(guard.isThrottled("192.0.2.5", nowMs = 5_000))
        assertFalse(guard.isThrottled("192.0.2.5", nowMs = 10_001))
    }

    @Test
    fun `successful auth clears recorded failures`() {
        val guard = ApiKeyGuard(expectedKey = "secret", maxFailures = 2, failureWindowMs = 60_000)
        guard.recordFailure("192.0.2.5", nowMs = 0)
        guard.recordFailure("192.0.2.5", nowMs = 0)
        assertTrue(guard.isThrottled("192.0.2.5", nowMs = 0))
        guard.recordSuccess("192.0.2.5")
        assertFalse(guard.isThrottled("192.0.2.5", nowMs = 0))
    }

    @Test
    fun `failures do not accumulate when the guard is disabled`() {
        val guard = ApiKeyGuard(expectedKey = null, maxFailures = 1)
        repeat(5) { guard.recordFailure("192.0.2.5") }
        assertFalse(guard.isThrottled("192.0.2.5"))
    }
}
