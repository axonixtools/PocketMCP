package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class PresetAppActionsTool : McpToolHandler {
    override val name = "app_actions"
    override val description = "Run predefined app actions from local JSON presets for easier, predictable inputs"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("mode") {
                put("type", "string")
                put("description", "Mode: 'run' (default), 'list_apps', 'list_actions'")
            }
            putJsonObject("app") {
                put("type", "string")
                put("description", "Preset app key (e.g. whatsapp_business, instagram, youtube, x)")
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "Preset action key for selected app")
            }
            putJsonObject("dry_run") {
                put("type", "boolean")
                put("description", "If true, return resolved tool + arguments without executing")
            }

            // Common pass-through fields used by presets.
            putJsonObject("contact_name") { put("type", "string") }
            putJsonObject("message") { put("type", "string") }
            putJsonObject("whatsapp_type") { put("type", "string") }
            putJsonObject("query") { put("type", "string") }
            putJsonObject("content") { put("type", "string") }
            putJsonObject("post_url") { put("type", "string") }
            putJsonObject("video_id") { put("type", "string") }
            putJsonObject("phone_number") { put("type", "string") }
            putJsonObject("username") { put("type", "string") }
            putJsonObject("platform") { put("type", "string") }
            putJsonObject("strict_contact_match") { put("type", "boolean") }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val mode = (argString(args, "mode") ?: "run").trim().lowercase()
        val root = loadPresetRoot(context) ?: return resultError(
            "Preset file missing or invalid: $PRESET_FILE_NAME"
        )
        val apps = root["apps"]?.jsonObject ?: return resultError(
            "Preset file is invalid: missing 'apps' object"
        )

        return when (mode) {
            "list_apps" -> listApps(apps)
            "list_actions" -> listActions(args, apps)
            "run" -> runPreset(args, apps, context)
            else -> resultError("Invalid mode '$mode'. Use run, list_apps, or list_actions")
        }
    }

    private fun listApps(apps: JsonObject): McpToolCallResult {
        val payload = buildJsonObject {
            put("mode", "list_apps")
            put("count", apps.size)
            putJsonArray("apps") {
                apps.toSortedMap().forEach { (key, value) ->
                    val appObject = value as? JsonObject ?: JsonObject(emptyMap())
                    val actions = appObject["actions"] as? JsonObject ?: JsonObject(emptyMap())
                    add(
                        buildJsonObject {
                            put("app", key)
                            put("description", appObject.stringValue("description") ?: "")
                            put("actions_count", actions.size)
                        }
                    )
                }
            }
        }
        return resultJson(payload)
    }

    private fun listActions(args: JsonObject?, apps: JsonObject): McpToolCallResult {
        val appKey = argString(args, "app")?.trim()?.lowercase()
            ?: return resultError("app is required for mode=list_actions")
        val appObject = apps[appKey] as? JsonObject
            ?: return resultError("Unknown app preset: $appKey")
        val actions = appObject["actions"] as? JsonObject
            ?: return resultError("App preset '$appKey' has no actions")

        val payload = buildJsonObject {
            put("mode", "list_actions")
            put("app", appKey)
            put("description", appObject.stringValue("description") ?: "")
            putJsonArray("actions") {
                actions.toSortedMap().forEach { (actionKey, actionValue) ->
                    val actionObject = actionValue as? JsonObject ?: JsonObject(emptyMap())
                    add(
                        buildJsonObject {
                            put("action", actionKey)
                            put("description", actionObject.stringValue("description") ?: "")
                            put("tool", actionObject.stringValue("tool") ?: "")
                            put(
                                "required_inputs",
                                actionObject.stringArray("required_inputs").toJsonArray()
                            )
                            put(
                                "allowed_inputs",
                                actionObject.stringArray("allowed_inputs").toJsonArray()
                            )
                        }
                    )
                }
            }
        }
        return resultJson(payload)
    }

    private suspend fun runPreset(
        args: JsonObject?,
        apps: JsonObject,
        context: Context
    ): McpToolCallResult {
        val appKey = argString(args, "app")?.trim()?.lowercase()
            ?: return resultError("app is required for mode=run")
        val actionKey = argString(args, "action")?.trim()?.lowercase()
            ?: return resultError("action is required for mode=run")
        val dryRun = argBoolean(args, "dry_run") ?: false

        val appObject = apps[appKey] as? JsonObject
            ?: return resultError("Unknown app preset: $appKey")
        val actions = appObject["actions"] as? JsonObject
            ?: return resultError("App preset '$appKey' has no actions")
        val actionObject = actions[actionKey] as? JsonObject
            ?: return resultError("Unknown action '$actionKey' for app '$appKey'")

        val toolName = actionObject.stringValue("tool")
            ?: return resultError("Preset '$appKey/$actionKey' is missing target tool")
        val defaults = actionObject["defaults"] as? JsonObject ?: JsonObject(emptyMap())
        val allowedInputs = actionObject.stringArray("allowed_inputs")
        val requiredInputs = actionObject.stringArray("required_inputs")

        val merged = LinkedHashMap<String, JsonElement>()
        defaults.forEach { (key, value) -> merged[key] = value }
        allowedInputs.forEach { inputKey ->
            val inputValue = args?.get(inputKey)
            if (inputValue != null) {
                merged[inputKey] = inputValue
            }
        }

        val missing = requiredInputs.filter { key ->
            val value = merged[key] ?: return@filter true
            if (value !is JsonPrimitive) return@filter false
            if (!value.isString) return@filter false
            value.contentOrNull.isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            return resultError("Missing required inputs for $appKey/$actionKey: ${missing.joinToString(", ")}")
        }

        val resolvedArgs = buildJsonObject {
            merged.forEach { (key, value) -> put(key, value) }
        }

        if (dryRun) {
            val payload = buildJsonObject {
                put("mode", "run")
                put("dry_run", true)
                put("app", appKey)
                put("action", actionKey)
                put("tool", toolName)
                put("arguments", resolvedArgs)
            }
            return resultJson(payload)
        }

        val delegate = resolveDelegate(toolName)
            ?: return resultError("Preset target tool is not supported by app_actions: $toolName")
        return delegate.execute(resolvedArgs, context)
    }

    private fun resolveDelegate(toolName: String): McpToolHandler? {
        return when (toolName) {
            "send_whatsapp_message" -> WhatsAppSendTool()
            "send_whatsapp_business_message" -> WhatsAppBusinessSendTool()
            "social_media" -> SocialMediaTool()
            "send_message" -> MessagingTool()
            "launch_app" -> LaunchAppTool()
            "whatsapp_automation" -> WhatsAppAutomationTool()
            "transcribe_audio" -> TranscribeAudioTool()
            "transcribe_file" -> TranscribeFileTool()
            "transcribe_whatsapp_audio" -> WhatsAppAudioTranscribeTool()
            "human_command" -> HumanCommandTool()
            else -> null
        }
    }

    private fun loadPresetRoot(context: Context): JsonObject? {
        return try {
            val rawJson = context.assets.open(PRESET_FILE_NAME).bufferedReader().use { it.readText() }
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.stringValue(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.stringArray(key: String): List<String> {
        val raw = this[key] as? JsonArray ?: return emptyList()
        return raw.mapNotNull { element -> element.jsonPrimitive.contentOrNull }
    }

    private fun List<String>.toJsonArray(): JsonArray {
        return buildJsonArray {
            this@toJsonArray.forEach { add(JsonPrimitive(it)) }
        }
    }

    companion object {
        private const val PRESET_FILE_NAME = "app_action_presets.json"
    }
}
