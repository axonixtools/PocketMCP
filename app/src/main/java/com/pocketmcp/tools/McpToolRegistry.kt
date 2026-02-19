package com.pocketmcp.tools

import com.pocketmcp.server.McpToolHandler
import java.util.concurrent.ConcurrentHashMap

class McpToolRegistry {
    private val tools = ConcurrentHashMap<String, McpToolHandler>()

    fun register(tool: McpToolHandler) {
        if (tools.containsKey(tool.name)) {
            throw IllegalArgumentException("Tool with name ${tool.name} is already registered.")
        }
        tools[tool.name] = tool
    }

    fun get(name: String): McpToolHandler? {
        return tools[name]
    }

    fun getAll(): List<McpToolHandler> {
        return tools.values.sortedBy { it.name }
    }
}
