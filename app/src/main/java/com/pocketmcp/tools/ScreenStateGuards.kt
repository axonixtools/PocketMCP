package com.pocketmcp.tools

import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.accessibility.ScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ScreenStateCheckpoint(
    val step: String,
    val expectedPackage: String,
    val actualPackage: String,
    val matchedExpectedPackage: Boolean,
    val highlights: List<String>
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("step", step)
        put("expected_package", expectedPackage)
        put("actual_package", actualPackage)
        put("matched_expected_package", matchedExpectedPackage)
        put(
            "highlights",
            buildJsonArray {
                highlights.forEach { add(JsonPrimitive(it)) }
            }
        )
    }
}

internal suspend fun waitForForegroundPackage(
    expectedPackages: Set<String>,
    timeoutMs: Long = 10_000L,
    pollMs: Long = 280L
): ScreenSnapshot? {
    val endAt = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < endAt) {
        val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
        if (snapshot != null && expectedPackages.contains(snapshot.packageName)) {
            return snapshot
        }
        delay(pollMs)
    }
    return null
}

internal fun captureCheckpoint(step: String, expectedPackage: String): ScreenStateCheckpoint {
    val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
    val actualPackage = snapshot?.packageName.orEmpty()
    return ScreenStateCheckpoint(
        step = step,
        expectedPackage = expectedPackage,
        actualPackage = actualPackage,
        matchedExpectedPackage = actualPackage == expectedPackage,
        highlights = snapshotHighlights(snapshot)
    )
}

internal fun snapshotHighlights(snapshot: ScreenSnapshot?, limit: Int = 10): List<String> {
    if (snapshot == null) {
        return emptyList()
    }
    return snapshot.nodes.asSequence()
        .mapNotNull { node ->
            val text = node.text.trim()
            val description = node.contentDescription.trim()
            when {
                text.isNotBlank() -> text
                description.isNotBlank() -> description
                else -> null
            }
        }
        .distinct()
        .take(limit)
        .toList()
}

internal fun snapshotContainsText(snapshot: ScreenSnapshot?, value: String): Boolean {
    if (snapshot == null) {
        return false
    }
    val query = value.trim().lowercase()
    if (query.isBlank()) {
        return false
    }

    val haystack = snapshotHighlights(snapshot, limit = 60).joinToString(" ").lowercase()
    if (haystack.contains(query)) {
        return true
    }

    val tokens = query.split(Regex("\\s+")).filter { it.length >= 3 }
    if (tokens.isEmpty()) {
        return false
    }
    val matched = tokens.count { haystack.contains(it) }
    val requiredMatches = if (tokens.size >= 3) 2 else 1
    return matched >= requiredMatches
}

internal fun snapshotContainsPhone(snapshot: ScreenSnapshot?, phoneNumber: String): Boolean {
    if (snapshot == null) {
        return false
    }
    val digits = phoneNumber.filter { it.isDigit() }
    if (digits.length < 6) {
        return false
    }
    val suffix = digits.takeLast(6)
    return snapshot.nodes.any { node ->
        val merged = (node.text + node.contentDescription).filter { it.isDigit() }
        merged.contains(suffix)
    }
}
