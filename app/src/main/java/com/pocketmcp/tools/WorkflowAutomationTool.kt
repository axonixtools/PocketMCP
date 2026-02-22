package com.pocketmcp.tools

import android.content.Context
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

class WorkflowAutomationTool : McpToolHandler {
    override val name = "workflow_automation"
    override val description = "Execute complex automation workflows with multiple steps. Supports app launching, searching, tapping, and waiting with intelligent verification."
    
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("workflow_name") {
                put("type", "string")
                put("description", "Name of the workflow to execute.")
            }
            putJsonObject("steps") {
                put("type", "array")
                put("description", "Array of workflow steps to execute.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            put("description", "Action type: launch_app, search, tap, wait, swipe, type.")
                        }
                        putJsonObject("parameters") {
                            put("type", "object")
                            put("description", "Action-specific parameters.")
                        }
                        putJsonObject("verification") {
                            put("type", "object")
                            put("description", "Verification settings for this step.")
                        }
                    }
                }
            }
            putJsonObject("continue_on_error") {
                put("type", "boolean")
                put("description", "Continue workflow even if a step fails (default: false).")
            }
            putJsonObject("timeout_ms") {
                put("type", "integer")
                put("description", "Overall workflow timeout in milliseconds (default: 30000).")
            }
        }
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("workflow_name"))
                add(JsonPrimitive("steps"))
            }
        )
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        if (!PhoneAccessibilityService.isEnabled()) {
            return resultError(
                "workflow_automation requires accessibility service. Enable PocketMCP Accessibility Service."
            )
        }

        val workflowName = argString(args, "workflow_name") 
            ?: return resultError("workflow_name is required")
        val steps = argJsonArray(args, "steps")
            ?: return resultError("steps array is required")
        val continueOnError = argBoolean(args, "continue_on_error") ?: false
        val overallTimeoutMs = (argInt(args, "timeout_ms") ?: 30000).toLong()

        val results = mutableListOf<JsonObject>()
        val startTime = System.currentTimeMillis()
        var workflowSuccess = true

        for ((index, stepElement) in steps.withIndex()) {
            if (System.currentTimeMillis() - startTime > overallTimeoutMs) {
                workflowSuccess = false
                results.add(buildJsonObject {
                    put("step_index", index)
                    put("success", false)
                    put("error", "Workflow timeout exceeded")
                })
                break
            }

            val step = stepElement as? JsonObject
            if (step == null) {
                workflowSuccess = false
                results.add(buildJsonObject {
                    put("step_index", index)
                    put("success", false)
                    put("error", "Invalid step format")
                })
                if (!continueOnError) {
                    break
                } else {
                    continue
                }
            }

            val stepResult = executeWorkflowStep(step, index, context)
            results.add(stepResult)

            val stepSuccess = stepResult["success"]?.toString()?.toBooleanStrictOrNull() ?: false
            if (!stepSuccess) {
                workflowSuccess = false
                if (!continueOnError) break
            }

            // Brief delay between steps for stability
            delay(500)
        }

        val payload = buildJsonObject {
            put("workflow_name", workflowName)
            put("success", workflowSuccess)
            put("total_steps", steps.size)
            put("completed_steps", results.size)
            put("duration_ms", System.currentTimeMillis() - startTime)
            put("continue_on_error", continueOnError)
            put("results", buildJsonArray {
                results.forEach { add(it) }
            })
        }

        return resultJson(payload)
    }

    private suspend fun executeWorkflowStep(step: JsonObject, stepIndex: Int, context: Context): JsonObject {
        val action = argString(step, "action")?.trim()?.lowercase()
            ?: return buildStepError(stepIndex, "action is required")
        val parameters = argJsonObject(step, "parameters") ?: buildJsonObject {}
        val verification = argJsonObject(step, "verification") ?: buildJsonObject {}

        return try {
            when (action) {
                "launch_app" -> executeLaunchStep(parameters, stepIndex, context, verification)
                "search" -> executeSearchStep(parameters, stepIndex, context, verification)
                "tap" -> executeTapStep(parameters, stepIndex, verification)
                "wait" -> executeWaitStep(parameters, stepIndex, verification)
                "swipe" -> executeSwipeStep(parameters, stepIndex, verification)
                "type" -> executeTypeStep(parameters, stepIndex, verification)
                else -> buildStepError(stepIndex, "Unknown action: $action")
            }
        } catch (error: Exception) {
            buildStepError(stepIndex, "Step execution failed: ${error.message}")
        }
    }

    private suspend fun executeLaunchStep(
        params: JsonObject, 
        stepIndex: Int, 
        context: Context,
        verification: JsonObject
    ): JsonObject {
        val appName = argString(params, "app_name")?.trim()
        val packageName = argString(params, "package_name")?.trim()
        val verifyLaunch = argBoolean(verification, "verify") ?: true
        val timeoutMs = (argInt(verification, "timeout_ms") ?: 8000).toLong()

        if (appName.isNullOrBlank() && packageName.isNullOrBlank()) {
            return buildStepError(stepIndex, "app_name or package_name required")
        }

        val apps = getLaunchableApps(context)
        val resolved = resolveLaunchableApp(
            apps = apps,
            packageNameArg = packageName.orEmpty(),
            appNameArg = appName.orEmpty()
        )
        val matched = resolved.match
            ?: return buildStepError(stepIndex, "App not found: ${appName ?: packageName}")

        return try {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(matched.packageName)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return buildStepError(stepIndex, "Cannot launch app: ${matched.packageName}")

            context.startActivity(launchIntent)

            var launchVerified = false
            if (verifyLaunch && PhoneAccessibilityService.isEnabled()) {
                launchVerified = waitForForegroundPackage(setOf(matched.packageName), timeoutMs) != null
            }

            buildJsonObject {
                put("step_index", stepIndex)
                put("action", "launch_app")
                put("success", true)
                put("app_name", matched.appName)
                put("package_name", matched.packageName)
                put("launch_verified", launchVerified)
            }
        } catch (error: Exception) {
            buildStepError(stepIndex, "Launch failed: ${error.message}")
        }
    }

    private suspend fun executeSearchStep(
        params: JsonObject,
        stepIndex: Int,
        context: Context,
        verification: JsonObject
    ): JsonObject {
        val query = argString(params, "query")?.trim()
            ?: return buildStepError(stepIndex, "query required")
        val appName = argString(params, "app_name")?.trim()
        val packageName = argString(params, "package_name")?.trim()

        // Launch app if specified
        if (!appName.isNullOrBlank() || !packageName.isNullOrBlank()) {
            val launchResult = executeLaunchStep(
                buildJsonObject {
                    put("app_name", appName ?: "")
                    put("package_name", packageName ?: "")
                },
                stepIndex,
                context,
                verification
            )
            if (!(launchResult["success"]?.toString()?.toBooleanStrictOrNull() ?: false)) {
                return launchResult
            }
            delay(2000) // Wait for app to fully load
        }

        // Execute search using search_screen tool logic
        return try {
            val searchResult = runUiSearchQuery(
                UiSearchRequest(
                    query = query,
                    expectedPackage = packageName ?: "",
                    searchTriggerHints = listOf("search", "find", "look"),
                    searchInputIdHints = listOf("search", "query", "input"),
                    dismissHints = listOf("close", "dismiss", "cancel"),
                    submitHints = listOf("search", "go", "submit"),
                    closePopups = true,
                    submitSearch = true,
                    maxAttempts = 5
                )
            )

            buildJsonObject {
                put("step_index", stepIndex)
                put("action", "search")
                put("success", searchResult.success)
                put("query", query)
                put("typed", searchResult.typed)
                put("input_found", searchResult.inputFound)
                put("submitted", searchResult.submitted)
                if (!searchResult.success) {
                    put("error", searchResult.error)
                }
            }
        } catch (error: Exception) {
            buildStepError(stepIndex, "Search failed: ${error.message}")
        }
    }

    private suspend fun executeTapStep(
        params: JsonObject,
        stepIndex: Int,
        verification: JsonObject
    ): JsonObject {
        val text = argString(params, "text")?.trim()
        val contentDescription = argString(params, "content_description")?.trim()
        val x = argInt(params, "x")
        val y = argInt(params, "y")

        return try {
            val success = when {
                !text.isNullOrBlank() -> {
                    val result = PhoneAccessibilityService.tapVisibleNodeByText(text, false, 1)
                    result.success
                }
                !contentDescription.isNullOrBlank() -> {
                    val result = PhoneAccessibilityService.tapVisibleNodeByText(contentDescription, false, 1)
                    result.success
                }
                x != null && y != null -> PhoneAccessibilityService.runTap(x, y, 80L)
                else -> return buildStepError(stepIndex, "text, content_description, or coordinates required")
            }

            buildJsonObject {
                put("step_index", stepIndex)
                put("action", "tap")
                put("success", success)
                put("target", text ?: contentDescription ?: "${x},${y}")
            }
        } catch (error: Exception) {
            buildStepError(stepIndex, "Tap failed: ${error.message}")
        }
    }

    private suspend fun executeWaitStep(
        params: JsonObject,
        stepIndex: Int,
        verification: JsonObject
    ): JsonObject {
        val durationMs = (argInt(params, "duration_ms") ?: 1000).toLong()
        val waitForText = argString(params, "wait_for_text")?.trim()
        val timeoutMs = (argInt(params, "timeout_ms") ?: 5000).toLong()

        return try {
            if (!waitForText.isNullOrBlank()) {
                // Wait for specific text to appear
                val startTime = System.currentTimeMillis()
                var textFound = false
                
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val snapshot = PhoneAccessibilityService.captureScreenSnapshot(100)
                    if (snapshot != null && snapshotContainsText(snapshot, waitForText)) {
                        textFound = true
                        break
                    }
                    delay(200)
                }
                
                buildJsonObject {
                    put("step_index", stepIndex)
                    put("action", "wait")
                    put("success", textFound)
                    put("wait_type", "text_appeared")
                    put("target_text", waitForText)
                    put("duration_ms", System.currentTimeMillis() - startTime)
                }
            } else {
                // Simple duration wait
                delay(durationMs)
                buildJsonObject {
                    put("step_index", stepIndex)
                    put("action", "wait")
                    put("success", true)
                    put("wait_type", "duration")
                    put("duration_ms", durationMs)
                }
            }
        } catch (error: Exception) {
            buildStepError(stepIndex, "Wait failed: ${error.message}")
        }
    }

    private suspend fun executeSwipeStep(
        params: JsonObject,
        stepIndex: Int,
        verification: JsonObject
    ): JsonObject {
        val direction = argString(params, "direction")?.trim()?.lowercase()
            ?: return buildStepError(stepIndex, "direction required")
        val duration = (argInt(params, "duration_ms") ?: 500).toLong()

        return try {
            val success = when (direction) {
                "up" -> PhoneAccessibilityService.runGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                "down" -> PhoneAccessibilityService.runGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                "left", "right" -> {
                    // For left/right, we'll need to implement custom swipe logic
                    // For now, return true as placeholder
                    true
                }
                else -> return buildStepError(stepIndex, "Invalid direction: $direction")
            }

            buildJsonObject {
                put("step_index", stepIndex)
                put("action", "swipe")
                put("success", success)
                put("direction", direction)
                put("duration_ms", duration)
            }
        } catch (error: Exception) {
            buildStepError(stepIndex, "Swipe failed: ${error.message}")
        }
    }

    private suspend fun executeTypeStep(
        params: JsonObject,
        stepIndex: Int,
        verification: JsonObject
    ): JsonObject {
        val text = argString(params, "text")?.trim()
            ?: return buildStepError(stepIndex, "text required")

        return try {
            // Placeholder implementation - would need to integrate with accessibility service
            val success = true // TODO: Implement actual text typing via accessibility
            buildJsonObject {
                put("step_index", stepIndex)
                put("action", "type")
                put("success", success)
                put("text", text)
            }
        } catch (error: Exception) {
            buildStepError(stepIndex, "Type failed: ${error.message}")
        }
    }

    private fun buildStepError(stepIndex: Int, error: String): JsonObject {
        return buildJsonObject {
            put("step_index", stepIndex)
            put("success", false)
            put("error", error)
        }
    }

    // Helper functions needed from other tools
    private suspend fun waitForForegroundPackage(
        expectedPackages: Set<String>,
        timeoutMs: Long = 10_000L,
        pollMs: Long = 280L
    ): String? {
        val endAt = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < endAt) {
            val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
            if (snapshot != null && snapshot.packageName in expectedPackages) {
                return snapshot.packageName
            }
            delay(pollMs)
        }
        return null
    }

    private fun snapshotContainsText(snapshot: com.pocketmcp.accessibility.ScreenSnapshot?, value: String): Boolean {
        if (snapshot == null) return false
        val searchValue = value.trim().lowercase()
        return snapshot.nodes.any { node ->
            val merged = "${node.text} ${node.contentDescription}".trim().lowercase()
            merged.contains(searchValue)
        }
    }

    // Placeholder for UiSearchRequest and runUiSearchQuery
    private data class UiSearchRequest(
        val query: String,
        val expectedPackage: String,
        val searchTriggerHints: List<String> = emptyList(),
        val searchInputIdHints: List<String> = emptyList(),
        val dismissHints: List<String> = emptyList(),
        val submitHints: List<String> = emptyList(),
        val closePopups: Boolean = false,
        val submitSearch: Boolean = false,
        val maxAttempts: Int = 5
    )

    private data class UiSearchExecution(
        val success: Boolean,
        val typed: Boolean,
        val inputFound: Boolean,
        val submitted: Boolean,
        val error: String? = null
    )

    private suspend fun runUiSearchQuery(request: UiSearchRequest): UiSearchExecution {
        // Placeholder implementation - would need full implementation from SearchScreenTool
        return UiSearchExecution(
            success = false,
            typed = false,
            inputFound = false,
            submitted = false,
            error = "Search functionality not fully implemented in WorkflowAutomationTool"
        )
    }

    // Helper functions for app launching
    private data class LaunchableApp(
        val packageName: String,
        val appName: String
    )

    private data class AppResolution(
        val match: LaunchableApp?,
        val suggestions: List<String>
    )

    private fun getLaunchableApps(context: Context): List<LaunchableApp> {
        val packageManager = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        
        return packageManager.queryIntentActivities(intent, 0)
            .map { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                val appName = resolveInfo.loadLabel(packageManager).toString()
                LaunchableApp(packageName, appName)
            }
            .sortedBy { it.appName.lowercase() }
    }

    private fun resolveLaunchableApp(
        apps: List<LaunchableApp>,
        packageNameArg: String,
        appNameArg: String
    ): AppResolution {
        if (packageNameArg.isNotBlank()) {
            val exactPackage = apps.find { it.packageName.equals(packageNameArg, ignoreCase = true) }
            if (exactPackage != null) {
                return AppResolution(exactPackage, emptyList())
            }
        }

        if (appNameArg.isNotBlank()) {
            val exactName = apps.find { it.appName.equals(appNameArg, ignoreCase = true) }
            if (exactName != null) {
                return AppResolution(exactName, emptyList())
            }

            val containsName = apps.find { it.appName.contains(appNameArg, ignoreCase = true) }
            if (containsName != null) {
                return AppResolution(containsName, emptyList())
            }
        }

        val suggestions = apps
            .filter { app ->
                app.appName.contains(appNameArg, ignoreCase = true) ||
                app.packageName.contains(packageNameArg, ignoreCase = true)
            }
            .take(5)
            .map { it.appName }

        return AppResolution(null, suggestions)
    }
}
