package com.pocketmcp.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Central API key policy for the MCP server.
 *
 * Behavior:
 * - When no API key is configured the guard is disabled and every request is authorized.
 * - When a key is configured, requests must provide it via the `X-API-Key` header,
 *   `Authorization: Bearer <key>`, or the `key` / `api_key` query parameter. The query
 *   parameter exists for WebSocket and SSE handshakes that cannot attach custom headers.
 * - Failed attempts are throttled per remote host to slow down brute-force attempts.
 */
internal class ApiKeyGuard(
    private val expectedKey: String?,
    private val maxFailures: Int = MAX_FAILURES,
    private val failureWindowMs: Long = FAILURE_WINDOW_MS,
) {
    private class FailureWindow {
        var count: Int = 0
        var startMs: Long = 0L
    }

    private val failures = ConcurrentHashMap<String, FailureWindow>()

    val enabled: Boolean
        get() = !expectedKey.isNullOrBlank()

    fun authorize(providedKey: String?): Boolean = !enabled || providedKey == expectedKey

    fun isThrottled(host: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return false
        val window = failures[host] ?: return false
        synchronized(window) {
            if (nowMs - window.startMs > failureWindowMs) {
                failures.remove(host)
                return false
            }
            return window.count >= maxFailures
        }
    }

    fun recordFailure(host: String, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled) return
        val window = failures.computeIfAbsent(host) {
            FailureWindow().also { it.startMs = nowMs }
        }
        synchronized(window) {
            if (nowMs - window.startMs > failureWindowMs) {
                window.count = 0
                window.startMs = nowMs
            }
            window.count += 1
        }
    }

    fun recordSuccess(host: String) {
        failures.remove(host)
    }

    private companion object {
        const val MAX_FAILURES: Int = 10
        const val FAILURE_WINDOW_MS: Long = 60_000L
    }
}
