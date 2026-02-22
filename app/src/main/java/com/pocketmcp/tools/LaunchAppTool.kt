package com.pocketmcp.tools

import android.content.Context
import android.content.Intent
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.text.lowercase
import kotlin.text.trim
import kotlin.text.contains
import kotlin.text.split
import kotlin.text.isBlank
import kotlin.text.isNotBlank
import kotlin.text.replace
import kotlin.text.Regex

class LaunchAppTool : McpToolHandler {
    override val name = "launch_app"
    override val description = "Open or close an app by package name, app name, or natural language voice command. Supports commands like 'open youtube', 'launch google', 'start instagram'."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "One of: open, close, launch, start. Default: open.")
            }
            putJsonObject("package_name") {
                put("type", "string")
                put("description", "App package id, e.g. com.google.android.youtube.")
            }
            putJsonObject("app_name") {
                put("type", "string")
                put("description", "Launcher app name, e.g. YouTube.")
            }
            putJsonObject("voice_command") {
                put("type", "string")
                put("description", "Natural language voice command, e.g. 'open youtube', 'launch google maps', 'start instagram'.")
            }
            putJsonObject("verify_launch") {
                put("type", "boolean")
                put("description", "Verify app launch with screen state checks (default: true).")
            }
            putJsonObject("wait_timeout_ms") {
                put("type", "integer")
                put("description", "Launch verification timeout in milliseconds (default: 8000, range: 2000-15000).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = (argString(args, "action") ?: "open").trim().lowercase()
        if (action !in setOf("open", "close", "launch", "start")) {
            return resultError("Invalid action '$action'. Use open, close, launch, or start.")
        }

        val normalizedAction = if (action in setOf("launch", "start")) "open" else action
        val verifyLaunch = argBoolean(args, "verify_launch") ?: true
        val waitTimeoutMs = (argInt(args, "wait_timeout_ms") ?: 8000).coerceIn(2000, 15000).toLong()
        
        // Handle voice command input
        val voiceCommand = argString(args, "voice_command")?.trim()?.lowercase()
        var packageNameArg = argString(args, "package_name")?.trim().orEmpty()
        var appNameArg = argString(args, "app_name")?.trim().orEmpty()
        
        if (voiceCommand?.isNotBlank() == true) {
            val parsed = parseVoiceCommand(voiceCommand)
            if (parsed != null) {
                appNameArg = parsed.first
                packageNameArg = parsed.second.orEmpty()
            } else {
                return resultError("Could not understand voice command: '$voiceCommand'. Try 'open [app name]' or 'launch [app name]'.")
            }
        }
        
        if (packageNameArg.isBlank() && appNameArg.isBlank()) {
            return resultError("Provide package_name, app_name, or voice_command.")
        }

        val apps = getLaunchableApps(context)
        val resolved = resolveLaunchableApp(
            apps = apps,
            packageNameArg = packageNameArg,
            appNameArg = appNameArg
        )
        val matched = resolved.match ?: return resultError(buildNoMatchMessage(packageNameArg, appNameArg, resolved.suggestions))
        val packageManager = context.packageManager

        val launchIntent = packageManager.getLaunchIntentForPackage(matched.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return resultError("App '${matched.packageName}' cannot be launched.")

        return try {
            if (normalizedAction == "open") {
                val canVerify = PhoneAccessibilityService.isEnabled() && verifyLaunch
                val checkpoints = mutableListOf<ScreenStateCheckpoint>()
                
                if (canVerify) {
                    checkpoints += captureCheckpoint("before_open", matched.packageName)
                    if (checkpoints.last().actualPackage == matched.packageName) {
                        val payload = buildJsonObject {
                            put("action", "open")
                            put("success", true)
                            put("already_open", true)
                            put("screen_state_verified", true)
                            put("package_name", matched.packageName)
                            put("app_name", matched.appName)
                            put("voice_command", voiceCommand ?: "")
                            put(
                                "screen_checkpoints",
                                kotlinx.serialization.json.buildJsonArray {
                                    checkpoints.forEach { add(it.toJson()) }
                                }
                            )
                        }
                        return resultJson(payload)
                    }
                }

                context.startActivity(launchIntent)
                
                if (canVerify) {
                    waitForForegroundPackage(setOf(matched.packageName), timeoutMs = waitTimeoutMs)
                        ?: return resultError(
                            "Safety check failed: app launch started but foreground package did not match ${matched.packageName} within ${waitTimeoutMs}ms."
                        )
                    checkpoints += captureCheckpoint("after_open", matched.packageName)
                }

                val payload = buildJsonObject {
                    put("action", "open")
                    put("success", true)
                    put("package_name", matched.packageName)
                    put("app_name", matched.appName)
                    put("voice_command", voiceCommand ?: "")
                    put("screen_state_verified", canVerify)
                    put("launch_verified", canVerify)
                    if (canVerify) {
                        put(
                            "screen_checkpoints",
                            kotlinx.serialization.json.buildJsonArray {
                                checkpoints.forEach { add(it.toJson()) }
                            }
                        )
                    } else {
                        put(
                            "note",
                            "Accessibility is disabled or verification disabled; launch succeeded but screen_state verification was skipped."
                        )
                    }
                }
                resultJson(payload)
            } else {
                if (!PhoneAccessibilityService.isEnabled()) {
                    return resultError(
                        "Accessibility automation is disabled. Enable PocketMCP Accessibility Service in Android Accessibility settings."
                    )
                }

                // Bring target app to foreground first, then close the current app card.
                context.startActivity(launchIntent)
                delay(420)
                val closed = closeForegroundAppBestEffort()
                val payload = buildJsonObject {
                    put("action", "close")
                    put("success", closed)
                    put("package_name", matched.packageName)
                    put("app_name", matched.appName)
                    put("voice_command", voiceCommand ?: "")
                    put("note", "Best effort only. Android does not allow force-stop for third-party apps.")
                }
                resultJson(payload)
            }
        } catch (error: Exception) {
            resultError("Failed to $normalizedAction app: ${error.message ?: "unknown error"}")
        }
    }

    private fun parseVoiceCommand(command: String): Pair<String, String?>? {
        val cleanCommand = command.trim().lowercase()
        
        // Common app name mappings for better recognition
        val appMappings = mapOf(
            "youtube" to "com.google.android.youtube",
            "google" to "com.google.android.googlequicksearchbox",
            "chrome" to "com.android.chrome",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "twitter" to "com.twitter.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "calculator" to "com.android.calculator2",
            "settings" to "com.android.settings",
            "play store" to "com.android.vending",
            "photos" to "com.google.android.apps.photos",
            "drive" to "com.google.android.apps.docs.editors.docs",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.android.deskclock",
            "weather" to "com.google.android.apps.weather",
            "discord" to "com.discord",
            "slack" to "com.Slack",
            "zoom" to "us.zoom.videomeetings",
            "teams" to "com.microsoft.teams"
        )
        
        // Pattern matching for voice commands
        val patterns = listOf(
            Regex("^(open|launch|start|run)\\s+(.+)$"),
            Regex("^(can you|please)\\s+(open|launch|start|run)\\s+(.+)$"),
            Regex("^(i want to|let me)\\s+(open|launch|start|run)\\s+(.+)$"),
            Regex("^(.+)$") // Fallback: just take the last word as app name
        )
        
        for (pattern in patterns) {
            val matchResult = pattern.find(cleanCommand)
            if (matchResult != null) {
                val appName = when (pattern.pattern.count { it == '(' }) {
                    2 -> matchResult.groupValues[2].trim() // "open appname"
                    3 -> matchResult.groupValues[3].trim() // "can you open appname"
                    4 -> matchResult.groupValues[4].trim() // "i want to open appname"
                    else -> matchResult.groupValues[1].trim() // fallback
                }
                
                if (appName.isNotBlank()) {
                    // Check for exact mappings first
                    for ((key, value) in appMappings) {
                        if (appName.contains(key) || key.contains(appName)) {
                            return Pair(key, value)
                        }
                    }
                    
                    // Return the app name as-is for fuzzy matching
                    return Pair(appName, null)
                }
            }
        }
        
        return null
    }

    private fun buildNoMatchMessage(
        packageNameArg: String,
        appNameArg: String,
        suggestions: List<LaunchableApp>
    ): String {
        val query = if (packageNameArg.isNotBlank()) packageNameArg else appNameArg
        if (suggestions.isEmpty()) {
            return "No launchable app matched '$query'."
        }
        val preview = suggestions.joinToString(", ") { "${it.appName} (${it.packageName})" }
        return "No launchable app matched '$query'. Suggestions: $preview"
    }
}
