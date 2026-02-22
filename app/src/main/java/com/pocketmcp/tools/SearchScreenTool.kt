package com.pocketmcp.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.accessibility.ScreenSnapshot
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SearchScreenTool : McpToolHandler {
    override val name = "search_screen"
    override val description =
        "Find a search input on the current screen and type a query with strict screen_state safety checks."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Optional. Use 'search'.")
            }
            putJsonObject("query") {
                put("type", "string")
                put("description", "Text to search for in the current app screen.")
            }
            putJsonObject("app_name") {
                put("type", "string")
                put("description", "Optional app name to open first (for example: Chrome, Google).")
            }
            putJsonObject("package_name") {
                put("type", "string")
                put("description", "Optional package name to open first (for example: com.android.chrome).")
            }
            putJsonObject("wait_timeout_ms") {
                put("type", "integer")
                put("description", "Foreground wait timeout when opening app (default: 12000, range: 2000-30000).")
            }
            putJsonObject("results_timeout_ms") {
                put("type", "integer")
                put("description", "Timeout for confirming search results after submit (default: 12000, range: 2000-30000).")
            }
            putJsonObject("max_attempts") {
                put("type", "integer")
                put("description", "Retry attempts for finding search input and typing query (default: 8, range: 1-16).")
            }
            putJsonObject("close_popups") {
                put("type", "boolean")
                put("description", "Close common popups before searching (default: true).")
            }
            putJsonObject("submit") {
                put("type", "boolean")
                put("description", "Submit search after typing query (default: true).")
            }
            putJsonObject("search_trigger_hints") {
                put("type", "array")
                put(
                    "description",
                    "Optional text/content-description hints used to find a search trigger button."
                )
                putJsonObject("items") {
                    put("type", "string")
                }
            }
            putJsonObject("search_input_id_hints") {
                put("type", "array")
                put(
                    "description",
                    "Optional view-id hints used to find the search input field."
                )
                putJsonObject("items") {
                    put("type", "string")
                }
            }
            putJsonObject("dismiss_hints") {
                put("type", "array")
                put("description", "Optional dismiss/close button hints for popup handling.")
                putJsonObject("items") {
                    put("type", "string")
                }
            }
            putJsonObject("submit_hints") {
                put("type", "array")
                put("description", "Optional submit button/action hints.")
                putJsonObject("items") {
                    put("type", "string")
                }
            }
        }
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("query"))
            }
        )
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        if (!PhoneAccessibilityService.isEnabled()) {
            return resultError(
                "search_screen requires accessibility screen-state checks. Enable PocketMCP Accessibility Service."
            )
        }

        val action = argString(args, "action")?.trim()?.lowercase()
        if (!action.isNullOrBlank() && action != "search") {
            return resultError("Unsupported action '$action'. Use action='search' or omit it.")
        }

        val query = argString(args, "query")?.trim()
            ?: return resultError("Query is required")
        if (query.isBlank()) {
            return resultError("Query cannot be empty")
        }

        val maxAttempts = (argInt(args, "max_attempts") ?: 8).coerceIn(1, 16)
        val waitTimeoutMs = (argInt(args, "wait_timeout_ms") ?: 12_000).coerceIn(2_000, 30_000).toLong()
        val resultsTimeoutMs = (argInt(args, "results_timeout_ms") ?: 12_000).coerceIn(2_000, 30_000).toLong()
        val closePopups = argBoolean(args, "close_popups") ?: true
        val submitSearch = argBoolean(args, "submit") ?: true
        val packageNameArg = argString(args, "package_name")?.trim().orEmpty()
        val appNameArg = argString(args, "app_name")?.trim().orEmpty()
        val triggerHints = argStringList(args, "search_trigger_hints")
            ?.ifEmpty { UiSearchDefaults.DEFAULT_TRIGGER_HINTS }
            ?: UiSearchDefaults.DEFAULT_TRIGGER_HINTS
        val inputIdHints = argStringList(args, "search_input_id_hints")
            ?.ifEmpty { UiSearchDefaults.DEFAULT_INPUT_ID_HINTS }
            ?: UiSearchDefaults.DEFAULT_INPUT_ID_HINTS
        val dismissHints = argStringList(args, "dismiss_hints")
            ?.ifEmpty { UiSearchDefaults.DEFAULT_DISMISS_HINTS }
            ?: UiSearchDefaults.DEFAULT_DISMISS_HINTS
        val submitHints = argStringList(args, "submit_hints")
            ?.ifEmpty { UiSearchDefaults.DEFAULT_SUBMIT_HINTS }
            ?: UiSearchDefaults.DEFAULT_SUBMIT_HINTS

        val checkpoints = mutableListOf<ScreenStateCheckpoint>()
        var expectedPackage = ""
        var resolvedAppName: String? = null

        if (packageNameArg.isNotBlank() || appNameArg.isNotBlank()) {
            val apps = getLaunchableApps(context)
            val resolved = resolveLaunchableApp(
                apps = apps,
                packageNameArg = packageNameArg.ifBlank { null },
                appNameArg = appNameArg.ifBlank { null }
            )
            val match = resolved.match
                ?: return resultError(buildNoMatchMessage(packageNameArg, appNameArg, resolved.suggestions))
            if (!launchApp(context, match.packageName)) {
                return resultError("Failed to open app '${match.appName}' (${match.packageName}).")
            }
            expectedPackage = match.packageName
            resolvedAppName = match.appName
            waitForForegroundPackage(setOf(expectedPackage), timeoutMs = waitTimeoutMs)
                ?: return resultError("Safety check failed: app '$expectedPackage' did not reach foreground.")
            checkpoints += captureCheckpoint("after_open_foreground", expectedPackage)
            val readySnapshot = waitForScreenReady(expectedPackage = expectedPackage, timeoutMs = waitTimeoutMs)
                ?: return resultError("Safety check failed: app '$expectedPackage' opened but screen did not become ready.")
            checkpoints += ScreenStateCheckpoint(
                step = "after_open_ready",
                expectedPackage = expectedPackage,
                actualPackage = readySnapshot.packageName,
                matchedExpectedPackage = readySnapshot.packageName == expectedPackage,
                highlights = snapshotHighlights(readySnapshot)
            )
        } else {
            val beforeSnapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
                ?: return resultError(
                    "search_screen requires an active screen state. Unlock your phone and open the target app first."
                )
            expectedPackage = beforeSnapshot.packageName
            if (expectedPackage.isBlank()) {
                return resultError("Unable to resolve foreground package from screen state.")
            }
            checkpoints += captureCheckpoint("before_search", expectedPackage)
        }

        if (checkpoints.none { it.step == "before_search" }) {
            checkpoints += captureCheckpoint("before_search", expectedPackage)
        }

        val result = runUiSearchQuery(
            UiSearchRequest(
                query = query,
                expectedPackage = expectedPackage,
                searchTriggerHints = triggerHints,
                searchInputIdHints = inputIdHints,
                dismissHints = dismissHints,
                submitHints = submitHints,
                closePopups = closePopups,
                submitSearch = submitSearch,
                maxAttempts = maxAttempts
            )
        )

        checkpoints += captureCheckpoint("after_query_entry", expectedPackage)

        if (!result.success) {
            return resultError("Search aborted: ${result.error ?: "screen-state verification failed"}")
        }

        val resultsSnapshot = if (submitSearch) {
            waitForSearchResults(
                expectedPackage = expectedPackage,
                query = query,
                timeoutMs = resultsTimeoutMs
            ) ?: return resultError(
                "Search was submitted but results were not confirmed yet. Keep the app visible and retry."
            )
        } else {
            null
        }

        if (resultsSnapshot != null) {
            checkpoints += ScreenStateCheckpoint(
                step = "after_results_ready",
                expectedPackage = expectedPackage,
                actualPackage = resultsSnapshot.packageName,
                matchedExpectedPackage = resultsSnapshot.packageName == expectedPackage,
                highlights = snapshotHighlights(resultsSnapshot)
            )
        } else {
            checkpoints += captureCheckpoint("after_search", expectedPackage)
        }

        val payload = buildJsonObject {
            put("action", "search")
            put("success", true)
            put("task_completed", true)
            put("query", query)
            put("expected_package", expectedPackage)
            put("actual_package", result.actualPackage)
            put("typed", result.typed)
            put("input_found", result.inputFound)
            put("trigger_tapped", result.triggerTapped)
            put("popup_dismissed", result.popupDismissed)
            put("submitted", result.submitted)
            put("close_popups", closePopups)
            put("submit", submitSearch)
            put("results_ready", resultsSnapshot != null || !submitSearch)
            put(
                "screen_state_verified",
                result.queryVisible &&
                    result.actualPackage == expectedPackage &&
                    (resultsSnapshot != null || !submitSearch)
            )
            if (!resolvedAppName.isNullOrBlank()) {
                put("app_name", resolvedAppName)
            }
            put(
                "screen_checkpoints",
                buildJsonArray {
                    checkpoints.forEach { add(it.toJson()) }
                }
            )
        }
        return resultJson(payload)
    }

    private suspend fun waitForScreenReady(
        expectedPackage: String,
        timeoutMs: Long,
        pollMs: Long = 280L
    ): ScreenSnapshot? {
        val endAt = System.currentTimeMillis() + timeoutMs
        var lastSnapshot: ScreenSnapshot? = null
        var stableCount = 0
        val requiredStableCount = 3 // Require 3 consecutive stable snapshots
        
        while (System.currentTimeMillis() < endAt) {
            val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
            if (snapshot != null && snapshot.packageName == expectedPackage) {
                val highlights = snapshotHighlights(snapshot, limit = 16)
                
                // Check if screen has enough content and is stable
                if (snapshot.nodes.size >= 6 && highlights.isNotEmpty()) {
                    // Check for stability - compare with last snapshot
                    if (lastSnapshot != null) {
                        val isStable = isScreenStable(lastSnapshot, snapshot)
                        if (isStable) {
                            stableCount++
                            if (stableCount >= requiredStableCount) {
                                return snapshot
                            }
                        } else {
                            stableCount = 0 // Reset if screen changed
                        }
                    }
                    lastSnapshot = snapshot
                }
            }
            delay(pollMs)
        }
        return lastSnapshot?.takeIf { it.packageName == expectedPackage && it.nodes.size >= 6 }
    }

    private suspend fun waitForSearchResults(
        expectedPackage: String,
        query: String,
        timeoutMs: Long,
        pollMs: Long = 280L
    ): ScreenSnapshot? {
        val endAt = System.currentTimeMillis() + timeoutMs
        var lastSnapshot: ScreenSnapshot? = null
        var resultFoundCount = 0
        val requiredResultCount = 2 // Require 2 consecutive confirmations
        
        while (System.currentTimeMillis() < endAt) {
            val snapshot = PhoneAccessibilityService.captureScreenSnapshot(140)
            if (snapshot == null || snapshot.packageName != expectedPackage) {
                delay(pollMs)
                continue
            }
            
            // Recursive verification: check multiple conditions for search results
            val hasQueryText = snapshotContainsText(snapshot, query)
            val hasResultIndicators = hasSearchResultIndicators(snapshot)
            val hasEnoughContent = snapshot.nodes.size >= 10
            val isLikelyResults = isLikelySearchResultsScreen(snapshot, query)
            
            val allConditionsMet = hasQueryText && (hasResultIndicators || hasEnoughContent) && isLikelyResults
            
            if (allConditionsMet) {
                resultFoundCount++
                if (resultFoundCount >= requiredResultCount) {
                    return snapshot
                }
            } else {
                resultFoundCount = 0
            }
            
            lastSnapshot = snapshot
            delay(pollMs)
        }
        return lastSnapshot?.takeIf { 
            it.packageName == expectedPackage && 
            snapshotContainsText(it, query) && 
            isLikelySearchResultsScreen(it, query)
        }
    }

    private fun isScreenStable(previous: ScreenSnapshot, current: ScreenSnapshot): Boolean {
        // Compare screen snapshots to determine if they're stable
        if (previous.nodes.size != current.nodes.size) {
            return false
        }
        
        // Check if key elements are in similar positions
        val previousHighlights = snapshotHighlights(previous, limit = 10)
        val currentHighlights = snapshotHighlights(current, limit = 10)
        
        if (previousHighlights.size != currentHighlights.size) {
            return false
        }
        
        // Simple stability check: compare text content
        val previousText = previous.nodes.joinToString("|") { "${it.text}${it.contentDescription}" }.lowercase()
        val currentText = current.nodes.joinToString("|") { "${it.text}${it.contentDescription}" }.lowercase()
        
        // Consider stable if 90% or more of content is the same
        val similarity = calculateStringSimilarity(previousText, currentText)
        return similarity >= 0.9
    }
    
    private fun hasSearchResultIndicators(snapshot: ScreenSnapshot): Boolean {
        val resultIndicators = listOf(
            "results", "about", "all", "images", "videos", "news", "shopping", "maps", 
            "top stories", "found", "search results", "showing", "of", "items", "entries",
            "view", "see", "filter", "sort", "refine"
        )
        
        return snapshot.nodes.any { node ->
            val merged = "${node.text} ${node.contentDescription}".trim().lowercase()
            resultIndicators.any { indicator -> merged.contains(indicator) }
        }
    }
    
    private fun calculateStringSimilarity(str1: String, str2: String): Double {
        if (str1 == str2) return 1.0
        if (str1.isEmpty() || str2.isEmpty()) return 0.0
        
        val longer = if (str1.length > str2.length) str1 else str2
        val shorter = if (str1.length > str2.length) str2 else str1
        
        if (longer.isEmpty()) return 1.0
        
        val editDistance = calculateLevenshteinDistance(longer, shorter)
        return (longer.length - editDistance).toDouble() / longer.length
    }
    
    private fun calculateLevenshteinDistance(str1: String, str2: String): Int {
        val matrix = Array(str1.length + 1) { IntArray(str2.length + 1) }
        
        for (i in 0..str1.length) {
            matrix[i][0] = i
        }
        
        for (j in 0..str2.length) {
            matrix[0][j] = j
        }
        
        for (i in 1..str1.length) {
            for (j in 1..str2.length) {
                val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
                matrix[i][j] = minOf(
                    matrix[i - 1][j] + 1,      // deletion
                    matrix[i][j - 1] + 1,      // insertion
                    matrix[i - 1][j - 1] + cost // substitution
                )
            }
        }
        
        return matrix[str1.length][str2.length]
    }

    private fun isLikelySearchResultsScreen(snapshot: ScreenSnapshot, query: String): Boolean {
        val normalizedQuery = query.trim().lowercase()
        val queryTokens = normalizedQuery
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }

        val hasQueryOutsideInput = snapshot.nodes.any { node ->
            val merged = "${node.text} ${node.contentDescription}".trim().lowercase()
            if (merged.isBlank()) return@any false
            if (node.className.contains("EditText", ignoreCase = true)) return@any false
            merged.contains(normalizedQuery) || queryTokens.count { merged.contains(it) } >= 2
        }

        val resultHints = listOf(
            "results",
            "about",
            "all",
            "images",
            "videos",
            "news",
            "shopping",
            "maps",
            "top stories"
        )
        val hasResultHints = snapshot.nodes.any { node ->
            val merged = "${node.text} ${node.contentDescription}".trim().lowercase()
            merged.isNotBlank() && resultHints.any { merged.contains(it) }
        }

        return hasQueryOutsideInput || (hasResultHints && snapshot.nodes.size >= 10)
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

    private fun launchApp(context: Context, packageName: String): Boolean {
        val packageManager = context.packageManager

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchIntent != null) {
            return runCatching {
                context.startActivity(launchIntent)
                true
            }.getOrElse { false }
        }

        val queryIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val launchableActivity = packageManager
            .queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
            .firstOrNull()
            ?.activityInfo
            ?: return false

        val fallbackIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(launchableActivity.packageName, launchableActivity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(fallbackIntent)
            true
        }.getOrElse { false }
    }
}
