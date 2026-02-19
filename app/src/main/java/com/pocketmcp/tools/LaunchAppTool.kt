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

class LaunchAppTool : McpToolHandler {
    override val name = "launch_app"
    override val description = "Open or close an app by package name or app name."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "One of: open, close. Default: open.")
            }
            putJsonObject("package_name") {
                put("type", "string")
                put("description", "App package id, e.g. com.google.android.youtube.")
            }
            putJsonObject("app_name") {
                put("type", "string")
                put("description", "Launcher app name, e.g. YouTube.")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = (argString(args, "action") ?: "open").trim().lowercase()
        if (action !in setOf("open", "close")) {
            return resultError("Invalid action '$action'. Use open or close.")
        }

        val packageNameArg = argString(args, "package_name")?.trim().orEmpty()
        val appNameArg = argString(args, "app_name")?.trim().orEmpty()
        if (packageNameArg.isBlank() && appNameArg.isBlank()) {
            return resultError("Provide package_name or app_name.")
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
            if (action == "open") {
                val canVerify = PhoneAccessibilityService.isEnabled()
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
                    waitForForegroundPackage(setOf(matched.packageName), timeoutMs = 8_000L)
                        ?: return resultError(
                            "Safety check failed: app launch started but foreground package did not match ${matched.packageName}."
                        )
                    checkpoints += captureCheckpoint("after_open", matched.packageName)
                }

                val payload = buildJsonObject {
                    put("action", "open")
                    put("success", true)
                    put("package_name", matched.packageName)
                    put("app_name", matched.appName)
                    put("screen_state_verified", canVerify)
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
                            "Accessibility is disabled; launch succeeded but screen_state verification was skipped."
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
                    put("note", "Best effort only. Android does not allow force-stop for third-party apps.")
                }
                resultJson(payload)
            }
        } catch (error: Exception) {
            resultError("Failed to $action app: ${error.message ?: "unknown error"}")
        }
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
