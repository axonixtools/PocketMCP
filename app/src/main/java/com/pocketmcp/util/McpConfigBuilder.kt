package com.pocketmcp.util

/**
 * Builds the `mcpServers` JSON snippet shown in the app's Quick Connect
 * section and used as a copy-paste MCP client config.
 */
public object McpConfigBuilder {
    public fun build(endpoint: String, apiKey: String): String {
        val escapedApiKey = apiKey.replace("\"", "\\\"")
        val headersBlock = if (apiKey.isBlank()) {
            ""
        } else {
            """
        "headers": {
          "X-API-Key": "$escapedApiKey"
        }
""".trimIndent()
        }

        return if (headersBlock.isBlank()) {
            """
{
  "mcpServers": {
    "pocketmcp": {
      "type": "http",
      "url": "$endpoint"
    }
  }
}
""".trimIndent()
        } else {
            """
{
  "mcpServers": {
    "pocketmcp": {
      "type": "http",
      "url": "$endpoint",
$headersBlock
    }
  }
}
""".trimIndent()
        }
    }
}
