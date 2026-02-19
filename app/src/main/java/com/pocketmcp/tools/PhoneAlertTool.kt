package com.pocketmcp.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class PhoneAlertTool : McpToolHandler {
    override val name = "phone_alert"
    override val description = "Ring and/or vibrate the phone so you can quickly find it."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "One of: ring, vibrate, both, stop, status. Default: both.")
            }
            putJsonObject("duration_seconds") {
                put("type", "integer")
                put("description", "How long to alert for ring/vibrate/both (default: 10, max: 120).")
            }
            putJsonObject("boost_ring_volume") {
                put("type", "boolean")
                put("description", "Temporarily set ring stream to max while alerting (default: true).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = (argString(args, "action") ?: "both").trim().lowercase()
        return when (action) {
            "status" -> resultJson(statusPayload())
            "stop" -> resultJson(stopAlert(context.applicationContext, "manual_stop"))
            "ring", "vibrate", "both" -> {
                val durationSeconds = (argInt(args, "duration_seconds") ?: 10).coerceIn(1, 120)
                val durationMs = durationSeconds * 1_000L
                val boostRingVolume = argBoolean(args, "boost_ring_volume") ?: true

                startAlert(
                    context = context.applicationContext,
                    action = action,
                    durationMs = durationMs,
                    boostRingVolume = boostRingVolume
                )
            }

            else -> resultError("Invalid action '$action'. Use ring, vibrate, both, stop, or status.")
        }
    }

    private fun startAlert(
        context: Context,
        action: String,
        durationMs: Long,
        boostRingVolume: Boolean
    ): McpToolCallResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notes = mutableListOf<String>()

        val startState = synchronized(stateLock) {
            stopLocked(audioManager, reason = "replaced")

            var ringStarted = false
            var vibrateStarted = false

            if (action == "ring" || action == "both") {
                ringStarted = startRingtone(context, audioManager, boostRingVolume, notes)
            }

            if (action == "vibrate" || action == "both") {
                vibrateStarted = startVibration(context, notes)
            }

            if (!ringStarted && !vibrateStarted) {
                return@synchronized AlertStartState(
                    ringStarted = false,
                    vibrateStarted = false,
                    mode = "none",
                    error = if (notes.isEmpty()) {
                        "Failed to start alert."
                    } else {
                        "Failed to start alert: ${notes.joinToString("; ")}"
                    }
                )
            }

            val mode = when {
                ringStarted && vibrateStarted -> "both"
                ringStarted -> "ring"
                else -> "vibrate"
            }
            alertMode = mode
            alertUntilMs = System.currentTimeMillis() + durationMs
            scheduleAutoStop(context, durationMs)
            AlertStartState(
                ringStarted = ringStarted,
                vibrateStarted = vibrateStarted,
                mode = mode,
                error = null
            )
        }

        if (startState.error != null) {
            return resultError(startState.error)
        }

        val payload = buildJsonObject {
            put("action", action)
            put("mode", startState.mode)
            put("ringing", startState.ringStarted)
            put("vibrating", startState.vibrateStarted)
            put("running", true)
            put("duration_ms", durationMs)
            put("ends_at_ms", alertUntilMs)
            if (notes.isNotEmpty()) {
                putJsonArray("notes") {
                    notes.forEach { note -> add(JsonPrimitive(note)) }
                }
            }
        }
        return resultJson(payload)
    }

    private fun startRingtone(
        context: Context,
        audioManager: AudioManager,
        boostRingVolume: Boolean,
        notes: MutableList<String>
    ): Boolean {
        return runCatching {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: throw IllegalStateException("No default ringtone/alarm/notification sound configured.")

            val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
                ?: throw IllegalStateException("Unable to create ringtone player.")

            if (boostRingVolume) {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                if (current < max) {
                    originalRingVolume = current
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, max, 0)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                ringtone.streamType = AudioManager.STREAM_RING
            }

            ringtone.play()
            activeRingtone = ringtone
            true
        }.getOrElse { error ->
            notes += "Ringtone failed: ${error.message ?: "unknown error"}"
            false
        }
    }

    private fun startVibration(context: Context, notes: MutableList<String>): Boolean {
        val vibrator = resolveVibrator(context)
        if (vibrator == null || !vibrator.hasVibrator()) {
            notes += "Device does not support vibration."
            return false
        }

        return runCatching {
            val pattern = longArrayOf(0, 350, 160, 350)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            activeVibrator = vibrator
            true
        }.getOrElse { error ->
            notes += "Vibration failed: ${error.message ?: "unknown error"}"
            false
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun scheduleAutoStop(context: Context, durationMs: Long) {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(durationMs)
            synchronized(stateLock) {
                if (isRunningLocked()) {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    stopLocked(audioManager, reason = "duration_elapsed")
                }
            }
        }
    }

    private fun stopAlert(context: Context, reason: String): JsonObject {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return synchronized(stateLock) {
            stopLocked(audioManager, reason)
        }
    }

    private fun stopLocked(audioManager: AudioManager, reason: String): JsonObject {
        val wasRunning = isRunningLocked()

        runCatching { activeRingtone?.stop() }
        activeRingtone = null

        runCatching { activeVibrator?.cancel() }
        activeVibrator = null

        originalRingVolume?.let { previous ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_RING, previous, 0) }
        }
        originalRingVolume = null

        autoStopJob?.cancel()
        autoStopJob = null

        alertMode = "none"
        alertUntilMs = 0L

        return buildJsonObject {
            put("action", "stop")
            put("stopped", wasRunning)
            put("reason", reason)
            put("running", false)
        }
    }

    private fun statusPayload(): JsonObject {
        val now = System.currentTimeMillis()
        return synchronized(stateLock) {
            val ringing = activeRingtone?.isPlaying == true
            val vibrating = activeVibrator != null
            val running = ringing || vibrating
            val remaining = if (running) (alertUntilMs - now).coerceAtLeast(0L) else 0L

            buildJsonObject {
                put("action", "status")
                put("running", running)
                put("mode", if (running) alertMode else "none")
                put("ringing", ringing)
                put("vibrating", vibrating)
                put("ends_at_ms", if (running) alertUntilMs else 0L)
                put("remaining_ms", remaining)
                put("ring_volume_boosted", originalRingVolume != null)
            }
        }
    }

    private fun isRunningLocked(): Boolean {
        return activeRingtone?.isPlaying == true || activeVibrator != null
    }

    private data class AlertStartState(
        val ringStarted: Boolean,
        val vibrateStarted: Boolean,
        val mode: String,
        val error: String?
    )

    private companion object {
        val stateLock = Any()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        var activeRingtone: Ringtone? = null
        var activeVibrator: Vibrator? = null
        var originalRingVolume: Int? = null
        var alertMode: String = "none"
        var alertUntilMs: Long = 0L
        var autoStopJob: Job? = null
    }
}
