package com.pocketmcp.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

class VoiceRecordTool : McpToolHandler {
    override val name = "voice_record"
    override val description = "Record voice notes from the phone microphone."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "One of: record, start, stop, status. Default: record.")
            }
            putJsonObject("duration_seconds") {
                put("type", "integer")
                put("description", "For action=record (required) or action=start (optional auto-stop). 1-300.")
            }
            putJsonObject("filename_prefix") {
                put("type", "string")
                put("description", "Optional filename prefix (letters/numbers/_/- only).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = (argString(args, "action") ?: "record").trim().lowercase()
        val appContext = context.applicationContext

        return when (action) {
            "status" -> resultJson(statusPayload())
            "start" -> startAction(args, appContext)
            "stop" -> stopAction("manual_stop")
            "record" -> recordAction(args, appContext)
            else -> resultError("Invalid action '$action'. Use record, start, stop, or status.")
        }
    }

    private suspend fun recordAction(args: JsonObject?, context: Context): McpToolCallResult {
        val durationSeconds = (argInt(args, "duration_seconds") ?: 10).coerceIn(1, 300)
        val startResult = startRecording(args, context)
        if (startResult.error != null) {
            return resultError(startResult.error)
        }

        delay(durationSeconds * 1_000L)
        val stopResult = stopRecording("record_complete")
        if (stopResult.error != null) {
            return resultError(stopResult.error)
        }

        val finished = stopResult.snapshot ?: return resultError("Failed to stop recording.")
        val payload = buildJsonObject {
            put("action", "record")
            put("file_path", finished.file.absolutePath)
            put("file_name", finished.file.name)
            put("size_bytes", finished.file.length())
            put("duration_ms", finished.durationMs)
            put("duration_seconds", durationSeconds)
        }
        return resultJson(payload)
    }

    private fun startAction(args: JsonObject?, context: Context): McpToolCallResult {
        val autoStopSeconds = argInt(args, "duration_seconds")?.coerceIn(1, 300)
        val started = startRecording(args, context)
        if (started.error != null) {
            return resultError(started.error)
        }

        if (autoStopSeconds != null) {
            scheduleAutoStop(autoStopSeconds * 1_000L)
        }

        val snapshot = started.snapshot ?: return resultError("Failed to start recording.")
        val payload = buildJsonObject {
            put("action", "start")
            put("running", true)
            put("started_at_ms", snapshot.startedAtMs)
            put("file_path", snapshot.file.absolutePath)
            put("file_name", snapshot.file.name)
            put("auto_stop_seconds", autoStopSeconds ?: 0)
        }
        return resultJson(payload)
    }

    private fun stopAction(reason: String): McpToolCallResult {
        val stopped = stopRecording(reason)
        if (stopped.error != null) {
            return resultError(stopped.error)
        }

        val snapshot = stopped.snapshot ?: return resultError("Failed to stop recording.")
        val payload = buildJsonObject {
            put("action", "stop")
            put("reason", reason)
            put("running", false)
            put("file_path", snapshot.file.absolutePath)
            put("file_name", snapshot.file.name)
            put("size_bytes", snapshot.file.length())
            put("duration_ms", snapshot.durationMs)
        }
        return resultJson(payload)
    }

    private fun startRecording(args: JsonObject?, context: Context): OperationResult {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return OperationResult(
                error = "RECORD_AUDIO permission is not granted. Enable microphone access in app settings."
            )
        }

        return synchronized(stateLock) {
            if (activeRecorder != null || activeFile != null) {
                return@synchronized OperationResult(
                    error = "A recording is already in progress. Call voice_record with action=stop first."
                )
            }

            val outputFile = createOutputFile(context, args)
            val recorder = buildRecorder(context)

            runCatching {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(44_100)
                recorder.setOutputFile(outputFile.absolutePath)
                recorder.prepare()
                recorder.start()
            }.onFailure {
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
                runCatching { outputFile.delete() }
                return@synchronized OperationResult(
                    error = "Unable to start recording: ${it.message ?: "unknown error"}"
                )
            }

            autoStopJob?.cancel()
            autoStopJob = null
            val startedAt = System.currentTimeMillis()
            activeRecorder = recorder
            activeFile = outputFile
            startedAtMs = startedAt

            OperationResult(
                snapshot = RecordingSnapshot(
                    file = outputFile,
                    startedAtMs = startedAt,
                    durationMs = 0L
                )
            )
        }
    }

    private fun stopRecording(reason: String): OperationResult {
        return synchronized(stateLock) {
            val recorder = activeRecorder
                ?: return@synchronized OperationResult(
                    error = "No active recording. Call voice_record with action=start or action=record."
                )
            val file = activeFile
                ?: return@synchronized OperationResult(
                    error = "Recording state is inconsistent (missing output file)."
                )

            autoStopJob?.cancel()
            autoStopJob = null

            var stopFailed: Throwable? = null
            runCatching { recorder.stop() }
                .onFailure { stopFailed = it }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }

            val finishedAt = System.currentTimeMillis()
            val durationMs = (finishedAt - startedAtMs).coerceAtLeast(0L)

            activeRecorder = null
            activeFile = null
            startedAtMs = 0L

            if (stopFailed != null) {
                runCatching { file.delete() }
                return@synchronized OperationResult(
                    error = "Recording could not be finalized (${reason}): ${stopFailed?.message ?: "captured clip was discarded"}"
                )
            }

            OperationResult(
                snapshot = RecordingSnapshot(
                    file = file,
                    startedAtMs = finishedAt - durationMs,
                    durationMs = durationMs
                )
            )
        }
    }

    private fun scheduleAutoStop(durationMs: Long) {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(durationMs)
            stopRecording("auto_stop")
        }
    }

    private fun statusPayload(): JsonObject {
        return synchronized(stateLock) {
            val running = activeRecorder != null && activeFile != null
            val elapsed = if (running) {
                (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            } else {
                0L
            }

            buildJsonObject {
                put("action", "status")
                put("running", running)
                if (running) {
                    put("file_path", activeFile?.absolutePath ?: "")
                    put("file_name", activeFile?.name ?: "")
                    put("started_at_ms", startedAtMs)
                    put("elapsed_ms", elapsed)
                }
            }
        }
    }

    private fun createOutputFile(context: Context, args: JsonObject?): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val folder = File(root, "voice_recordings")
        if (!folder.exists()) {
            folder.mkdirs()
        }

        val requestedPrefix = argString(args, "filename_prefix")?.trim().orEmpty()
        val safePrefix = requestedPrefix
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifEmpty { "voice" }

        val stamp = System.currentTimeMillis()
        return File(folder, "${safePrefix}_${stamp}.m4a")
    }

    private fun buildRecorder(context: Context): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private data class RecordingSnapshot(
        val file: File,
        val startedAtMs: Long,
        val durationMs: Long
    )

    private data class OperationResult(
        val snapshot: RecordingSnapshot? = null,
        val error: String? = null
    )

    private companion object {
        val stateLock = Any()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        var activeRecorder: MediaRecorder? = null
        var activeFile: File? = null
        var startedAtMs: Long = 0L
        var autoStopJob: Job? = null
    }
}
