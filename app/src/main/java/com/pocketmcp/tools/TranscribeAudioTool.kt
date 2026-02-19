package com.pocketmcp.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

class TranscribeAudioTool : McpToolHandler {
    override val name = "transcribe_audio"
    override val description = "Transcribe speech from microphone or from an audio file path into text for chat."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Action: 'listen' (default), 'transcribe_file', or 'status'")
            }
            putJsonObject("duration_seconds") {
                put("type", "integer")
                put("description", "Listening window for action=listen, 2-120 seconds (default: 10)")
            }
            putJsonObject("timeout_seconds") {
                put("type", "integer")
                put("description", "Max wait for file transcription result, 5-600 seconds.")
            }
            putJsonObject("language_tag") {
                put("type", "string")
                put("description", "BCP-47 language tag, e.g. en-US, ur-PK (default: device locale)")
            }
            putJsonObject("prefer_offline") {
                put("type", "boolean")
                put("description", "Prefer offline recognition when available (default: true)")
            }
            putJsonObject("partial_results") {
                put("type", "boolean")
                put("description", "Allow partial interim results while listening (default: true)")
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("description", "Maximum alternatives to return, 1-5 (default: 3)")
            }
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "Optional prompt hint shown by recognizer UI")
            }
            putJsonObject("audio_path") {
                put("type", "string")
                put("description", "Audio file path to transcribe (m4a/mp3/wav). If provided, action defaults to transcribe_file.")
            }
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Alias for audio_path.")
            }
            putJsonObject("path") {
                put("type", "string")
                put("description", "Alias for audio_path.")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val requestedAction = (argString(args, "action") ?: "listen").trim().lowercase()
        val audioPath = resolveAudioPath(args)
        val action = normalizeAction(requestedAction, audioPath)
        val appContext = context.applicationContext

        return when (action) {
            "status" -> resultJson(statusPayload(appContext))
            "listen" -> runListen(args, appContext)
            "transcribe_file" -> runTranscribeFile(args, appContext, audioPath)
            else -> resultError("Invalid action '$requestedAction'. Use listen, transcribe_file, or status.")
        }
    }

    private suspend fun runListen(args: JsonObject?, context: Context): McpToolCallResult {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return resultError(
                "RECORD_AUDIO permission is not granted. Enable microphone access in app settings."
            )
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return resultError(
                "Speech recognition service is unavailable on this device. Install/enable Google voice typing or another recognition service."
            )
        }

        synchronized(stateLock) {
            if (activeSession) {
                return resultError(
                    "A transcription session is already in progress. Wait for it to finish, then try again."
                )
            }
            activeSession = true
        }

        return try {
            val durationSeconds = (argInt(args, "duration_seconds") ?: 10).coerceIn(2, 120)
            val languageTag = argString(args, "language_tag")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: Locale.getDefault().toLanguageTag()
            val preferOffline = argBoolean(args, "prefer_offline") ?: true
            val partialResults = argBoolean(args, "partial_results") ?: true
            val maxResults = (argInt(args, "max_results") ?: 3).coerceIn(1, 5)
            val prompt = argString(args, "prompt")?.trim()?.takeIf { it.isNotBlank() }

            val outcome = listenAndTranscribe(
                context = context,
                durationSeconds = durationSeconds,
                languageTag = languageTag,
                preferOffline = preferOffline,
                partialResults = partialResults,
                maxResults = maxResults,
                prompt = prompt
            )

            if (!outcome.success) {
                return resultError(outcome.error ?: "Transcription failed.")
            }

            val payload = buildJsonObject {
                put("action", "listen")
                put("success", true)
                put("text", outcome.text ?: "")
                put("language_tag", languageTag)
                put("duration_seconds", durationSeconds)
                put("status", outcome.status)
                put("used_partial_fallback", outcome.usedPartialFallback)
                if (outcome.confidence != null) {
                    put("confidence", outcome.confidence)
                }
                put(
                    "alternatives",
                    buildJsonArray {
                        outcome.alternatives.forEach { add(JsonPrimitive(it)) }
                    }
                )
            }
            resultJson(payload)
        } finally {
            synchronized(stateLock) {
                activeSession = false
            }
        }
    }

    private suspend fun runTranscribeFile(
        args: JsonObject?,
        context: Context,
        resolvedPath: String?
    ): McpToolCallResult {
        val audioPath = resolvedPath?.trim()
        if (audioPath.isNullOrBlank()) {
            return resultError("audio_path is required for action=transcribe_file")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return resultError(
                "File transcription requires Android 12+ (API 31+) speech injection support."
            )
        }

        val canonicalFile = runCatching { File(audioPath).canonicalFile }.getOrNull()
            ?: return resultError("Invalid audio_path.")
        if (!canonicalFile.exists() || !canonicalFile.isFile) {
            return resultError("Audio file not found: $audioPath")
        }
        if (!isAllowedFile(canonicalFile, context)) {
            return resultError("Access denied. audio_path is outside allowed directories.")
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return resultError(
                "Speech recognition service is unavailable on this device. Install/enable Google voice typing or another recognition service."
            )
        }

        synchronized(stateLock) {
            if (activeSession) {
                return resultError(
                    "A transcription session is already in progress. Wait for it to finish, then try again."
                )
            }
            activeSession = true
        }

        return try {
            val languageTag = argString(args, "language_tag")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: Locale.getDefault().toLanguageTag()
            val preferOffline = argBoolean(args, "prefer_offline") ?: true
            val partialResults = argBoolean(args, "partial_results") ?: false
            val maxResults = (argInt(args, "max_results") ?: 3).coerceIn(1, 5)
            val estimatedDurationMs = readAudioDurationMs(canonicalFile)
            val timeoutSeconds = (argInt(args, "timeout_seconds")
                ?: ((estimatedDurationMs ?: 20_000L) / 1_000L + 20L).toInt())
                .coerceIn(5, 600)

            val outcome = transcribeAudioFile(
                context = context,
                audioFile = canonicalFile,
                estimatedDurationMs = estimatedDurationMs,
                timeoutSeconds = timeoutSeconds,
                languageTag = languageTag,
                preferOffline = preferOffline,
                partialResults = partialResults,
                maxResults = maxResults,
            )

            if (!outcome.success) {
                return resultError(outcome.error ?: "Transcription failed.")
            }

            val payload = buildJsonObject {
                put("action", "transcribe_file")
                put("success", true)
                put("text", outcome.text ?: "")
                put("audio_path", canonicalFile.absolutePath)
                put("file_name", canonicalFile.name)
                put("file_size_bytes", canonicalFile.length())
                put("language_tag", languageTag)
                put("timeout_seconds", timeoutSeconds)
                if (estimatedDurationMs != null) {
                    put("estimated_duration_ms", estimatedDurationMs)
                }
                put("status", outcome.status)
                put("used_partial_fallback", outcome.usedPartialFallback)
                if (outcome.confidence != null) {
                    put("confidence", outcome.confidence)
                }
                put(
                    "alternatives",
                    buildJsonArray {
                        outcome.alternatives.forEach { add(JsonPrimitive(it)) }
                    }
                )
            }
            resultJson(payload)
        } finally {
            synchronized(stateLock) {
                activeSession = false
            }
        }
    }

    private suspend fun listenAndTranscribe(
        context: Context,
        durationSeconds: Int,
        languageTag: String,
        preferOffline: Boolean,
        partialResults: Boolean,
        maxResults: Int,
        prompt: String?
    ): RecognitionOutcome {
        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                var finished = false
                var latestPartial: String? = null
                val startedAt = System.currentTimeMillis()
                val recognizer = runCatching {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }.getOrElse { error ->
                    if (continuation.isActive) {
                        continuation.resume(
                            RecognitionOutcome(
                                success = false,
                                error = "Could not initialize speech recognizer: ${error.message ?: "unknown error"}"
                            )
                        )
                    }
                    return@post
                }

                fun finish(outcome: RecognitionOutcome) {
                    if (finished) {
                        return
                    }
                    finished = true
                    runCatching { recognizer.destroy() }
                    if (continuation.isActive) {
                        continuation.resume(outcome)
                    }
                }

                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onPartialResults(partial: Bundle?) {
                        val candidate = partial
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        if (candidate != null) {
                            latestPartial = candidate
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val alternatives = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.mapNotNull { it?.trim()?.takeIf { text -> text.isNotBlank() } }
                            .orEmpty()
                        val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val confidence = confidenceScores
                            ?.firstOrNull()
                            ?.takeIf { it >= 0f }
                            ?.toDouble()

                        val chosenText = alternatives.firstOrNull() ?: latestPartial
                        if (chosenText.isNullOrBlank()) {
                            finish(
                                RecognitionOutcome(
                                    success = false,
                                    error = "No speech recognized. Try speaking clearly and closer to the microphone."
                                )
                            )
                            return
                        }

                        finish(
                            RecognitionOutcome(
                                success = true,
                                text = chosenText,
                                alternatives = if (alternatives.isNotEmpty()) alternatives else listOf(chosenText),
                                confidence = confidence,
                                usedPartialFallback = alternatives.isEmpty() && !latestPartial.isNullOrBlank(),
                                status = "final_result"
                            )
                        )
                    }

                    override fun onError(error: Int) {
                        if (finished) {
                            return
                        }

                        val fallback = latestPartial?.takeIf { it.isNotBlank() }
                        val canUseFallback = fallback != null &&
                            (error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                error == SpeechRecognizer.ERROR_CLIENT)

                        if (canUseFallback && fallback != null) {
                            finish(
                                RecognitionOutcome(
                                    success = true,
                                    text = fallback,
                                    alternatives = listOf(fallback),
                                    usedPartialFallback = true,
                                    status = "partial_fallback_${errorName(error)}"
                                )
                            )
                            return
                        }

                        finish(
                            RecognitionOutcome(
                                success = false,
                                error = "Speech recognition failed: ${errorMessage(error)}"
                            )
                        )
                    }
                }

                recognizer.setRecognitionListener(listener)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                    if (prompt != null) {
                        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                    }

                    val listenMs = durationSeconds * 1_000L
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        listenMs
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1_200L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        900L
                    )
                }

                runCatching { recognizer.startListening(intent) }
                    .onFailure { error ->
                        finish(
                            RecognitionOutcome(
                                success = false,
                                error = "Unable to start listening: ${error.message ?: "unknown error"}"
                            )
                        )
                        return@post
                    }

                val listenWindowMs = durationSeconds * 1_000L
                handler.postDelayed({
                    if (!finished) {
                        runCatching { recognizer.stopListening() }
                    }
                }, listenWindowMs)

                handler.postDelayed({
                    if (finished) {
                        return@postDelayed
                    }
                    val fallback = latestPartial?.takeIf { it.isNotBlank() }
                    if (fallback != null) {
                        finish(
                            RecognitionOutcome(
                                success = true,
                                text = fallback,
                                alternatives = listOf(fallback),
                                usedPartialFallback = true,
                                status = "timeout_partial"
                            )
                        )
                    } else {
                        val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000.0)
                        finish(
                            RecognitionOutcome(
                                success = false,
                                error = "Transcription timed out after %.1f seconds with no recognized speech."
                                    .format(elapsedSeconds)
                            )
                        )
                    }
                }, listenWindowMs + 4_000L)

                continuation.invokeOnCancellation {
                    handler.post {
                        if (!finished) {
                            finished = true
                            runCatching { recognizer.cancel() }
                            runCatching { recognizer.destroy() }
                        }
                    }
                }
            }
        }
    }

    private suspend fun transcribeAudioFile(
        context: Context,
        audioFile: File,
        estimatedDurationMs: Long?,
        timeoutSeconds: Int,
        languageTag: String,
        preferOffline: Boolean,
        partialResults: Boolean,
        maxResults: Int
    ): RecognitionOutcome {
        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                var finished = false
                var latestPartial: String? = null
                var injectedPfd: ParcelFileDescriptor? = null
                val recognizer = runCatching {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }.getOrElse { error ->
                    if (continuation.isActive) {
                        continuation.resume(
                            RecognitionOutcome(
                                success = false,
                                error = "Could not initialize speech recognizer: ${error.message ?: "unknown error"}"
                            )
                        )
                    }
                    return@post
                }

                fun closeInjectedSource() {
                    runCatching { injectedPfd?.close() }
                    injectedPfd = null
                }

                fun finish(outcome: RecognitionOutcome) {
                    if (finished) {
                        return
                    }
                    finished = true
                    closeInjectedSource()
                    runCatching { recognizer.destroy() }
                    if (continuation.isActive) {
                        continuation.resume(outcome)
                    }
                }

                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onPartialResults(partial: Bundle?) {
                        val candidate = partial
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        if (candidate != null) {
                            latestPartial = candidate
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val alternatives = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.mapNotNull { it?.trim()?.takeIf { text -> text.isNotBlank() } }
                            .orEmpty()
                        val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val confidence = confidenceScores
                            ?.firstOrNull()
                            ?.takeIf { it >= 0f }
                            ?.toDouble()

                        val chosenText = alternatives.firstOrNull() ?: latestPartial
                        if (chosenText.isNullOrBlank()) {
                            finish(
                                RecognitionOutcome(
                                    success = false,
                                    error = "No speech recognized from the file. This recognizer may not support file-audio transcription."
                                )
                            )
                            return
                        }

                        finish(
                            RecognitionOutcome(
                                success = true,
                                text = chosenText,
                                alternatives = if (alternatives.isNotEmpty()) alternatives else listOf(chosenText),
                                confidence = confidence,
                                usedPartialFallback = alternatives.isEmpty() && !latestPartial.isNullOrBlank(),
                                status = "file_result"
                            )
                        )
                    }

                    override fun onError(error: Int) {
                        if (finished) {
                            return
                        }

                        val fallback = latestPartial?.takeIf { it.isNotBlank() }
                        val canUseFallback = fallback != null &&
                            (error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                error == SpeechRecognizer.ERROR_CLIENT)

                        if (canUseFallback && fallback != null) {
                            finish(
                                RecognitionOutcome(
                                    success = true,
                                    text = fallback,
                                    alternatives = listOf(fallback),
                                    usedPartialFallback = true,
                                    status = "file_partial_fallback_${errorName(error)}"
                                )
                            )
                            return
                        }

                        finish(
                            RecognitionOutcome(
                                success = false,
                                error = "File transcription failed: ${errorMessage(error)} " +
                                    "Some recognition services only support live microphone input."
                            )
                        )
                    }
                }
                recognizer.setRecognitionListener(listener)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    injectedPfd = runCatching {
                        ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    }.getOrElse { error ->
                        finish(
                            RecognitionOutcome(
                                success = false,
                                error = "Could not open audio file descriptor: ${error.message ?: "unknown error"}"
                            )
                        )
                        return@post
                    }
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, injectedPfd)
                    intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                } else {
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_INJECT_SOURCE, Uri.fromFile(audioFile))
                }

                runCatching { recognizer.startListening(intent) }
                    .onFailure { error ->
                        finish(
                            RecognitionOutcome(
                                success = false,
                                error = "Unable to start file transcription: ${error.message ?: "unknown error"}"
                            )
                        )
                        return@post
                    }

                val timeoutMs = timeoutSeconds * 1_000L
                if (injectedPfd != null) {
                    val closeAfterMs = ((estimatedDurationMs ?: 10_000L) + 1_500L)
                        .coerceAtMost((timeoutMs - 1_000L).coerceAtLeast(2_000L))
                    handler.postDelayed({
                        closeInjectedSource()
                    }, closeAfterMs)
                }

                handler.postDelayed({
                    if (finished) {
                        return@postDelayed
                    }
                    val fallback = latestPartial?.takeIf { it.isNotBlank() }
                    if (fallback != null) {
                        finish(
                            RecognitionOutcome(
                                success = true,
                                text = fallback,
                                alternatives = listOf(fallback),
                                usedPartialFallback = true,
                                status = "file_timeout_partial"
                            )
                        )
                    } else {
                        finish(
                            RecognitionOutcome(
                                success = false,
                                error = "File transcription timed out. The recognition service may not support injected audio on this device."
                            )
                        )
                    }
                }, timeoutMs)

                continuation.invokeOnCancellation {
                    handler.post {
                        if (!finished) {
                            finished = true
                            closeInjectedSource()
                            runCatching { recognizer.cancel() }
                            runCatching { recognizer.destroy() }
                        }
                    }
                }
            }
        }
    }

    private fun errorName(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permissions"
            SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
            else -> "unknown_$code"
        }
    }

    private fun errorMessage(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio capture error."
            SpeechRecognizer.ERROR_CLIENT -> "Client-side recognition error."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient microphone permissions."
            SpeechRecognizer.ERROR_NETWORK -> "Network recognition error."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network recognition timed out."
            SpeechRecognizer.ERROR_NO_MATCH -> "No matching speech recognized."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy; retry in a moment."
            SpeechRecognizer.ERROR_SERVER -> "Recognition service server error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected before timeout."
            else -> "Unknown error code $code."
        }
    }

    private fun statusPayload(context: Context): JsonObject {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        val busy = synchronized(stateLock) { activeSession }

        return buildJsonObject {
            put("action", "status")
            put("record_audio_permission", hasPermission)
            put("recognition_available", available)
            put("busy", busy)
            put("android_api", Build.VERSION.SDK_INT)
            put("file_injection_supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            put(
                "ready",
                available && !busy
            )
            if (!available) {
                put("hint", "Install/enable a speech recognition service (e.g. Google voice typing).")
            } else if (busy) {
                put("hint", "Wait for current transcription session to finish.")
            }
        }
    }

    private fun normalizeAction(action: String, audioPath: String?): String {
        if (!audioPath.isNullOrBlank() && action == "listen") {
            return "transcribe_file"
        }
        return when (action) {
            "file", "transcribe_file", "from_file" -> "transcribe_file"
            else -> action
        }
    }

    private fun resolveAudioPath(args: JsonObject?): String? {
        return argString(args, "audio_path")?.trim()?.takeIf { it.isNotBlank() }
            ?: argString(args, "file_path")?.trim()?.takeIf { it.isNotBlank() }
            ?: argString(args, "path")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun readAudioDurationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }

    private fun isAllowedFile(file: File, context: Context): Boolean {
        val allowedRoots = buildList {
            add(File("/sdcard"))
            add(Environment.getExternalStorageDirectory())
            context.filesDir?.let { add(it) }
            context.cacheDir?.let { add(it) }
            context.externalCacheDir?.let { add(it) }
            context.getExternalFilesDir(null)?.let { add(it) }
            context.getExternalFilesDirs(null)?.forEach { candidate ->
                if (candidate != null) add(candidate)
            }
        }.mapNotNull { root ->
            runCatching { root.canonicalFile }.getOrNull()
        }.distinctBy { it.absolutePath }

        return allowedRoots.any { root -> isChildPath(root, file) }
    }

    private fun isChildPath(root: File, file: File): Boolean {
        val rootPath = root.absolutePath
        val filePath = file.absolutePath
        return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }

    private data class RecognitionOutcome(
        val success: Boolean,
        val text: String? = null,
        val alternatives: List<String> = emptyList(),
        val confidence: Double? = null,
        val usedPartialFallback: Boolean = false,
        val status: String = "error",
        val error: String? = null
    )

    private companion object {
        val stateLock = Any()
        var activeSession: Boolean = false
    }
}
