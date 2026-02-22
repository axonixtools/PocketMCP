package com.pocketmcp.tools

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.pocketmcp.accessibility.PhoneAccessibilityService
import kotlinx.coroutines.delay

internal object UiSearchDefaults {
    val DEFAULT_TRIGGER_HINTS = listOf("Search", "Find", "Lookup")
    val DEFAULT_INPUT_ID_HINTS = listOf(
        "search",
        "query",
        "search_src_text",
        "search_input",
        "search_edit_text"
    )
    val DEFAULT_DISMISS_HINTS = listOf(
        "Close",
        "Dismiss",
        "Cancel",
        "Not now",
        "No thanks",
        "Skip",
        "Later",
        "Got it"
    )
    val DEFAULT_SUBMIT_HINTS = listOf("Search", "Go", "Enter", "Done", "OK")
}

internal data class UiSearchRequest(
    val query: String,
    val expectedPackage: String,
    val searchTriggerHints: List<String> = UiSearchDefaults.DEFAULT_TRIGGER_HINTS,
    val searchInputIdHints: List<String> = UiSearchDefaults.DEFAULT_INPUT_ID_HINTS,
    val dismissHints: List<String> = UiSearchDefaults.DEFAULT_DISMISS_HINTS,
    val submitHints: List<String> = UiSearchDefaults.DEFAULT_SUBMIT_HINTS,
    val closePopups: Boolean = false,
    val submitSearch: Boolean = false,
    val settleDelayMs: Long = 1_400L,
    val pollDelayMs: Long = 420L,
    val maxAttempts: Int = 8
)

internal data class UiSearchExecution(
    val success: Boolean,
    val typed: Boolean,
    val queryVisible: Boolean,
    val inputFound: Boolean,
    val triggerTapped: Boolean,
    val popupDismissed: Boolean,
    val submitted: Boolean,
    val expectedPackage: String,
    val actualPackage: String,
    val error: String? = null
) {
    companion object {
        fun unavailable(expectedPackage: String, error: String): UiSearchExecution {
            return UiSearchExecution(
                success = false,
                typed = false,
                queryVisible = false,
                inputFound = false,
                triggerTapped = false,
                popupDismissed = false,
                submitted = false,
                expectedPackage = expectedPackage,
                actualPackage = "",
                error = error
            )
        }
    }
}

internal suspend fun runUiSearchQuery(request: UiSearchRequest): UiSearchExecution {
    if (!PhoneAccessibilityService.isEnabled()) {
        return UiSearchExecution.unavailable(
            expectedPackage = request.expectedPackage,
            error = "Accessibility service is not connected."
        )
    }

    val query = request.query.trim()
    if (query.isBlank()) {
        return UiSearchExecution.unavailable(
            expectedPackage = request.expectedPackage,
            error = "Search query cannot be empty."
        )
    }

    delay(request.settleDelayMs.coerceAtLeast(0L))

    var inputFound = false
    var triggerTapped = false
    var typed = false
    var popupDismissed = false
    var submitted = false
    var lastPackage = ""

    val maxAttempts = request.maxAttempts.coerceIn(1, 20)
    repeat(maxAttempts) {
        val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
        if (snapshot == null) {
            delay(request.pollDelayMs.coerceAtLeast(120L))
            return@repeat
        }

        lastPackage = snapshot.packageName
        if (snapshot.packageName != request.expectedPackage) {
            delay(request.pollDelayMs.coerceAtLeast(120L))
            return@repeat
        }

        var rootNode = PhoneAccessibilityService.getInstance()?.rootInActiveWindow
        if (rootNode == null) {
            delay(request.pollDelayMs.coerceAtLeast(120L))
            return@repeat
        }

        if (request.closePopups && dismissBlockingPopup(rootNode, request.dismissHints)) {
            popupDismissed = true
            delay(320L)
            rootNode = PhoneAccessibilityService.getInstance()?.rootInActiveWindow ?: rootNode
        }

        // Rule: inspect the page for a search input first, then type the query.
        var inputNode = findSearchInputNode(
            rootNode = rootNode,
            idHints = request.searchInputIdHints
        )
        if (inputNode != null) {
            inputFound = true
        }

        if (inputNode == null) {
            val triggerNode = findSearchTriggerNode(
                rootNode = rootNode,
                idHints = request.searchInputIdHints,
                triggerHints = request.searchTriggerHints
            )
            if (triggerNode != null && clickNodeOrParent(triggerNode)) {
                triggerTapped = true
                delay(450L)
                val refreshedRoot = PhoneAccessibilityService.getInstance()?.rootInActiveWindow
                inputNode = findSearchInputNode(
                    rootNode = refreshedRoot ?: rootNode,
                    idHints = request.searchInputIdHints
                )
                if (inputNode != null) {
                    inputFound = true
                }
            }
        }

        if (inputNode == null) {
            delay(request.pollDelayMs.coerceAtLeast(120L))
            return@repeat
        }

        if (setNodeText(inputNode, query)) {
            typed = true
            if (request.submitSearch) {
                submitted = submitSearchQuery(
                    inputNode = inputNode,
                    rootNode = PhoneAccessibilityService.getInstance()?.rootInActiveWindow ?: rootNode,
                    submitHints = request.submitHints
                )
            }
            delay(320L)
            val verifySnapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
            val queryVisible = verifySnapshot?.packageName == request.expectedPackage &&
                snapshotContainsText(verifySnapshot, query)
            val completionSatisfied = queryVisible && (!request.submitSearch || submitted)
            if (completionSatisfied) {
                return UiSearchExecution(
                    success = true,
                    typed = true,
                    queryVisible = true,
                    inputFound = inputFound,
                    triggerTapped = triggerTapped,
                    popupDismissed = popupDismissed,
                    submitted = submitted,
                    expectedPackage = request.expectedPackage,
                    actualPackage = verifySnapshot?.packageName.orEmpty(),
                    error = null
                )
            }
        }

        delay(request.pollDelayMs.coerceAtLeast(120L))
    }

    val error = when {
        lastPackage.isNotBlank() && lastPackage != request.expectedPackage ->
            "Foreground package changed to '$lastPackage' while waiting for '${request.expectedPackage}'."
        !inputFound ->
            "No search input was found on the current screen."
        !typed ->
            "A search input was found, but typing the query failed."
        request.submitSearch && !submitted ->
            "Query was typed but submit action could not be triggered."
        else ->
            "Query text could not be verified on screen after typing."
    }

    return UiSearchExecution(
        success = false,
        typed = typed,
        queryVisible = false,
        inputFound = inputFound,
        triggerTapped = triggerTapped,
        popupDismissed = popupDismissed,
        submitted = submitted,
        expectedPackage = request.expectedPackage,
        actualPackage = lastPackage,
        error = error
    )
}

