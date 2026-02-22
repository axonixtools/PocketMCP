package com.pocketmcp.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SocialMediaTool : McpToolHandler {
    override val name = "social_media"
    override val description = "Search and interact with social media platforms (Instagram, YouTube, X/Twitter)"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Action: 'search', 'like', 'comment', 'dislike', 'share', 'open_profile'")
            }
            putJsonObject("platform") {
                put("type", "string")
                put("description", "Platform: 'instagram', 'youtube', 'x', 'twitter'")
            }
            putJsonObject("query") {
                put("type", "string")
                put("description", "Search query or username")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "Content for comment/action")
            }
            putJsonObject("video_id") {
                put("type", "string")
                put("description", "YouTube video ID for like/dislike/comment")
            }
            putJsonObject("post_url") {
                put("type", "string")
                put("description", "Post URL for interaction")
            }
            putJsonObject("strict_screen_state") {
                put("type", "boolean")
                put(
                    "description",
                    "When true (default), enforces screen_state checks at each step to prevent wrong-app actions."
                )
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("action"))
            add(JsonPrimitive("platform"))
        })
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = argString(args, "action")?.trim()?.lowercase()
            ?: return resultError("Action is required")
        val platform = argString(args, "platform")?.trim()?.lowercase()
            ?: return resultError("Platform is required")

        val query = argString(args, "query")?.trim()
        val content = argString(args, "content")?.trim()
        val videoId = argString(args, "video_id")?.trim()
        val postUrl = argString(args, "post_url")?.trim()
        val strictScreenState = argBoolean(args, "strict_screen_state") ?: true

        if (strictScreenState && !PhoneAccessibilityService.isEnabled()) {
            return resultError(
                "strict_screen_state=true requires accessibility screen-state checks. Enable PocketMCP Accessibility Service."
            )
        }

        return when (platform) {
            "instagram" -> handleInstagramAction(context, action, query, content, postUrl, strictScreenState)
            "youtube" -> handleYouTubeAction(context, action, query, videoId, content, strictScreenState)
            "x", "twitter" -> handleXAction(context, action, query, content, postUrl, strictScreenState)
            else -> resultError("Unsupported platform. Use: instagram, youtube, x, twitter")
        }
    }

    private suspend fun handleInstagramAction(
        context: Context,
        action: String,
        query: String?,
        content: String?,
        postUrl: String?,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return when (action) {
            "search" -> {
                if (query.isNullOrBlank()) return resultError("Query is required for search")
                openInstagramSearch(context, query, strictScreenState)
            }
            "open_profile" -> {
                if (query.isNullOrBlank()) return resultError("Username is required for open_profile")
                openInstagramProfile(context, query, strictScreenState)
            }
            "like", "comment", "share" -> {
                if (postUrl.isNullOrBlank()) return resultError("Post URL is required for $action")
                openInstagramPost(context, postUrl, action, content, strictScreenState)
            }
            else -> resultError("Unsupported Instagram action. Use: search, open_profile, like, comment, share")
        }
    }

    private suspend fun handleYouTubeAction(
        context: Context,
        action: String,
        query: String?,
        videoId: String?,
        content: String?,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return when (action) {
            "search" -> {
                if (query.isNullOrBlank()) return resultError("Query is required for search")
                openYouTubeSearch(context, query, strictScreenState)
            }
            "like", "dislike", "comment" -> {
                if (videoId.isNullOrBlank()) return resultError("Video ID is required for $action")
                openYouTubeVideo(context, videoId, action, content, strictScreenState)
            }
            else -> resultError("Unsupported YouTube action. Use: search, like, dislike, comment")
        }
    }

    private suspend fun handleXAction(
        context: Context,
        action: String,
        query: String?,
        content: String?,
        postUrl: String?,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return when (action) {
            "search" -> {
                if (query.isNullOrBlank()) return resultError("Query is required for search")
                openXSearch(context, query, strictScreenState)
            }
            "open_profile" -> {
                if (query.isNullOrBlank()) return resultError("Username is required for open_profile")
                openXProfile(context, query, strictScreenState)
            }
            "like", "comment", "share" -> {
                if (postUrl.isNullOrBlank()) return resultError("Post URL is required for $action")
                openXPost(context, postUrl, action, content, strictScreenState)
            }
            else -> resultError("Unsupported X action. Use: search, open_profile, like, comment, share")
        }
    }

    private suspend fun openInstagramSearch(
        context: Context,
        query: String,
        strictScreenState: Boolean
    ): McpToolCallResult {
        val packageName = "com.instagram.android"
        if (!isAppInstalled(context, packageName)) {
            return resultError("Instagram is not installed")
        }
        val checkpoints = mutableListOf<ScreenStateCheckpoint>()
        if (strictScreenState) {
            checkpoints += captureCheckpoint("before_open", packageName)
        }
        val alreadyForeground = strictScreenState &&
            checkpoints.lastOrNull()?.actualPackage == packageName

        val opened = if (alreadyForeground) {
            true
        } else {
            startIntentIfResolvable(
                context,
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.instagram.com/explore/search/keyword/?q=${Uri.encode(query)}")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) || launchApp(context, packageName)
        }

        if (!opened) {
            return resultError("Failed to open Instagram")
        }
        if (strictScreenState) {
            waitForForegroundPackage(setOf(packageName), timeoutMs = 10_000L)
                ?: return resultError("Safety check failed: Instagram did not reach foreground.")
            checkpoints += captureCheckpoint("after_open", packageName)
        }

        val searchExecution = if (PhoneAccessibilityService.isEnabled()) {
            runUiSearchQuery(
                UiSearchRequest(
                    expectedPackage = packageName,
                    query = query,
                    searchTriggerHints = listOf("Search", "Search and explore"),
                    searchInputIdHints = listOf("search", "search_src_text")
                )
            )
        } else {
            UiSearchExecution.unavailable(
                expectedPackage = packageName,
                error = "Accessibility service is not connected."
            )
        }
        val typed = searchExecution.typed
        if (strictScreenState && !searchExecution.success) {
            return resultError(
                "Safety check failed: ${searchExecution.error ?: "Instagram search query could not be typed."}"
            )
        }
        val queryVisible = if (!strictScreenState) true else {
            checkpoints += captureCheckpoint("query_verification", packageName)
            searchExecution.queryVisible
        }

        if (strictScreenState && (!typed || !queryVisible)) {
            return resultError(
                "Safety check failed: Instagram search query could not be verified on screen. Aborted."
            )
        }

        val payload = buildJsonObject {
            put("platform", "instagram")
            put("action", "search")
            put("query", query)
            put("success", true)
            put("auto_typed", typed)
            put("strict_screen_state", strictScreenState)
            put("screen_state_verified", queryVisible)
            put(
                "message",
                if (typed) {
                    "Opened Instagram and typed search query"
                } else {
                    "Opened Instagram. Search input was not auto-typed; app UI may differ."
                }
            )
            if (strictScreenState) {
                put(
                    "screen_checkpoints",
                    buildJsonArray {
                        checkpoints.forEach { add(it.toJson()) }
                    }
                )
            }
        }
        return resultJson(payload)
    }

    private suspend fun openYouTubeSearch(
        context: Context,
        query: String,
        strictScreenState: Boolean
    ): McpToolCallResult {
        val packageName = "com.google.android.youtube"
        if (!isAppInstalled(context, packageName)) {
            return resultError("YouTube is not installed")
        }
        val checkpoints = mutableListOf<ScreenStateCheckpoint>()
        if (strictScreenState) {
            checkpoints += captureCheckpoint("before_open", packageName)
        }
        val alreadyForeground = strictScreenState &&
            checkpoints.lastOrNull()?.actualPackage == packageName

        val openedWithQueryIntent = if (alreadyForeground) {
            false
        } else {
            startIntentIfResolvable(
                context,
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage(packageName)
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        val opened = if (alreadyForeground) {
            true
        } else {
            openedWithQueryIntent || startIntentIfResolvable(
                context,
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) || launchApp(context, packageName)
        }

        if (!opened) {
            return resultError("Failed to open YouTube")
        }
        if (strictScreenState) {
            waitForForegroundPackage(setOf(packageName), timeoutMs = 10_000L)
                ?: return resultError("Safety check failed: YouTube did not reach foreground.")
            checkpoints += captureCheckpoint("after_open", packageName)
        }

        val searchExecution = if (openedWithQueryIntent) {
            UiSearchExecution(
                success = true,
                typed = true,
                queryVisible = true,
                inputFound = true,
                triggerTapped = false,
                popupDismissed = false,
                submitted = true,
                expectedPackage = packageName,
                actualPackage = packageName
            )
        } else if (PhoneAccessibilityService.isEnabled()) {
            runUiSearchQuery(
                UiSearchRequest(
                    expectedPackage = packageName,
                    query = query,
                    searchTriggerHints = listOf("Search"),
                    searchInputIdHints = listOf("search", "search_edit_text")
                )
            )
        } else {
            UiSearchExecution.unavailable(
                expectedPackage = packageName,
                error = "Accessibility service is not connected."
            )
        }
        val typed = searchExecution.typed
        if (strictScreenState && !searchExecution.success) {
            return resultError(
                "Safety check failed: ${searchExecution.error ?: "YouTube search query could not be typed."}"
            )
        }
        val queryVisible = if (!strictScreenState) true else {
            checkpoints += captureCheckpoint("query_verification", packageName)
            searchExecution.queryVisible
        }

        if (strictScreenState && !queryVisible) {
            return resultError(
                "Safety check failed: YouTube search query could not be verified on screen. Aborted."
            )
        }

        val payload = buildJsonObject {
            put("platform", "youtube")
            put("action", "search")
            put("query", query)
            put("success", true)
            put("auto_typed", typed)
            put("strict_screen_state", strictScreenState)
            put("screen_state_verified", queryVisible)
            put(
                "message",
                if (typed) {
                    "Opened YouTube search"
                } else {
                    "Opened YouTube. Search input was not auto-typed; app UI may differ."
                }
            )
            if (strictScreenState) {
                put(
                    "screen_checkpoints",
                    buildJsonArray {
                        checkpoints.forEach { add(it.toJson()) }
                    }
                )
            }
        }
        return resultJson(payload)
    }

    private suspend fun openXSearch(
        context: Context,
        query: String,
        strictScreenState: Boolean
    ): McpToolCallResult {
        val packageName = "com.twitter.android"
        if (!isAppInstalled(context, packageName)) {
            return resultError("X/Twitter is not installed")
        }
        val checkpoints = mutableListOf<ScreenStateCheckpoint>()
        if (strictScreenState) {
            checkpoints += captureCheckpoint("before_open", packageName)
        }
        val alreadyForeground = strictScreenState &&
            checkpoints.lastOrNull()?.actualPackage == packageName

        val opened = if (alreadyForeground) {
            true
        } else {
            startIntentIfResolvable(
                context,
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("twitter://search?query=${Uri.encode(query)}")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) || startIntentIfResolvable(
                context,
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://twitter.com/search?q=${Uri.encode(query)}")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) || launchApp(context, packageName)
        }

        if (!opened) {
            return resultError("Failed to open X/Twitter")
        }
        if (strictScreenState) {
            waitForForegroundPackage(setOf(packageName), timeoutMs = 10_000L)
                ?: return resultError("Safety check failed: X/Twitter did not reach foreground.")
            checkpoints += captureCheckpoint("after_open", packageName)
        }

        val searchExecution = if (PhoneAccessibilityService.isEnabled()) {
            runUiSearchQuery(
                UiSearchRequest(
                    expectedPackage = packageName,
                    query = query,
                    searchTriggerHints = listOf("Search", "Search and Explore", "Explore"),
                    searchInputIdHints = listOf("search", "query")
                )
            )
        } else {
            UiSearchExecution.unavailable(
                expectedPackage = packageName,
                error = "Accessibility service is not connected."
            )
        }
        val typed = searchExecution.typed
        if (strictScreenState && !searchExecution.success) {
            return resultError(
                "Safety check failed: ${searchExecution.error ?: "X search query could not be typed."}"
            )
        }
        val queryVisible = if (!strictScreenState) true else {
            checkpoints += captureCheckpoint("query_verification", packageName)
            searchExecution.queryVisible
        }

        if (strictScreenState && (!typed || !queryVisible)) {
            return resultError(
                "Safety check failed: X search query could not be verified on screen. Aborted."
            )
        }

        val payload = buildJsonObject {
            put("platform", "x")
            put("action", "search")
            put("query", query)
            put("success", true)
            put("auto_typed", typed)
            put("strict_screen_state", strictScreenState)
            put("screen_state_verified", queryVisible)
            put(
                "message",
                if (typed) {
                    "Opened X and typed search query"
                } else {
                    "Opened X. Search input was not auto-typed; app UI may differ."
                }
            )
            if (strictScreenState) {
                put(
                    "screen_checkpoints",
                    buildJsonArray {
                        checkpoints.forEach { add(it.toJson()) }
                    }
                )
            }
        }
        return resultJson(payload)
    }

    private suspend fun openInstagramProfile(
        context: Context,
        username: String,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return openUrlInApp(
            context = context,
            packageName = "com.instagram.android",
            url = "https://www.instagram.com/$username",
            strictScreenState = strictScreenState,
            payload = buildJsonObject {
                put("platform", "instagram")
                put("action", "open_profile")
                put("username", username)
                put("success", true)
                put("message", "Opened Instagram profile: @$username")
            }
        )
    }

    private suspend fun openInstagramPost(
        context: Context,
        postUrl: String,
        action: String,
        content: String?,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return openUrlInApp(
            context = context,
            packageName = "com.instagram.android",
            url = postUrl,
            strictScreenState = strictScreenState,
            payload = buildJsonObject {
                put("platform", "instagram")
                put("action", action)
                put("post_url", postUrl)
                put("content", content ?: "")
                put("success", true)
                put("message", "Opened Instagram post for $action. Manual interaction required.")
                put("note", "Please manually $action the post in the app")
            }
        )
    }

    private suspend fun openYouTubeVideo(
        context: Context,
        videoId: String,
        action: String,
        content: String?,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return openUrlInApp(
            context = context,
            packageName = "com.google.android.youtube",
            url = "https://www.youtube.com/watch?v=$videoId",
            strictScreenState = strictScreenState,
            payload = buildJsonObject {
                put("platform", "youtube")
                put("action", action)
                put("video_id", videoId)
                put("content", content ?: "")
                put("success", true)
                put("message", "Opened YouTube video for $action. Manual interaction required.")
                put("note", "Please manually $action the video in the app")
            }
        )
    }

    private suspend fun openXProfile(
        context: Context,
        username: String,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return openUrlInApp(
            context = context,
            packageName = "com.twitter.android",
            url = "https://twitter.com/$username",
            strictScreenState = strictScreenState,
            payload = buildJsonObject {
                put("platform", "x")
                put("action", "open_profile")
                put("username", username)
                put("success", true)
                put("message", "Opened X profile: @$username")
            }
        )
    }

    private suspend fun openXPost(
        context: Context,
        postUrl: String,
        action: String,
        content: String?,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return openUrlInApp(
            context = context,
            packageName = "com.twitter.android",
            url = postUrl,
            strictScreenState = strictScreenState,
            payload = buildJsonObject {
                put("platform", "x")
                put("action", action)
                put("post_url", postUrl)
                put("content", content ?: "")
                put("success", true)
                put("message", "Opened X post for $action. Manual interaction required.")
                put("note", "Please manually $action the post in the app")
            }
        )
    }

    private suspend fun openUrlInApp(
        context: Context,
        packageName: String,
        url: String,
        strictScreenState: Boolean,
        payload: JsonObject
    ): McpToolCallResult {
        if (!isAppInstalled(context, packageName)) {
            return resultError("$packageName is not installed")
        }

        return try {
            val checkpoints = mutableListOf<ScreenStateCheckpoint>()
            if (strictScreenState) {
                checkpoints += captureCheckpoint("before_open", packageName)
            }

            val opened = startIntentIfResolvable(
                context,
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(url)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ) || launchApp(context, packageName)

            if (!opened) {
                resultError("Failed to open app: $packageName")
            } else if (strictScreenState) {
                waitForForegroundPackage(setOf(packageName), timeoutMs = 10_000L)
                    ?: return resultError("Safety check failed: expected $packageName in foreground.")
                checkpoints += captureCheckpoint("after_open", packageName)
                val guardedPayload = buildJsonObject {
                    payload.forEach { (key, value) -> put(key, value) }
                    put("strict_screen_state", true)
                    put("screen_state_verified", true)
                    put(
                        "screen_checkpoints",
                        buildJsonArray {
                            checkpoints.forEach { add(it.toJson()) }
                        }
                    )
                }
                resultJson(guardedPayload)
            } else {
                resultJson(payload)
            }
        } catch (e: Exception) {
            resultError("Failed to open $packageName: ${e.message}")
        }
    }

    private fun startIntentIfResolvable(context: Context, intent: Intent): Boolean {
        val component = intent.resolveActivity(context.packageManager) ?: return false
        return try {
            context.startActivity(intent)
            component != null
        } catch (_: Exception) {
            false
        }
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

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
