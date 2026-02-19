package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

class WhatsAppAudioTranscribeTool : McpToolHandler {
    override val name = "transcribe_whatsapp_audio"
    override val description =
        "Transcribe the latest WhatsApp/WhatsApp Business voice note audio by path discovery."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("contact_name") {
                put("type", "string")
                put("description", "Optional contact hint, e.g. Fahad Shakoor.")
            }
            putJsonObject("whatsapp_type") {
                put("type", "string")
                put("description", "WhatsApp app type: business or personal (default: business).")
            }
            putJsonObject("audio_path") {
                put("type", "string")
                put("description", "Optional direct audio path. If set, skips auto-discovery.")
            }
            putJsonObject("language_tag") {
                put("type", "string")
                put("description", "BCP-47 language tag, e.g. en-US, ur-PK.")
            }
            putJsonObject("prefer_offline") {
                put("type", "boolean")
                put("description", "Prefer offline recognition when available (default: true).")
            }
            putJsonObject("timeout_seconds") {
                put("type", "integer")
                put("description", "Max wait for transcription result, 5-600 seconds.")
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("description", "Maximum alternatives to return, 1-5 (default: 3).")
            }
            putJsonObject("raw_command") {
                put("type", "string")
                put(
                    "description",
                    "Natural command, e.g. 'transcribe the last audio of Fahad Shakoor from whatsapp bussiness'."
                )
            }
            putJsonObject("command") {
                put("type", "string")
                put("description", "Alias of raw_command.")
            }
            putJsonObject("request") {
                put("type", "string")
                put("description", "Alias of raw_command.")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val directPath = resolveAudioPath(args)
        val rawCommand = argString(args, "raw_command")?.trim()?.takeIf { it.isNotBlank() }
            ?: argString(args, "command")?.trim()?.takeIf { it.isNotBlank() }
            ?: argString(args, "request")?.trim()?.takeIf { it.isNotBlank() }
        val parsed = parseRawCommand(rawCommand)

        val contactName = argString(args, "contact_name")?.trim()?.takeIf { it.isNotBlank() }
            ?: parsed.contactName
        val whatsappType = normalizeWhatsAppType(
            argString(args, "whatsapp_type"),
            parsed.whatsappType
        )
        val preferOffline = argBoolean(args, "prefer_offline") ?: true
        val languageTag = argString(args, "language_tag")?.trim()?.takeIf { it.isNotBlank() }
        val timeoutSeconds = argInt(args, "timeout_seconds")?.coerceIn(5, 600)
        val maxResults = (argInt(args, "max_results") ?: 3).coerceIn(1, 5)

        val selection = if (!directPath.isNullOrBlank()) {
            val file = runCatching { File(directPath).canonicalFile }.getOrNull()
                ?: return resultError("Invalid audio_path.")
            if (!file.exists() || !file.isFile) {
                return resultError("Audio file not found: $directPath")
            }
            AudioSelection(file = file, matchedContactByFileName = false, searchedRoots = emptyList())
        } else {
            findLatestWhatsAppAudio(whatsappType, contactName)
                ?: return resultError(
                    "Could not find WhatsApp ${whatsappTypeLabel(whatsappType)} audio files. " +
                        "Open WhatsApp and download/play the voice note first."
                )
        }

        val delegateArgs = buildJsonObject {
            put("action", "transcribe_file")
            put("audio_path", selection.file.absolutePath)
            put("prefer_offline", preferOffline)
            put("max_results", maxResults)
            if (languageTag != null) {
                put("language_tag", languageTag)
            }
            if (timeoutSeconds != null) {
                put("timeout_seconds", timeoutSeconds)
            }
        }

        val delegate = TranscribeAudioTool().execute(delegateArgs, context)
        if (delegate.isError) {
            return delegate
        }

        val delegateText = delegate.content.firstOrNull()?.text
        val delegateJson = delegateText?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

        val payload = buildJsonObject {
            put("action", "transcribe_whatsapp_audio")
            put("success", true)
            put("whatsapp_type", whatsappType)
            put("contact_name_hint", contactName ?: "")
            put("selected_audio_path", selection.file.absolutePath)
            put("selected_file_name", selection.file.name)
            put("selected_file_last_modified_ms", selection.file.lastModified())
            put("contact_matched_by_file_name", selection.matchedContactByFileName)
            if (!contactName.isNullOrBlank() && !selection.matchedContactByFileName) {
                put(
                    "note",
                    "WhatsApp media filenames usually do not include contact names; this used the latest available file in ${whatsappTypeLabel(whatsappType)} media."
                )
            }
            if (selection.searchedRoots.isNotEmpty()) {
                put(
                    "searched_roots",
                    buildJsonArray {
                        selection.searchedRoots.forEach { add(JsonPrimitive(it)) }
                    }
                )
            }
            if (delegateJson != null) {
                put("transcription", delegateJson)
            } else {
                put("transcription_text", delegateText ?: "")
            }
        }
        return resultJson(payload)
    }

