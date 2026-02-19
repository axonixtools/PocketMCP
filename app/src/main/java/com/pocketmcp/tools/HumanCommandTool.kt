package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class HumanCommandTool : McpToolHandler {
    override val name = "human_command"
    override val description =
        "Use natural language commands. Auto-routes to messaging, phone alert, recording, transcription, app launch, and notifications."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "Natural command text, e.g. 'send message to Fahad on whatsapp bussiness hello'.")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Alias for command.")
            }
            putJsonObject("request") {
                put("type", "string")
                put("description", "Alias for command.")
            }
            putJsonObject("language_tag") {
                put("type", "string")
                put("description", "Optional language hint for transcription commands (e.g. en-US, ur-PK).")
            }
            putJsonObject("strict_contact_match") {
                put("type", "boolean")
                put("description", "For WhatsApp send commands: enforce strict contact verification (default: true).")
            }
            putJsonObject("default_whatsapp_type") {
                put("type", "string")
                put("description", "Fallback WhatsApp type for ambiguous commands: business or personal (default: business).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val command = argString(args, "command")?.trim()?.takeIf { it.isNotBlank() }
            ?: argString(args, "text")?.trim()?.takeIf { it.isNotBlank() }
            ?: argString(args, "request")?.trim()?.takeIf { it.isNotBlank() }
            ?: return resultError("Missing command. Provide command/text/request with natural language.")

        val route = routeCommand(command, args)
            ?: return resultError(
                "Could not interpret command. Try examples: " +
                    "'send message to Fahad on whatsapp bussiness hello', " +
                    "'vibrate my phone for 8 seconds', " +
                    "'record voice for 10 seconds', " +
                    "'transcribe this /storage/...m4a'."
            )

        val delegate = resolveDelegate(route.toolName)
            ?: return resultError("Internal routing error: unsupported target tool ${route.toolName}")
        val delegateResult = delegate.execute(route.arguments, context)
        if (delegateResult.isError) {
            return delegateResult
        }

        val delegateText = delegateResult.content.firstOrNull()?.text
        val delegateJson = delegateText?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

        val payload = buildJsonObject {
            put("action", "human_command")
            put("command", command)
            put("interpreted_intent", route.intent)
            put("target_tool", route.toolName)
            put("target_arguments", route.arguments)
            if (delegateJson != null) {
                put("result", delegateJson)
            } else {
                put("result_text", delegateText.orEmpty())
            }
        }
        return resultJson(payload)
    }

    private fun resolveDelegate(name: String): McpToolHandler? {
        return when (name) {
            "send_message" -> MessagingTool()
            "send_whatsapp_business_message" -> WhatsAppBusinessSendTool()
            "phone_alert" -> PhoneAlertTool()
            "voice_record" -> VoiceRecordTool()
            "transcribe_audio" -> TranscribeAudioTool()
            "transcribe_file" -> TranscribeFileTool()
            "transcribe_whatsapp_audio" -> WhatsAppAudioTranscribeTool()
            "launch_app" -> LaunchAppTool()
            "social_media" -> SocialMediaTool()
            "notifications" -> NotificationTool()
            "global_action" -> GlobalActionTool()
            else -> null
        }
    }

    private fun routeCommand(command: String, args: JsonObject?): CommandRoute? {
        val lower = command.trim().lowercase()
        if (lower.isBlank()) {
            return null
        }

        parseSendMessageRoute(command, args)?.let { return it }
        parsePhoneAlertRoute(lower)?.let { return it }
        parseVoiceRecordingRoute(lower)?.let { return it }
        parseTranscribeRoute(command, lower, args)?.let { return it }
        parseNotificationsRoute(lower)?.let { return it }
        parseSocialSearchRoute(command)?.let { return it }
        parseLaunchAppRoute(command, lower)?.let { return it }
        return null
    }

    private fun parseSendMessageRoute(command: String, args: JsonObject?): CommandRoute? {
        val strictContactMatch = argBoolean(args, "strict_contact_match") ?: true
        val defaultWhatsappType = normalizeWhatsappType(argString(args, "default_whatsapp_type")) ?: "business"

        val patterns = listOf(
            Regex(
                "(?:send|text)\\s+(?:a\\s+)?message\\s+to\\s+(.+?)\\s+on\\s+(.+?)\\s+(.+)",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                "(?:send|text|message)\\s+(.+?)\\s+on\\s+(.+?)\\s+(.+)",
                RegexOption.IGNORE_CASE
            )
        )

        val match = patterns.asSequence().mapNotNull { it.find(command) }.firstOrNull() ?: return null
        val target = match.groupValues.getOrElse(1) { "" }.trim().trim(',', '.', ':', ';')
        val rawPlatform = match.groupValues.getOrElse(2) { "" }.trim()
        val message = match.groupValues.getOrElse(3) { "" }.trim()
        if (target.isBlank() || rawPlatform.isBlank() || message.isBlank()) {
            return null
        }

        val normalizedPlatform = normalizePlatform(rawPlatform, defaultWhatsappType) ?: return null
        return when (normalizedPlatform) {
            "whatsapp_business" -> CommandRoute(
                intent = "send_message_whatsapp_business",
                toolName = "send_whatsapp_business_message",
                arguments = buildJsonObject {
                    put("contact_name", target)
                    put("message", message)
                    put("strict_contact_match", strictContactMatch)
                }
            )

            "whatsapp_personal", "whatsapp" -> CommandRoute(
                intent = "send_message_whatsapp",
                toolName = "send_message",
                arguments = buildJsonObject {
                    put("app", normalizedPlatform)
                    put("contact_name", target)
                    put("message", message)
                    put("strict_contact_match", strictContactMatch)
                }
            )

            "instagram", "messenger" -> CommandRoute(
                intent = "send_message_social",
                toolName = "send_message",
                arguments = buildJsonObject {
                    put("app", normalizedPlatform)
                    put("username", target)
                    put("message", message)
                }
            )

            else -> null
        }
    }

    private fun parsePhoneAlertRoute(lower: String): CommandRoute? {
        val hasRing = lower.contains("ring")
        val hasVibrate = lower.contains("vibrate")
        val findPhone = lower.contains("find my phone") || lower.contains("alert my phone")
        if (!hasRing && !hasVibrate && !findPhone) {
            return null
        }

        val duration = parseDurationSeconds(lower, defaultSeconds = 10)
        val action = when {
            findPhone || (hasRing && hasVibrate) -> "both"
            hasVibrate -> "vibrate"
            else -> "ring"
        }
        return CommandRoute(
            intent = "phone_alert",
            toolName = "phone_alert",
            arguments = buildJsonObject {
                put("action", action)
                put("duration_seconds", duration)
                put("boost_ring_volume", action != "vibrate")
            }
        )
    }

    private fun parseVoiceRecordingRoute(lower: String): CommandRoute? {
        if (lower.contains("transcribe")) {
            return null
        }
        val mentionsRecording = lower.contains("record") ||
            lower.contains("recording") ||
            lower.contains("voice record") ||
            lower.contains("voice recording")
        if (!mentionsRecording) {
            return null
        }
        val action = when {
            lower.contains("stop") -> "stop"
            lower.contains("status") -> "status"
            lower.contains("start") && !lower.contains(" for ") && !lower.contains(" seconds") -> "start"
            else -> "record"
        }
        val duration = if (action == "record" || action == "start") parseDurationSeconds(lower, 10) else null
        return CommandRoute(
            intent = "voice_recording",
            toolName = "voice_record",
            arguments = buildJsonObject {
                put("action", action)
                if (duration != null) {
                    put("duration_seconds", duration)
                }
            }
        )
    }

    private fun parseTranscribeRoute(
        command: String,
        lower: String,
        args: JsonObject?
    ): CommandRoute? {
        if (!lower.contains("transcribe")) {
            return null
        }
        val languageTag = argString(args, "language_tag")?.trim()?.takeIf { it.isNotBlank() }
            ?: parseLanguageTag(lower)
        val path = extractPath(command)

        if (lower.contains("whatsapp")) {
            return CommandRoute(
                intent = "transcribe_whatsapp_audio",
                toolName = "transcribe_whatsapp_audio",
                arguments = buildJsonObject {
                    put("raw_command", command)
                    if (languageTag != null) {
                        put("language_tag", languageTag)
                    }
                }
            )
        }

        if (path != null) {
            return CommandRoute(
                intent = "transcribe_file",
                toolName = "transcribe_file",
                arguments = buildJsonObject {
                    put("audio_path", path)
                    if (languageTag != null) {
                        put("language_tag", languageTag)
                    }
                }
            )
        }

        val duration = parseDurationSeconds(lower, defaultSeconds = 10)
        return CommandRoute(
            intent = "transcribe_listen",
            toolName = "transcribe_audio",
            arguments = buildJsonObject {
                put("action", "listen")
                put("duration_seconds", duration)
                if (languageTag != null) {
                    put("language_tag", languageTag)
                }
            }
        )
    }

    private fun parseNotificationsRoute(lower: String): CommandRoute? {
        if (lower.contains("notification panel") || lower.contains("show notifications panel")) {
            return CommandRoute(
                intent = "open_notification_panel",
                toolName = "global_action",
                arguments = buildJsonObject {
                    put("action", "notifications")
                }
            )
        }
        if (lower.contains("quick settings")) {
            return CommandRoute(
                intent = "open_quick_settings",
                toolName = "global_action",
                arguments = buildJsonObject {
                    put("action", "quick_settings")
                }
            )
        }
        if (lower.contains("list notifications") || lower.startsWith("show notifications")) {
            return CommandRoute(
                intent = "list_notifications",
                toolName = "notifications",
                arguments = buildJsonObject {
                    put("action", "active")
                    put("limit", 20)
                }
            )
        }
        return null
    }

    private fun parseSocialSearchRoute(command: String): CommandRoute? {
        val p1 = Regex("search\\s+(instagram|youtube|x|twitter)\\s+for\\s+(.+)", RegexOption.IGNORE_CASE)
        val p2 = Regex("search\\s+(.+?)\\s+(?:on|in)\\s+(instagram|youtube|x|twitter)", RegexOption.IGNORE_CASE)
        val m1 = p1.find(command)
        val m2 = if (m1 == null) p2.find(command) else null
        val platform: String
        val query: String
        when {
            m1 != null -> {
                platform = m1.groupValues[1].trim().lowercase()
                query = m1.groupValues[2].trim()
            }
            m2 != null -> {
                query = m2.groupValues[1].trim()
                platform = m2.groupValues[2].trim().lowercase()
            }
            else -> return null
        }
        if (query.isBlank() || platform.isBlank()) {
            return null
        }
        val mapped = if (platform == "twitter") "x" else platform
        return CommandRoute(
            intent = "social_search",
            toolName = "social_media",
            arguments = buildJsonObject {
                put("platform", mapped)
                put("action", "search")
                put("query", query)
            }
        )
    }

    private fun parseLaunchAppRoute(command: String, lower: String): CommandRoute? {
        if (lower.contains("send message") || lower.contains("transcribe")) {
            return null
        }
        val match = Regex("(?:open|launch)\\s+(?:the\\s+)?(?:app\\s+)?(.+)", RegexOption.IGNORE_CASE)
            .find(command)
            ?: return null
        val appName = match.groupValues.getOrElse(1) { "" }.trim().trim('.', '!')
        if (appName.isBlank()) {
            return null
        }
        return CommandRoute(
            intent = "launch_app",
            toolName = "launch_app",
            arguments = buildJsonObject {
                put("action", "open")
                put("app_name", appName)
            }
        )
    }

    private fun normalizePlatform(rawPlatform: String, defaultWhatsappType: String): String? {
        val compact = rawPlatform.lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), "")
        if (compact.isBlank()) {
            return null
        }
        val looksLikeWhatsApp = compact.contains("whatsapp") || compact.contains("whatsap") || compact.contains("watsapp")
        if (looksLikeWhatsApp) {
            return when {
                compact.contains("business") || compact.contains("bussiness") || compact.contains("buisness") || compact.contains("w4b") -> "whatsapp_business"
                compact.contains("personal") || compact.contains("normal") || compact.contains("regular") -> "whatsapp_personal"
                else -> if (defaultWhatsappType == "personal") "whatsapp_personal" else "whatsapp_business"
            }
        }
        return when (compact) {
            "instagram", "insta" -> "instagram"
            "messenger", "facebookmessenger", "fbmessenger", "fbchat" -> "messenger"
            "googlemessages", "messages" -> "google_messages"
            else -> null
        }
    }

    private fun normalizeWhatsappType(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val compact = raw.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return when {
            compact.contains("personal") || compact == "normal" || compact == "regular" -> "personal"
            compact.contains("business") || compact.contains("bussiness") || compact.contains("buisness") || compact == "w4b" -> "business"
            else -> null
        }
    }

    private fun parseDurationSeconds(text: String, defaultSeconds: Int): Int {
        Regex("(\\d+)\\s*(seconds?|secs?|sec|s)\\b", RegexOption.IGNORE_CASE).find(text)?.let { match ->
            return match.groupValues[1].toIntOrNull()?.coerceIn(1, 600) ?: defaultSeconds
        }
        Regex("(\\d+)\\s*(minutes?|mins?|min|m)\\b", RegexOption.IGNORE_CASE).find(text)?.let { match ->
            val minutes = match.groupValues[1].toIntOrNull() ?: return defaultSeconds
            return (minutes * 60).coerceIn(1, 600)
        }
        return defaultSeconds
    }

    private fun extractPath(command: String): String? {
        val quoted = Regex("\"([^\"]+)\"").findAll(command)
            .map { it.groupValues[1] }
            .firstOrNull { it.startsWith("/") && it.contains('/') }
        if (quoted != null) {
            return quoted
        }
        return Regex("(/storage/[^\\s]+)").find(command)?.groupValues?.getOrNull(1)
    }

    private fun parseLanguageTag(lower: String): String? {
        return when {
            lower.contains(" urdu") || lower.contains("in urdu") -> "ur-PK"
            lower.contains(" hindi") || lower.contains("in hindi") -> "hi-IN"
            lower.contains(" english") || lower.contains("in english") -> "en-US"
            lower.contains(" arabic") || lower.contains("in arabic") -> "ar-SA"
            else -> null
        }
    }

    private data class CommandRoute(
        val intent: String,
        val toolName: String,
        val arguments: JsonObject
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
