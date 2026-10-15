package com.pocketmcp.agent.memory

import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

public class ExecutionTraceStore(
    private val context: Context
) {
    public fun writeTrace(command: String, success: Boolean, stepMessages: List<String>): Unit {
        runCatching {
            val dir = File(context.filesDir, "agent-traces")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "trace-${System.currentTimeMillis()}.json")
            val payload = buildJsonObject {
                put("timestamp", System.currentTimeMillis())
                put("command", command)
                put("success", success)
                put("steps", buildJsonArray {
                    stepMessages.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
            file.writeText(payload.toString())
        }
    }
}