    private fun resolveAudioPath(args: JsonObject?): String? {
        return argString(args, "audio_path")?.trim()?.takeIf { it.isNotBlank() }
            ?: argString(args, "file_path")?.trim()?.takeIf { it.isNotBlank() }
            ?: argString(args, "path")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun normalizeWhatsAppType(rawType: String?, parsedType: String?): String {
        val candidate = rawType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: parsedType
            ?: "business"
        return when {
            candidate.startsWith("personal") || candidate == "normal" || candidate == "regular" -> "personal"
            else -> "business"
        }
    }

    private fun whatsappTypeLabel(type: String): String {
        return if (type == "personal") "Personal" else "Business"
    }

    private fun parseRawCommand(raw: String?): ParsedCommand {
        if (raw.isNullOrBlank()) {
            return ParsedCommand(null, null)
        }
        val normalized = raw.trim()
        val lower = normalized.lowercase()
        val type = when {
            lower.contains("whatsapp bussiness") ||
                lower.contains("whatsapp business") ||
                lower.contains("whatsapp buisness") ||
                lower.contains("whatsapp w4b") -> "business"
            lower.contains("whatsapp personal") -> "personal"
            else -> null
        }

        val contactPattern = Regex(
            pattern = "(?:audio|voice(?:\\s+note|\\s+message)?).*?(?:of|from)\\s+(.+?)\\s+from\\s+what'?s?app",
            option = RegexOption.IGNORE_CASE
        )
        val contact = contactPattern.find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim(',', '.', ':', ';')
            ?.takeIf { it.isNotBlank() }

        return ParsedCommand(contactName = contact, whatsappType = type)
    }

    private fun findLatestWhatsAppAudio(
        whatsappType: String,
        contactName: String?
    ): AudioSelection? {
        val roots = candidateRoots(whatsappType)
        val existingRoots = roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .distinctBy { it.absolutePath }
            .filter { it.exists() && it.isDirectory }

        if (existingRoots.isEmpty()) {
            return null
        }

        val allAudio = mutableListOf<File>()
        existingRoots.forEach { root ->
            runCatching {
                root.walkTopDown()
                    .maxDepth(8)
                    .filter { it.isFile && isAudioExtension(it.extension) }
                    .forEach { allAudio += it }
            }
        }
        if (allAudio.isEmpty()) {
            return null
        }

        val normalizedContact = normalizeSearchToken(contactName)
        val byContact = if (normalizedContact.isBlank()) {
            emptyList()
        } else {
            allAudio.filter { file ->
                normalizeSearchToken(file.name).contains(normalizedContact)
            }
        }
        val pool = if (byContact.isNotEmpty()) byContact else allAudio
        val latest = pool.maxByOrNull { it.lastModified() } ?: return null

        return AudioSelection(
            file = latest,
            matchedContactByFileName = byContact.isNotEmpty() && byContact.contains(latest),
            searchedRoots = existingRoots.map { it.absolutePath }
        )
    }

    private fun candidateRoots(whatsappType: String): List<File> {
        return if (whatsappType == "personal") {
            listOf(
                File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
                File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"),
                File("/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes"),
                File("/storage/emulated/0/WhatsApp/Media/WhatsApp Audio")
            )
        } else {
            listOf(
                File("/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"),
                File("/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Audio"),
                File("/storage/emulated/0/WhatsApp Business/Media/WhatsApp Business Voice Notes"),
                File("/storage/emulated/0/WhatsApp Business/Media/WhatsApp Business Audio")
            )
        }
    }

    private fun isAudioExtension(extension: String): Boolean {
        return AUDIO_EXTENSIONS.contains(extension.lowercase())
    }

    private fun normalizeSearchToken(value: String?): String {
        if (value.isNullOrBlank()) {
            return ""
        }
        return value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    private data class ParsedCommand(
        val contactName: String?,
        val whatsappType: String?
    )

    private data class AudioSelection(
        val file: File,
        val matchedContactByFileName: Boolean,
        val searchedRoots: List<String>
    )

    private companion object {
        val AUDIO_EXTENSIONS = setOf("opus", "ogg", "mp3", "m4a", "aac", "wav", "amr", "3gp")
    }
}
