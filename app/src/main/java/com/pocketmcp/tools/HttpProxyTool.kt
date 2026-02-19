package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI

private const val DEFAULT_MAX_BODY_CHARS = 20_000

class HttpProxyTool : McpToolHandler {
    override val name = "http_request"
    override val description = "Perform an outbound HTTP request from the phone."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "http:// or https:// URL.")
            }
            putJsonObject("method") {
                put("type", "string")
                put("description", "HTTP method. Default: GET.")
            }
            putJsonObject("headers") {
                put("type", "object")
                put("description", "Optional request headers.")
            }
            putJsonObject("body") {
                put("type", "string")
                put("description", "Optional request body.")
            }
            putJsonObject("timeout_seconds") {
                put("type", "integer")
                put("description", "Timeout in seconds, 1-30 (default: 15).")
            }
            putJsonObject("max_bytes") {
                put("type", "integer")
                put("description", "Maximum response body bytes to return.")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val url = argString(args, "url")?.trim()
        if (url.isNullOrBlank()) {
            return resultError("Missing required argument: url")
        }
        val parsedUri = runCatching { URI(url) }.getOrNull()
            ?: return resultError("Invalid URL")
        val scheme = parsedUri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return resultError("Only http and https URLs are allowed.")
        }

        val host = parsedUri.host?.lowercase().orEmpty()
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") {
            return resultError("Loopback URLs are blocked for safety.")
        }

        val methodText = argString(args, "method")?.uppercase() ?: "GET"
        val method = runCatching { HttpMethod.parse(methodText) }.getOrNull()
            ?: return resultError("Invalid HTTP method: $methodText")

        val headersArg = args?.get("headers") as? JsonObject
        val requestHeaders = headersArg
            ?.mapNotNull { (key, value) ->
                value.toString().trim('"').takeIf { it.isNotBlank() }?.let { key to it }
            }
            ?.toMap()
            ?: emptyMap()

        val body = argString(args, "body")
        val timeoutSeconds = (argInt(args, "timeout_seconds") ?: 15).coerceIn(1, 30)
        val maxChars = (argInt(args, "max_bytes") ?: DEFAULT_MAX_BODY_CHARS).coerceIn(256, 200_000)

        return runCatching {
            val response = client.request(url) {
                this.method = method
                timeout {
                    requestTimeoutMillis = timeoutSeconds * 1000L
                    connectTimeoutMillis = timeoutSeconds * 1000L
                    socketTimeoutMillis = timeoutSeconds * 1000L
                }
                requestHeaders.forEach { (key, value) -> this.headers.append(key, value) }
                if (!body.isNullOrEmpty() && method != HttpMethod.Get && method != HttpMethod.Head) {
                    setBody(body)
                }
            }
            val responseBody = response.bodyAsText()
            val payload = buildJsonObject {
                put("url", url)
                put("method", method.value)
                put("status", response.status.value)
                put("status_text", response.status.description)
                put("headers", buildJsonObject {
                    response.headers.entries().forEach { entry ->
                        put(entry.key, entry.value.joinToString(","))
                    }
                })
                put("body", responseBody.take(maxChars))
                put("body_truncated", responseBody.length > maxChars)
            }
            resultJson(payload)
        }.getOrElse { error ->
            resultError("HTTP request failed: ${error.message ?: "unknown error"}")
        }
    }

    private companion object {
        val client = HttpClient(OkHttp) {
            install(HttpTimeout)
        }
    }
}
