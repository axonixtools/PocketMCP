package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

class HumanCommandTool : McpToolHandler {
    override val name = "human_command"
    override val description =
        "Ultimate fast-path for natural language execution. Ingests raw user instructions (e.g., 'text John hello', 'turn on flashlight', 'what's my battery', 'scroll down') and autonomously routes, executes, and recovers. Handles fuzzy matching, missing-parameter inference, and permission-aware error reporting. Use this for broad human requests without step-by-step reasoning."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "Natural command text, e.g. 'send message to Emily on whatsapp business hello'.")
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
            putJsonObject("include_routing_debug") {
                put("type", "boolean")
                put("description", "Optional. When true, include interpreted route metadata in output (default: false).")
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
                    "'text John on whatsapp hello', " +
                    "'turn on flashlight', " +
                    "'what is my battery', " +
                    "'scroll down', " +
                    "'call Mom', " +
                    "'volume up', " +
                    "'take a screenshot', " +
                    "'open chrome and search cats'."
            )

        val delegate = resolveDelegate(route.toolName)
            ?: return resultError("Internal routing error: unsupported target tool ${route.toolName}")

        val delegateResult = delegate.execute(route.arguments, context)

        val includeRoutingDebug = argBoolean(args, "include_routing_debug") ?: false

        // Smart error augmentation
        val finalResult = if (delegateResult.isError) {
            augmentError(delegateResult, route, command)
        } else delegateResult

        if (!includeRoutingDebug) {
            return finalResult
        }

        val delegateText = finalResult.content.firstOrNull()?.text
        val delegateJson = delegateText?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

        val payload = buildJsonObject {
            put("action", "human_command")
            put("command", command)
            put("interpreted_intent", route.intent)
            put("confidence", route.confidence)
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
            "make_call" -> CallTool()
            "search_contacts" -> ContactsSearchTool()
            "phone_alert" -> PhoneAlertTool()
            "voice_record" -> VoiceRecordTool()
            "transcribe_audio" -> TranscribeAudioTool()
            "transcribe_file" -> TranscribeFileTool()
            "transcribe_whatsapp_audio" -> WhatsAppAudioTranscribeTool()
            "launch_app" -> LaunchAppTool()
            "social_media" -> SocialMediaTool()
            "search_screen" -> SearchScreenTool()
            "notifications" -> NotificationTool()
            "global_action" -> GlobalActionTool()
            "shell" -> ShellCommandTool()
            "volume_control" -> VolumeControlTool()
            "flashlight" -> FlashlightTool()
            "take_screenshot" -> ScreenshotTool()
            "device_info" -> DeviceInfoTool()
            "get_location" -> LocationTool()
            "scroll_screen" -> ScrollScreenTool()
            "tap" -> TapTool()
            "screen_state" -> ScreenStateTool()
            "list_apps" -> ListAppsTool()
            "workflow_automation" -> WorkflowAutomationTool()
            "app_actions" -> PresetAppActionsTool()
            "http_request" -> HttpProxyTool()
            "read_file" -> FileReadTool()
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Scoring-based router
    // ------------------------------------------------------------------

    private fun routeCommand(command: String, args: JsonObject?): CommandRoute? {
        val lower = command.trim().lowercase()
        if (lower.isBlank()) return null

        val candidates = mutableListOf<CommandRoute>()

        parseSendMessageRoute(command, args)?.let { candidates.add(it) }
        parseCallRoute(command)?.let { candidates.add(it) }
        parseContactSearchRoute(command)?.let { candidates.add(it) }
        parsePhoneAlertRoute(lower)?.let { candidates.add(it) }
        parseVoiceRecordingRoute(lower)?.let { candidates.add(it) }
        parseTranscribeRoute(command, lower, args)?.let { candidates.add(it) }
        parseNotificationsRoute(lower)?.let { candidates.add(it) }
        parseSocialSearchRoute(command)?.let { candidates.add(it) }
        parseOpenAndSearchRoute(command)?.let { candidates.add(it) }
        parseCurrentScreenSearchRoute(command)?.let { candidates.add(it) }
        parseLaunchAppRoute(command, lower)?.let { candidates.add(it) }
        parseShellRoute(lower)?.let { candidates.add(it) }
        parseVolumeRoute(lower)?.let { candidates.add(it) }
        parseFlashlightRoute(lower)?.let { candidates.add(it) }
        parseScreenshotRoute(lower)?.let { candidates.add(it) }
        parseDeviceInfoRoute(lower)?.let { candidates.add(it) }
        parseLocationRoute(lower)?.let { candidates.add(it) }
        parseScrollRoute(lower)?.let { candidates.add(it) }
        parseTapRoute(command, lower)?.let { candidates.add(it) }
        parseScreenStateRoute(lower)?.let { candidates.add(it) }
        parseListAppsRoute(lower)?.let { candidates.add(it) }
        parseGlobalActionRoute(lower)?.let { candidates.add(it) }
        parseFileReadRoute(command, lower)?.let { candidates.add(it) }
        parseHttpRequestRoute(command, lower)?.let { candidates.add(it) }
        parseWorkflowRoute(command, lower)?.let { candidates.add(it) }
        parseAppActionsRoute(command, lower)?.let { candidates.add(it) }
        parseInstallAppRoute(command, lower)?.let { candidates.add(it) }

        return candidates.maxByOrNull { it.confidence }
    }

    // ------------------------------------------------------------------
    // Messaging
    // ------------------------------------------------------------------

    private fun parseSendMessageRoute(command: String, args: JsonObject?): CommandRoute? {
        val strictContactMatch = argBoolean(args, "strict_contact_match") ?: true
        val defaultWhatsappType = normalizeWhatsappType(argString(args, "default_whatsapp_type")) ?: "business"

        // Expanded patterns: more flexible NL
        val patterns = listOf(
            Regex("(?:send|text|msg|message|tell|ask)\\s+(?:a\\s+)?(?:message\\s+|msg\\s+|text\\s+)?to\\s+(.+?)\\s+(?:on|via|using|through|in)\\s+(.+?)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("(?:send|text|msg|message|tell|ask)\\s+(.+?)\\s+(?:on|via|using|through|in)\\s+(.+?)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("(?:send|text|msg|message|tell|ask)\\s+(?:a\\s+)?(?:message\\s+|msg\\s+|text\\s+)?to\\s+(.+?)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("(?:text|msg|message|tell|ask)\\s+(.+?)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("(?:whatsapp|wa|dm)\\s+(.+?)\\s+(.+)", RegexOption.IGNORE_CASE)
        )

        val match = patterns.asSequence().mapNotNull { it.find(command) }.firstOrNull() ?: return null
        val groupCount = match.groupValues.size

        val target: String
        val rawPlatform: String
        val message: String

        when {
            groupCount >= 4 -> {
                target = match.groupValues[1].trim().trim { it in ",.:;" }
                rawPlatform = match.groupValues[2].trim()
                message = match.groupValues[3].trim()
            }
            groupCount >= 3 -> {
                target = match.groupValues[1].trim().trim { it in ",.:;" }
                rawPlatform = ""
                message = match.groupValues[2].trim()
            }
            else -> return null
        }

        if (target.isBlank() || message.isBlank()) return null

        // Infer platform from raw text or command context
        val inferredPlatform = when {
            rawPlatform.isNotBlank() -> normalizePlatform(rawPlatform, defaultWhatsappType)
            command.lowercase().contains("whatsapp business") || command.lowercase().contains("w4b") -> "whatsapp_business"
            command.lowercase().contains("whatsapp") || command.lowercase().contains("wa ") -> "whatsapp"
            command.lowercase().contains("instagram") || command.lowercase().contains("insta") || command.lowercase().contains("ig ") -> "instagram"
            command.lowercase().contains("messenger") || command.lowercase().contains("fb ") -> "messenger"
            command.lowercase().contains("sms") || command.lowercase().contains("text ") -> "sms"
            else -> if (defaultWhatsappType == "personal") "whatsapp" else "whatsapp_business"
        }

        return when (inferredPlatform) {
            "whatsapp_business" -> CommandRoute(
                intent = "send_message_whatsapp_business",
                toolName = "send_whatsapp_business_message",
                confidence = 95,
                arguments = buildJsonObject {
                    put("contact_name", target)
                    put("message", message)
                    put("strict_contact_match", strictContactMatch)
                }
            )
            "whatsapp_personal", "whatsapp" -> CommandRoute(
                intent = "send_message_whatsapp",
                toolName = "send_message",
                confidence = 95,
                arguments = buildJsonObject {
                    put("app", inferredPlatform)
                    put("contact_name", target)
                    put("message", message)
                    put("strict_contact_match", strictContactMatch)
                }
            )
            "instagram", "messenger" -> CommandRoute(
                intent = "send_message_social",
                toolName = "send_message",
                confidence = 90,
                arguments = buildJsonObject {
                    put("app", inferredPlatform)
                    put("username", target)
                    put("message", message)
                }
            )
            "sms" -> CommandRoute(
                intent = "send_message_sms",
                toolName = "send_message",
                confidence = 90,
                arguments = buildJsonObject {
                    put("app", "sms")
                    put("contact_name", target)
                    put("message", message)
                }
            )
            else -> CommandRoute(
                intent = "send_message_default",
                toolName = "send_message",
                confidence = 80,
                arguments = buildJsonObject {
                    put("app", "whatsapp_business")
                    put("contact_name", target)
                    put("message", message)
                    put("strict_contact_match", strictContactMatch)
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // Call
    // ------------------------------------------------------------------

    private fun parseCallRoute(command: String): CommandRoute? {
        val match = Regex(
            "(?:call|dial|ring)\\s+(?:the\\s+)?(?:number\\s+)?(.+)",
            RegexOption.IGNORE_CASE
        ).find(command.trim()) ?: return null
        val target = match.groupValues[1].trim().trim { it in ".!?" }
        if (target.isBlank()) return null

        // Check if it's a raw number
        val digitsOnly = target.replace(Regex("[^0-9+]"), "")
        return if (digitsOnly.length >= 7) {
            CommandRoute(
                intent = "make_call",
                toolName = "make_call",
                confidence = 95,
                arguments = buildJsonObject {
                    put("phone_number", digitsOnly)
                    put("contact_name", target)
                }
            )
        } else {
            CommandRoute(
                intent = "make_call_by_contact",
                toolName = "make_call",
                confidence = 85,
                arguments = buildJsonObject {
                    put("contact_name", target)
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // Contact search
    // ------------------------------------------------------------------

    private fun parseContactSearchRoute(command: String): CommandRoute? {
        val match = Regex(
            "(?:find|search|look\\s*up|lookup)\\s+(?:contact|person|number)\\s+(.+)",
            RegexOption.IGNORE_CASE
        ).find(command.trim()) ?: return null
        val query = match.groupValues[1].trim().trim { it in ".!?" }
        if (query.isBlank()) return null
        return CommandRoute(
            intent = "search_contacts",
            toolName = "search_contacts",
            confidence = 92,
            arguments = buildJsonObject {
                put("query", query)
                put("limit", 10)
            }
        )
    }

    // ------------------------------------------------------------------
    // Phone alert
    // ------------------------------------------------------------------

    private fun parsePhoneAlertRoute(lower: String): CommandRoute? {
        val hasRing = lower.contains("ring") || lower.contains("beep")
        val hasVibrate = lower.contains("vibrate") || lower.contains("buzz")
        val findPhone = lower.contains("find my phone") || lower.contains("alert my phone") || lower.contains("where is my phone")
        if (!hasRing && !hasVibrate && !findPhone) return null

        val duration = parseDurationSeconds(lower, defaultSeconds = 10)
        val action = when {
            findPhone || (hasRing && hasVibrate) -> "both"
            hasVibrate -> "vibrate"
            else -> "ring"
        }
        return CommandRoute(
            intent = "phone_alert",
            toolName = "phone_alert",
            confidence = 95,
            arguments = buildJsonObject {
                put("action", action)
                put("duration_seconds", duration)
                put("boost_ring_volume", action != "vibrate")
            }
        )
    }

    // ------------------------------------------------------------------
    // Voice recording
    // ------------------------------------------------------------------

    private fun parseVoiceRecordingRoute(lower: String): CommandRoute? {
        if (lower.contains("transcribe")) return null
        val mentionsRecording = lower.contains("record") ||
            lower.contains("recording") ||
            lower.contains("voice record") ||
            lower.contains("voice recording") ||
            lower.contains("start recording")
        if (!mentionsRecording) return null
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
            confidence = 95,
            arguments = buildJsonObject {
                put("action", action)
                if (duration != null) put("duration_seconds", duration)
            }
        )
    }

    // ------------------------------------------------------------------
    // Transcribe
    // ------------------------------------------------------------------

    private fun parseTranscribeRoute(command: String, lower: String, args: JsonObject?): CommandRoute? {
        if (!lower.contains("transcribe") && !lower.contains("transcription") && !lower.contains("speech to text")) return null
        val languageTag = argString(args, "language_tag")?.trim()?.takeIf { it.isNotBlank() }
            ?: parseLanguageTag(lower)
        val path = extractPath(command)

        if (lower.contains("whatsapp")) {
            return CommandRoute(
                intent = "transcribe_whatsapp_audio",
                toolName = "transcribe_whatsapp_audio",
                confidence = 95,
                arguments = buildJsonObject {
                    put("raw_command", command)
                    if (languageTag != null) put("language_tag", languageTag)
                }
            )
        }

        if (path != null) {
            return CommandRoute(
                intent = "transcribe_file",
                toolName = "transcribe_file",
                confidence = 95,
                arguments = buildJsonObject {
                    put("audio_path", path)
                    if (languageTag != null) put("language_tag", languageTag)
                }
            )
        }

        val duration = parseDurationSeconds(lower, defaultSeconds = 10)
        return CommandRoute(
            intent = "transcribe_listen",
            toolName = "transcribe_audio",
            confidence = 90,
            arguments = buildJsonObject {
                put("action", "listen")
                put("duration_seconds", duration)
                if (languageTag != null) put("language_tag", languageTag)
            }
        )
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private fun parseNotificationsRoute(lower: String): CommandRoute? {
        if (lower.contains("notification panel") || lower.contains("show notifications panel") || lower.contains("pull down notifications")) {
            return CommandRoute(
                intent = "open_notification_panel",
                toolName = "global_action",
                confidence = 95,
                arguments = buildJsonObject { put("action", "notifications") }
            )
        }
        if (lower.contains("quick settings") || lower.contains("pull down quick settings")) {
            return CommandRoute(
                intent = "open_quick_settings",
                toolName = "global_action",
                confidence = 95,
                arguments = buildJsonObject { put("action", "quick_settings") }
            )
        }
        if (lower.contains("list notifications") || lower.startsWith("show notifications") || lower.contains("read notifications")) {
            return CommandRoute(
                intent = "list_notifications",
                toolName = "notifications",
                confidence = 92,
                arguments = buildJsonObject {
                    put("action", "active")
                    put("limit", 20)
                }
            )
        }
        return null
    }

    // ------------------------------------------------------------------
    // Social search
    // ------------------------------------------------------------------

    private fun parseSocialSearchRoute(command: String): CommandRoute? {
        val p1 = Regex("search\\s+(instagram|youtube|x|twitter|yt)\\s+for\\s+(.+)", RegexOption.IGNORE_CASE)
        val p2 = Regex("search\\s+(.+?)\\s+(?:on|in)\\s+(instagram|youtube|x|twitter|yt)", RegexOption.IGNORE_CASE)
        val p3 = Regex("(?:find|look\\s*up)\\s+(.+?)\\s+(?:on|in)\\s+(instagram|youtube|x|twitter|yt)", RegexOption.IGNORE_CASE)

        val m = sequenceOf(p1, p2, p3).mapNotNull { it.find(command) }.firstOrNull() ?: return null

        val platformRaw = (m.groups[2]?.value ?: m.groups[1]?.value ?: "").trim().lowercase()
        val query = (m.groups[1]?.value ?: m.groups[2]?.value ?: "").trim()
        if (query.isBlank() || platformRaw.isBlank()) return null

        val mapped = when {
            platformRaw == "twitter" -> "x"
            platformRaw == "yt" -> "youtube"
            else -> platformRaw
        }
        return CommandRoute(
            intent = "social_search",
            toolName = "social_media",
            confidence = 94,
            arguments = buildJsonObject {
                put("platform", mapped)
                put("action", "search")
                put("query", query)
            }
        )
    }

    // ------------------------------------------------------------------
    // Open and search
    // ------------------------------------------------------------------

    private fun parseOpenAndSearchRoute(command: String): CommandRoute? {
        val p1 = Regex(
            "(?:open|launch)\\s+(google\\s*chrome|chrome|google|browser|maps|youtube|yt)\\s+(?:and|then)?\\s*(?:search|find|look\\s*up|lookup|play)\\s+(.+)",
            RegexOption.IGNORE_CASE
        )
        val p2 = Regex(
            "(?:search|find|look\\s*up|lookup|play)\\s+(.+?)\\s+(?:on|in)\\s+(google\\s*chrome|chrome|google|browser|maps|youtube|yt)",
            RegexOption.IGNORE_CASE
        )

        val m1 = p1.find(command)
        val m2 = if (m1 == null) p2.find(command) else null
        val appRaw: String
        val queryRaw: String
        when {
            m1 != null -> { appRaw = m1.groupValues[1]; queryRaw = m1.groupValues[2] }
            m2 != null -> { queryRaw = m2.groupValues[1]; appRaw = m2.groupValues[2] }
            else -> return null
        }

        val appName = normalizeSearchAppName(appRaw) ?: return null
        val query = queryRaw.trim().trim { it in ".!?" }
        if (query.isBlank()) return null

        return CommandRoute(
            intent = "open_and_search",
            toolName = "search_screen",
            confidence = 93,
            arguments = buildJsonObject {
                put("action", "search")
                put("app_name", appName)
                put("query", query)
                put("close_popups", true)
                put("submit", true)
            }
        )
    }

    // ------------------------------------------------------------------
    // Current screen search
    // ------------------------------------------------------------------

    private fun parseCurrentScreenSearchRoute(command: String): CommandRoute? {
        val match = Regex(
            "^(?:search|find|look\\s*up|lookup)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(command.trim()) ?: return null
        val query = match.groupValues[1].trim().trim { it in ".!?" }
        if (query.isBlank()) return null
        return CommandRoute(
            intent = "search_current_screen",
            toolName = "search_screen",
            confidence = 88,
            arguments = buildJsonObject {
                put("action", "search")
                put("query", query)
                put("close_popups", true)
                put("submit", true)
            }
        )
    }

    // ------------------------------------------------------------------
    // Launch app
    // ------------------------------------------------------------------

    private fun parseLaunchAppRoute(command: String, lower: String): CommandRoute? {
        if (lower.contains("send message") || lower.contains("transcribe") || lower.contains("call ") || lower.contains("search ")) return null
        val match = Regex("(?:open|launch|start)\\s+(?:the\\s+)?(?:app\\s+)?(.+)", RegexOption.IGNORE_CASE).find(command) ?: return null
        val appName = match.groupValues.getOrElse(1) { "" }.trim().trim { it in ".!" }
        if (appName.isBlank()) return null
        return CommandRoute(
            intent = "launch_app",
            toolName = "launch_app",
            confidence = 90,
            arguments = buildJsonObject {
                put("action", "open")
                put("app_name", appName)
            }
        )
    }

    // ------------------------------------------------------------------
    // Shell
    // ------------------------------------------------------------------

    private fun parseShellRoute(lower: String): CommandRoute? {
        val prefixes = listOf("run shell", "execute shell", "shell command", "run command", "execute command", "adb shell", "terminal")
        val prefix = prefixes.find { lower.startsWith(it) } ?: return null
        val cmd = lower.removePrefix(prefix).trim().trim { it in ":-" }
        if (cmd.isBlank()) return null
        return CommandRoute(
            intent = "shell",
            toolName = "shell",
            confidence = 95,
            arguments = buildJsonObject {
                put("command", cmd)
                put("timeout_seconds", 15)
            }
        )
    }

    // ------------------------------------------------------------------
    // Volume
    // ------------------------------------------------------------------

    private fun parseVolumeRoute(lower: String): CommandRoute? {
        if (!lower.contains("volume") && !lower.contains("sound") && !lower.contains("audio") && !lower.contains("mute") && !lower.contains("unmute")) return null

        val action = when {
            lower.contains("mute") && !lower.contains("unmute") -> "mute"
            lower.contains("unmute") -> "unmute"
            lower.contains("up") || lower.contains("increase") || lower.contains("louder") || lower.contains("higher") -> "up"
            lower.contains("down") || lower.contains("decrease") || lower.contains("lower") || lower.contains("quieter") -> "down"
            lower.contains("set") || Regex("volume\\s+\\d+").find(lower) != null -> "set"
            lower.contains("status") || lower.contains("what") || lower.contains("current") -> "status"
            else -> "status"
        }

        val stream = when {
            lower.contains("ring") || lower.contains("ringer") || lower.contains("phone") -> "ring"
            lower.contains("alarm") -> "alarm"
            lower.contains("notification") -> "notification"
            lower.contains("media") || lower.contains("music") || lower.contains("spotify") -> "music"
            lower.contains("voice") || lower.contains("call") -> "voice_call"
            else -> "music"
        }

        val steps = Regex("(\\d+)\\s*(?:steps?|clicks?|times?|levels?)").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("volume\\s+(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 3

        return CommandRoute(
            intent = "volume_control",
            toolName = "volume_control",
            confidence = 94,
            arguments = buildJsonObject {
                put("action", action)
                put("stream", stream)
                if (action == "set" || action == "up" || action == "down") {
                    put("steps", steps)
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Flashlight
    // ------------------------------------------------------------------

    private fun parseFlashlightRoute(lower: String): CommandRoute? {
        if (!lower.contains("flashlight") && !lower.contains("flash light") && !lower.contains("torch") && !lower.contains("flash")) return null
        val action = when {
            lower.contains("turn on") || lower.contains("on") || lower.contains("enable") || lower.contains("start") -> "on"
            lower.contains("turn off") || lower.contains("off") || lower.contains("disable") || lower.contains("stop") -> "off"
            lower.contains("toggle") -> "toggle"
            else -> "status"
        }
        return CommandRoute(
            intent = "flashlight",
            toolName = "flashlight",
            confidence = 96,
            arguments = buildJsonObject { put("action", action) }
        )
    }

    // ------------------------------------------------------------------
    // Screenshot
    // ------------------------------------------------------------------

    private fun parseScreenshotRoute(lower: String): CommandRoute? {
        if (!lower.contains("screenshot") && !lower.contains("screen shot") && !lower.contains("capture screen")) return null
        return CommandRoute(
            intent = "take_screenshot",
            toolName = "take_screenshot",
            confidence = 98,
            arguments = buildJsonObject {
                put("format", "png")
            }
        )
    }

    // ------------------------------------------------------------------
    // Device info
    // ------------------------------------------------------------------

    private fun parseDeviceInfoRoute(lower: String): CommandRoute? {
        if (!lower.contains("device info") &&
            !lower.contains("battery") &&
            !lower.contains("phone status") &&
            !lower.contains("phone info") &&
            !lower.contains("what is my") &&
            !lower.contains("what's my") &&
            !lower.contains("how much battery") &&
            !lower.contains("how much ram") &&
            !lower.contains("os version") &&
            !lower.contains("android version")
        ) return null
        return CommandRoute(
            intent = "device_info",
            toolName = "device_info",
            confidence = 96,
            arguments = buildJsonObject { }
        )
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    private fun parseLocationRoute(lower: String): CommandRoute? {
        if (!lower.contains("location") &&
            !lower.contains("where am i") &&
            !lower.contains("my location") &&
            !lower.contains("gps") &&
            !lower.contains("coordinates")
        ) return null
        return CommandRoute(
            intent = "get_location",
            toolName = "get_location",
            confidence = 96,
            arguments = buildJsonObject { put("max_age_seconds", 300) }
        )
    }

    // ------------------------------------------------------------------
    // Scroll
    // ------------------------------------------------------------------

    private fun parseScrollRoute(lower: String): CommandRoute? {
        if (!lower.contains("scroll") && !lower.contains("swipe")) return null
        val direction = when {
            lower.contains("down") -> "down"
            lower.contains("up") -> "up"
            lower.contains("left") -> "left"
            lower.contains("right") -> "right"
            else -> "down"
        }
        return CommandRoute(
            intent = "scroll_screen",
            toolName = "scroll_screen",
            confidence = 95,
            arguments = buildJsonObject {
                put("direction", direction)
            }
        )
    }

    // ------------------------------------------------------------------
    // Tap
    // ------------------------------------------------------------------

    private fun parseTapRoute(command: String, lower: String): CommandRoute? {
        val match = Regex(
            "(?:tap|click|press)\\s+(?:on\\s+)?\"?(.+?)\"?\\s*$",
            RegexOption.IGNORE_CASE
        ).find(command.trim()) ?: return null
        val text = match.groupValues[1].trim().trim { it in ".!?'\"" }
        if (text.isBlank()) return null
        return CommandRoute(
            intent = "tap",
            toolName = "tap",
            confidence = 93,
            arguments = buildJsonObject {
                put("text", text)
                put("exact", false)
            }
        )
    }

    // ------------------------------------------------------------------
    // Screen state
    // ------------------------------------------------------------------

    private fun parseScreenStateRoute(lower: String): CommandRoute? {
        if (!lower.contains("screen state") &&
            !lower.contains("what's on screen") &&
            !lower.contains("what is on screen") &&
            !lower.contains("current screen") &&
            !lower.contains("what app is open") &&
            !lower.contains("what's open") &&
            !lower.contains("what is open")
        ) return null
        return CommandRoute(
            intent = "screen_state",
            toolName = "screen_state",
            confidence = 95,
            arguments = buildJsonObject { put("max_nodes", 80) }
        )
    }

    // ------------------------------------------------------------------
    // List apps
    // ------------------------------------------------------------------

    private fun parseListAppsRoute(lower: String): CommandRoute? {
        if (!lower.contains("list apps") && !lower.contains("installed apps") && !lower.contains("what apps") && !lower.contains("show apps")) return null
        return CommandRoute(
            intent = "list_apps",
            toolName = "list_apps",
            confidence = 95,
            arguments = buildJsonObject { put("action", "list") }
        )
    }

    // ------------------------------------------------------------------
    // Global actions
    // ------------------------------------------------------------------

    private fun parseGlobalActionRoute(lower: String): CommandRoute? {
        val action = when {
            lower.contains("go home") || lower.contains("press home") || lower.contains("home button") -> "home"
            lower.contains("go back") || lower.contains("press back") || lower.contains("back button") -> "back"
            lower.contains("recent apps") || lower.contains("recents") || lower.contains("app switcher") -> "recents"
            lower.contains("lock screen") || lower.contains("lock the phone") || lower.contains("turn off screen") -> "lock"
            lower.contains("power menu") || lower.contains("power button") -> "power_dialog"
            lower.contains("split screen") -> "split_screen"
            else -> return null
        }
        return CommandRoute(
            intent = "global_action",
            toolName = "global_action",
            confidence = 96,
            arguments = buildJsonObject { put("action", action) }
        )
    }

    // ------------------------------------------------------------------
    // File read
    // ------------------------------------------------------------------

    private fun parseFileReadRoute(command: String, lower: String): CommandRoute? {
        if (!lower.contains("read file") && !lower.contains("read") && !lower.contains("show file") && !lower.contains("open file")) return null
        val path = extractPath(command) ?: return null
        return CommandRoute(
            intent = "read_file",
            toolName = "read_file",
            confidence = 92,
            arguments = buildJsonObject { put("path", path) }
        )
    }

    // ------------------------------------------------------------------
    // HTTP request
    // ------------------------------------------------------------------

    private fun parseHttpRequestRoute(command: String, lower: String): CommandRoute? {
        val urlMatch = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(command) ?: return null
        val url = urlMatch.value
        val method = when {
            lower.contains("post") -> "POST"
            lower.contains("put") -> "PUT"
            lower.contains("delete") -> "DELETE"
            lower.contains("patch") -> "PATCH"
            else -> "GET"
        }
        return CommandRoute(
            intent = "http_request",
            toolName = "http_request",
            confidence = 90,
            arguments = buildJsonObject {
                put("url", url)
                put("method", method)
                put("timeout_seconds", 15)
            }
        )
    }

    // ------------------------------------------------------------------
    // Workflow
    // ------------------------------------------------------------------

    private fun parseWorkflowRoute(command: String, lower: String): CommandRoute? {
        if (!lower.contains("workflow") && !lower.contains("automation") && !lower.contains("automate")) return null
        // Simple pass-through for now; user can give workflow_name in future
        return CommandRoute(
            intent = "workflow_automation",
            toolName = "workflow_automation",
            confidence = 70,
            arguments = buildJsonObject {
                put("workflow_name", "user_request")
                putJsonArray("steps") {
                    add(JsonPrimitive("User provided command: $command"))
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Install app (Play Store fallback)
    // ------------------------------------------------------------------

    private fun parseInstallAppRoute(command: String, lower: String): CommandRoute? {
        if (!lower.contains("install") && !lower.contains("download") && !lower.contains("get app")) return null
        // Avoid routing "send message" or other commands that contain "install" coincidentally
        if (lower.contains("send ") || lower.contains("call ") || lower.contains("text ") || lower.contains("message ")) return null

        val patterns = listOf(
            Regex("(?:install|download|get)\\s+(?:the\\s+)?(?:app\\s+)?(.+)", RegexOption.IGNORE_CASE),
            Regex("(?:install|download|get)\\s+(?:the\\s+)?(.+)\\s+app", RegexOption.IGNORE_CASE)
        )
        val match = patterns.asSequence().mapNotNull { it.find(command) }.firstOrNull() ?: return null
        val appName = match.groupValues[1].trim().trim { it in ".!?'\"" }
        if (appName.isBlank()) return null

        // Normalize common app names to package IDs or Play Store search terms
        val playStoreQuery = normalizeAppNameForInstall(appName) ?: appName

        return CommandRoute(
            intent = "install_app",
            toolName = "search_screen",
            confidence = 88,
            arguments = buildJsonObject {
                put("action", "search")
                put("app_name", "Play Store")
                put("query", playStoreQuery)
                put("close_popups", true)
                put("submit", true)
            }
        )
    }

    // ------------------------------------------------------------------
    // App actions (presets)
    // ------------------------------------------------------------------

    private fun parseAppActionsRoute(command: String, lower: String): CommandRoute? {
        // e.g. "preset WhatsApp send_message to John"
        val match = Regex(
            "(?:preset|run preset|use preset)\\s+(\\w+)\\s+(\\w+)(?:\\s+to\\s+(.+))?",
            RegexOption.IGNORE_CASE
        ).find(command) ?: return null
        val app = match.groupValues[1].trim().lowercase()
        val action = match.groupValues[2].trim().lowercase()
        val extra = match.groupValues.getOrNull(3)?.trim()
        return CommandRoute(
            intent = "app_actions_preset",
            toolName = "app_actions",
            confidence = 92,
            arguments = buildJsonObject {
                put("mode", "run")
                put("app", app)
                put("action", action)
                if (!extra.isNullOrBlank()) {
                    put("message", extra)
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Error augmentation (self-correction hints)
    // ------------------------------------------------------------------

    private fun augmentError(result: McpToolCallResult, route: CommandRoute, originalCommand: String): McpToolCallResult {
        val text = result.content.firstOrNull()?.text ?: ""
        val lowerText = text.lowercase()

        val hint = when {
            lowerText.contains("permission") || lowerText.contains("not granted") -> {
                when (route.toolName) {
                    "send_message", "send_whatsapp_business_message" ->
                        "(Enable SMS/Contacts permissions in Android Settings > Apps > PocketMCP > Permissions)"
                    "make_call" ->
                        "(Enable CALL_PHONE permission in Android Settings > Apps > PocketMCP > Permissions)"
                    "search_contacts" ->
                        "(Enable READ_CONTACTS permission in Android Settings > Apps > PocketMCP > Permissions)"
                    "get_location" ->
                        "(Enable Location permission in Android Settings > Apps > PocketMCP > Permissions)"
                    "flashlight" ->
                        "(Enable CAMERA permission in Android Settings > Apps > PocketMCP > Permissions)"
                    "take_screenshot", "scroll_screen", "tap", "screen_state", "global_action" ->
                        "(Enable PocketMCP Accessibility Service in Android Settings > Accessibility)"
                    "notifications", "transcribe_whatsapp_audio" ->
                        "(Enable PocketMCP Notification Listener in Android Settings > Notifications > Notification access)"
                    "voice_record", "transcribe_audio", "transcribe_file" ->
                        "(Enable RECORD_AUDIO permission in Android Settings > Apps > PocketMCP > Permissions)"
                    else -> "(Check app permissions in Android Settings)"
                }
            }
            lowerText.contains("accessibility") -> "(Enable PocketMCP Accessibility Service in Android Settings > Accessibility)"
            lowerText.contains("notification listener") -> "(Enable PocketMCP Notification Listener in Android Settings > Notifications > Notification access)"
            else -> null
        }

        return if (hint != null) {
            resultError("$text\n\nHint: $hint")
        } else {
            result
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun normalizePlatform(rawPlatform: String, defaultWhatsappType: String): String? {
        val compact = rawPlatform.lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), "")
        if (compact.isBlank()) return null
        val looksLikeWhatsApp = compact.contains("whatsapp") || compact.contains("whatsap") || compact.contains("watsapp") || compact == "wa"
        if (looksLikeWhatsApp) {
            return when {
                compact.contains("business") || compact.contains("bussiness") || compact.contains("buisness") || compact.contains("w4b") -> "whatsapp_business"
                compact.contains("personal") || compact.contains("normal") || compact.contains("regular") -> "whatsapp_personal"
                else -> if (defaultWhatsappType == "personal") "whatsapp_personal" else "whatsapp_business"
            }
        }
        return when (compact) {
            "instagram", "insta", "ig" -> "instagram"
            "messenger", "facebookmessenger", "fbmessenger", "fbchat", "fb" -> "messenger"
            "googlemessages", "messages", "sms", "text" -> "sms"
            else -> null
        }
    }

    private fun normalizeWhatsappType(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val compact = raw.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return when {
            compact.contains("personal") || compact == "normal" || compact == "regular" -> "personal"
            compact.contains("business") || compact.contains("bussiness") || compact.contains("buisness") || compact == "w4b" -> "business"
            else -> null
        }
    }

    private fun normalizeSearchAppName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val compact = raw.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return when {
            compact.contains("chrome") -> "Chrome"
            compact.contains("google") || compact.contains("browser") -> "Google"
            compact.contains("maps") || compact.contains("map") -> "Maps"
            compact.contains("youtube") || compact == "yt" -> "YouTube"
            else -> null
        }
    }

    private fun normalizeAppNameForInstall(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val compact = raw.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return when {
            compact.contains("instagram") || compact == "insta" || compact == "ig" -> "Instagram"
            compact.contains("youtube") || compact == "yt" -> "YouTube"
            compact.contains("tiktok") || compact == "tiktok" -> "TikTok"
            compact.contains("twitter") || compact == "x" -> "Twitter"
            compact.contains("facebook") || compact == "fb" -> "Facebook"
            compact.contains("snapchat") -> "Snapchat"
            compact.contains("telegram") || compact == "tg" -> "Telegram"
            compact.contains("signal") -> "Signal"
            compact.contains("discord") -> "Discord"
            compact.contains("messenger") -> "Messenger"
            compact.contains("linkedin") -> "LinkedIn"
            compact.contains("reddit") -> "Reddit"
            compact.contains("pinterest") -> "Pinterest"
            compact.contains("twitch") -> "Twitch"
            compact.contains("spotify") -> "Spotify"
            compact.contains("netflix") -> "Netflix"
            compact.contains("primevideo") || compact.contains("amazonprime") -> "Amazon Prime Video"
            compact.contains("chrome") -> "Chrome"
            compact.contains("firefox") -> "Firefox"
            compact.contains("gmail") || compact == "mail" -> "Gmail"
            compact.contains("maps") || compact.contains("googlemaps") -> "Google Maps"
            compact.contains("uber") -> "Uber"
            compact.contains("lyft") -> "Lyft"
            compact.contains("amazon") -> "Amazon Shopping"
            compact.contains("ebay") -> "eBay"
            compact.contains("aliexpress") -> "AliExpress"
            compact.contains("zoom") -> "Zoom"
            compact.contains("teams") || compact.contains("microsoftteams") -> "Microsoft Teams"
            compact.contains("slack") -> "Slack"
            compact.contains("notion") -> "Notion"
            compact.contains("todoist") -> "Todoist"
            compact.contains("evernote") -> "Evernote"
            compact.contains("drive") || compact.contains("googledrive") -> "Google Drive"
            compact.contains("dropbox") -> "Dropbox"
            compact.contains("duolingo") -> "Duolingo"
            compact.contains("photos") || compact.contains("googlephotos") -> "Google Photos"
            compact.contains("calculator") || compact == "calc" -> "Calculator"
            compact.contains("calendar") || compact.contains("googlecalendar") -> "Calendar"
            compact.contains("contacts") -> "Contacts"
            compact.contains("phone") || compact.contains("dialer") -> "Phone"
            else -> raw.trim().replaceFirstChar { it.uppercase() }
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
        if (quoted != null) return quoted
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
        val confidence: Int,
        val arguments: JsonObject
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
