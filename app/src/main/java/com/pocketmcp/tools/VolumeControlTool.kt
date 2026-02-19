package com.pocketmcp.tools

import android.content.Context
import android.media.AudioManager
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class VolumeControlTool : McpToolHandler {
    override val name = "volume_control"
    override val description = "Read or change Android stream volume levels."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "One of: status, set, up, down, mute, unmute.")
            }
            putJsonObject("stream") {
                put("type", "string")
                put("description", "One of: music, ring, alarm, notification, system, voice_call. Default: music.")
            }
            putJsonObject("level") {
                put("type", "integer")
                put("description", "Required for action=set. Target level in stream range.")
            }
            putJsonObject("steps") {
                put("type", "integer")
                put("description", "For up/down. Number of increments (default: 1).")
            }
            putJsonObject("show_ui") {
                put("type", "boolean")
                put("description", "Whether to show Android volume UI (default: false).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val action = (argString(args, "action") ?: "status").trim().lowercase()
        val stream = mapStream((argString(args, "stream") ?: "music").trim().lowercase())
            ?: return resultError("Invalid stream. Use music, ring, alarm, notification, system, or voice_call.")

        val showUi = argBoolean(args, "show_ui") == true
        val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0

        return runCatching {
            when (action) {
                "status" -> statusPayload(audio, stream, "status")
                "set" -> {
                    val max = audio.getStreamMaxVolume(stream)
                    val min = streamMinVolume(audio, stream)
                    val requestedLevel = argInt(args, "level")
                        ?: return resultError("Missing required argument level for action=set.")
                    val clamped = requestedLevel.coerceIn(min, max)
                    audio.setStreamVolume(stream, clamped, flags)
                    statusPayload(audio, stream, "set")
                }
                "up" -> {
                    val steps = (argInt(args, "steps") ?: 1).coerceIn(1, 20)
                    repeat(steps) {
                        audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
                    }
                    statusPayload(audio, stream, "up")
                }
                "down" -> {
                    val steps = (argInt(args, "steps") ?: 1).coerceIn(1, 20)
                    repeat(steps) {
                        audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
                    }
                    statusPayload(audio, stream, "down")
                }
                "mute" -> {
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
                    statusPayload(audio, stream, "mute")
                }
                "unmute" -> {
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
                    statusPayload(audio, stream, "unmute")
                }
                else -> resultError("Invalid action '$action'. Use status, set, up, down, mute, or unmute.")
            }
        }.getOrElse { error ->
            resultError("Volume action failed: ${error.message ?: "unknown error"}")
        }
    }

    private fun statusPayload(audio: AudioManager, stream: Int, action: String): McpToolCallResult {
        val payload = buildJsonObject {
            put("action", action)
            put("stream", streamName(stream))
            put("current", audio.getStreamVolume(stream))
            put("min", streamMinVolume(audio, stream))
            put("max", audio.getStreamMaxVolume(stream))
            put("is_muted", audio.isStreamMute(stream))
        }
        return resultJson(payload)
    }

    private fun mapStream(name: String): Int? {
        return when (name) {
            "music" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "system" -> AudioManager.STREAM_SYSTEM
            "voice_call" -> AudioManager.STREAM_VOICE_CALL
            else -> null
        }
    }

    private fun streamName(stream: Int): String {
        return when (stream) {
            AudioManager.STREAM_MUSIC -> "music"
            AudioManager.STREAM_RING -> "ring"
            AudioManager.STREAM_ALARM -> "alarm"
            AudioManager.STREAM_NOTIFICATION -> "notification"
            AudioManager.STREAM_SYSTEM -> "system"
            AudioManager.STREAM_VOICE_CALL -> "voice_call"
            else -> "unknown"
        }
    }

    private fun streamMinVolume(audio: AudioManager, stream: Int): Int {
        return runCatching { audio.getStreamMinVolume(stream) }.getOrDefault(0)
    }
}
