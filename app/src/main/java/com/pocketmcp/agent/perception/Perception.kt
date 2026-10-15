package com.pocketmcp.agent.perception

import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.accessibility.ScreenSnapshot

public data class UiStateSnapshot(
    public val packageName: String?,
    public val visibleTexts: List<String>
)

public data class PopupMatch(
    public val patternId: String,
    public val matchedText: String,
    public val dismissStrategy: String,
    public val tapHints: List<String>,
    public val priority: Int = 2,
    public val note: String? = null
)

public class PopupDetector {
    // Extended global interruption patterns derived from app_action_presets.json v3
    private val patterns: List<PopupPattern> = listOf(
        PopupPattern("notification_permission", listOf("allow", "notifications", "send you notifications", "turn on notifications", "stay in the loop", "notify you"), "tap_deny", listOf("Don't allow", "Not now", "Skip", "No thanks", "Cancel", "Dismiss", "Later"), 1),
        PopupPattern("location_permission", listOf("allow", "location", "access your location", "while using", "all the time", "precise location"), "tap_allow", listOf("While using the app", "Only this time", "Allow", "Precise"), 1),
        PopupPattern("storage_permission", listOf("allow", "photos", "media", "files", "access photos", "pictures and videos"), "tap_allow", listOf("Allow", "Allow access", "OK"), 2),
        PopupPattern("camera_permission", listOf("allow", "camera", "take pictures", "record video", "photo"), "tap_allow", listOf("Allow", "Allow while using", "OK"), 2),
        PopupPattern("microphone_permission", listOf("allow", "microphone", "record audio", "audio"), "tap_allow", listOf("Allow", "Allow while using", "OK"), 2),
        PopupPattern("contacts_permission", listOf("allow", "contacts", "access your contacts"), "tap_allow", listOf("Allow", "OK"), 2),
        PopupPattern("phone_permission", listOf("allow", "phone", "make and manage phone calls"), "tap_allow", listOf("Allow", "OK"), 2),
        PopupPattern("sign_in_prompt", listOf("sign in", "log in", "continue with google", "continue with facebook", "create account", "choose an account", "sign up"), "tap_deny", listOf("Skip", "Not now", "Later", "Dismiss", "Cancel", "No thanks"), 2),
        PopupPattern("premium_upgrade", listOf("premium", "upgrade", "free trial", "try premium", "subscribe", "pro version", "get premium"), "tap_deny", listOf("No thanks", "Not now", "Skip", "Dismiss", "Maybe later", "Close"), 2),
        PopupPattern("rating_prompt", listOf("rate us", "enjoying", "how would you rate", "leave a review", "rate this app"), "tap_deny", listOf("Not now", "Maybe later", "No thanks", "Dismiss", "Close"), 3),
        PopupPattern("backup_prompt", listOf("backup", "restore", "restore chats", "backup to google drive", "restore your data"), "tap_skip", listOf("Skip", "Not now", "Later", "No"), 2),
        PopupPattern("sync_prompt", listOf("sync", "turn on sync", "sign in to chrome", "sync your data", "stay synced"), "tap_deny", listOf("No thanks", "Not now", "Skip"), 2),
        PopupPattern("system_update", listOf("software update", "system update available", "update now", "new software"), "tap_deny", listOf("Later", "Remind me later", "Not now", "Dismiss"), 3),
        PopupPattern("low_battery", listOf("battery saver", "low battery", "turn on battery saver"), "tap_deny", listOf("Cancel", "No thanks", "Dismiss"), 3),
        PopupPattern("vpn_connection", listOf("vpn", "connection request", "set up a vpn", "virtual private network"), "tap_allow", listOf("OK", "Allow"), 1),
        PopupPattern("accessibility_service", listOf("accessibility", "enable accessibility", "accessibility service"), "tap_allow", listOf("OK", "Allow", "Turn on"), 1),
        PopupPattern("wifi_calling", listOf("wi-fi calling", "wifi calling", "enable wifi calling"), "tap_deny", listOf("Not now", "Later", "No thanks"), 3),
        PopupPattern("emergency_alert", listOf("emergency alert", "amber alert", "emergency broadcast"), "tap_dismiss", listOf("OK", "Dismiss", "Close"), 0)
    )

    public fun detectFromSnapshot(snapshot: ScreenSnapshot?): PopupMatch? {
        if (snapshot == null) return null
        val merged = snapshot.nodes
            .asSequence()
            .mapNotNull { node ->
                val t = node.text.trim()
                val d = node.contentDescription.trim()
                when {
                    t.isNotBlank() -> t
                    d.isNotBlank() -> d
                    else -> null
                }
            }
            .joinToString(" ")
            .lowercase()

        if (merged.isBlank()) return null

        val matches = patterns.filter { pattern ->
            pattern.triggerTexts.any { trigger ->
                merged.contains(trigger.lowercase())
            }
        }.sortedBy { it.priority }

        val best = matches.firstOrNull() ?: return null
        val matchedTrigger = best.triggerTexts.first { merged.contains(it.lowercase()) }

        return PopupMatch(
            patternId = best.id,
            matchedText = matchedTrigger,
            dismissStrategy = best.dismissStrategy,
            tapHints = best.tapHints,
            priority = best.priority,
            note = best.note
        )
    }

    public fun detect(snapshot: UiStateSnapshot): String? {
        val merged = snapshot.visibleTexts.joinToString(" ").lowercase()
        return patterns.asSequence()
            .filter { p -> p.triggerTexts.any { merged.contains(it.lowercase()) } }
            .sortedBy { it.priority }
            .firstOrNull()
            ?.id
    }

    private data class PopupPattern(
        val id: String,
        val triggerTexts: List<String>,
        val dismissStrategy: String,
        val tapHints: List<String>,
        val priority: Int,
        val note: String? = null
    )
}

public class BlockerResolver {
    public fun resolutionFor(blocker: String?): String {
        return if (blocker.isNullOrBlank()) "none" else "Detected popup candidate: $blocker"
    }

    public fun resolutionForMatch(match: PopupMatch?): String {
        if (match == null) return "none"
        return "Detected [${match.patternId}] with strategy=${match.dismissStrategy}, hints=${match.tapHints.take(3)}"
    }
}
