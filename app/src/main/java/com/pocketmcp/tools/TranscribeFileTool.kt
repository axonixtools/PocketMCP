package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class TranscribeFileTool : McpToolHandler {
    override val name = "transcribe_file"
    override val description = "Transcribe a local audio file path into text."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("audio_path") {
                put("type", "string")
                put("description", "Absolute audio file path to transcribe.")
            }
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Alias for audio_path.")
            }
            putJsonObject("path") {
                put("type", "string")
                put("description", "Alias for audio_path.")
            }
            putJsonObject("timeout_seconds") {
                put("type", "integer")
                put("description", "Max wait for transcription result, 5-600 seconds.")
            }
            putJsonObject("language_tag") {
                put("type", "string")
                put("description", "BCP-47 language tag, e.g. en-US, ur-PK.")
            }
            putJsonObject("prefer_offline") {
                put("type", "boolean")
                put("description", "Prefer offline recognition when available (default: true).")
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("description", "Maximum alternatives to return, 1-5 (default: 3).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val delegatedArgs = buildJsonObject {
            put("action", "transcribe_file")
            args?.forEach { (key, value) ->
                put(key, value)
            }
        }
        return TranscribeAudioTool().execute(delegatedArgs, context)
    }
}