private fun findSearchInputNode(rootNode: AccessibilityNodeInfo, idHints: List<String>): AccessibilityNodeInfo? {
    val normalizedHints = normalizeHints(idHints)
    val bySignal = findNode(rootNode) { node ->
        isEditableNode(node) && hasSearchSignal(node, normalizedHints)
    }
    if (bySignal != null) {
        return bySignal
    }

    return findNode(rootNode) { node ->
        isEditableNode(node) && node.isFocused
    }
}

private fun findSearchTriggerNode(
    rootNode: AccessibilityNodeInfo,
    idHints: List<String>,
    triggerHints: List<String>
): AccessibilityNodeInfo? {
    val normalizedHints = normalizeHints(idHints + triggerHints)
    return findNode(rootNode) { node ->
        !isEditableNode(node) &&
            (node.isClickable || node.isFocusable) &&
            hasSearchSignal(node, normalizedHints)
    }
}

private fun normalizeHints(rawHints: List<String>): List<String> {
    return (rawHints + UiSearchDefaults.DEFAULT_TRIGGER_HINTS + UiSearchDefaults.DEFAULT_INPUT_ID_HINTS)
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun normalizeRawHints(rawHints: List<String>): List<String> {
    return rawHints
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun hasSearchSignal(node: AccessibilityNodeInfo, normalizedHints: List<String>): Boolean {
    val haystacks = listOf(
        node.viewIdResourceName.orEmpty().lowercase(),
        node.hintText?.toString().orEmpty().lowercase(),
        node.contentDescription?.toString().orEmpty().lowercase(),
        node.text?.toString().orEmpty().lowercase()
    )
    return normalizedHints.any { hint -> haystacks.any { value -> value.contains(hint) } }
}

private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
    val className = node.className?.toString().orEmpty()
    return className.contains("EditText", ignoreCase = true)
}

private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val arguments = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
}

private fun dismissBlockingPopup(rootNode: AccessibilityNodeInfo, dismissHints: List<String>): Boolean {
    val normalizedHints = normalizeRawHints(dismissHints)
    if (normalizedHints.isEmpty()) {
        return false
    }

    val dismissNode = findNode(rootNode) { node ->
        (node.isClickable || node.isFocusable) &&
            !isEditableNode(node) &&
            hasAnySignal(node, normalizedHints)
    } ?: return false

    return clickNodeOrParent(dismissNode)
}

private fun submitSearchQuery(
    inputNode: AccessibilityNodeInfo,
    rootNode: AccessibilityNodeInfo,
    submitHints: List<String>
): Boolean {
    val normalizedHints = normalizeRawHints(submitHints)
    val actionList = inputNode.actionList
    val submitAction = actionList.firstOrNull { action ->
        val label = action.label?.toString()?.trim()?.lowercase().orEmpty()
        label.isNotBlank() && normalizedHints.any { hint -> label.contains(hint) }
    }
    if (submitAction != null && inputNode.performAction(submitAction.id)) {
        return true
    }

    if (normalizedHints.isNotEmpty()) {
        val submitNode = findNode(rootNode) { node ->
            (node.isClickable || node.isFocusable) &&
                !isEditableNode(node) &&
                hasAnySignal(node, normalizedHints)
        }
        if (submitNode != null && clickNodeOrParent(submitNode)) {
            return true
        }
    }

    return false
}

private fun hasAnySignal(node: AccessibilityNodeInfo, normalizedHints: List<String>): Boolean {
    val haystacks = listOf(
        node.viewIdResourceName.orEmpty().lowercase(),
        node.hintText?.toString().orEmpty().lowercase(),
        node.contentDescription?.toString().orEmpty().lowercase(),
        node.text?.toString().orEmpty().lowercase()
    )
    return normalizedHints.any { hint -> haystacks.any { value -> value.contains(hint) } }
}

private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
    if (node == null) return false
    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        return true
    }

    var parent = node.parent
    while (parent != null) {
        if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        parent = parent.parent
    }
    return false
}

private fun findNode(
    node: AccessibilityNodeInfo?,
    predicate: (AccessibilityNodeInfo) -> Boolean
): AccessibilityNodeInfo? {
    if (node == null) return null
    if (predicate(node)) {
        return node
    }

    for (i in 0 until node.childCount) {
        val found = findNode(node.getChild(i), predicate)
        if (found != null) {
            return found
        }
    }
    return null
}
