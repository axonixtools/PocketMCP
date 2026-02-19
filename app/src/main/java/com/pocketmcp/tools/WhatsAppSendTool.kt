package com.pocketmcp.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.accessibility.ScreenSnapshot
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class WhatsAppSendTool : McpToolHandler {
    override val name = "send_whatsapp_message"
    override val description =
        "State-aware WhatsApp automation: verify contact screen, type message, re-check UI, then send."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("contact_name") {
                put("type", "string")
                put("description", "Contact name to search and send message to.")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Message content to send.")
            }
            putJsonObject("whatsapp_type") {
                put("type", "string")
                put("description", "WhatsApp app type: 'personal' or 'business'.")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("personal"))
                    add(JsonPrimitive("business"))
                })
            }
            putJsonObject("phone_number") {
                put("type", "string")
                put("description", "Optional phone number used as fallback for contact match.")
            }
            putJsonObject("strict_contact_match") {
                put("type", "boolean")
                put("description", "Abort send if contact cannot be confirmed on screen (default: true).")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("contact_name"))
            add(JsonPrimitive("message"))
        })
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val contactName = argString(args, "contact_name")?.trim()
            ?: return resultError("Contact name is required")
        val message = argString(args, "message")?.trim()
            ?: return resultError("Message is required")
        val whatsappType = argString(args, "whatsapp_type")?.trim()?.lowercase() ?: "business"
        val phoneNumber = argString(args, "phone_number")?.trim()?.takeIf { it.isNotBlank() }
        val strictContactMatch = argBoolean(args, "strict_contact_match") ?: true

        if (message.isBlank()) {
            return resultError("Message cannot be empty")
        }
        if (!PhoneAccessibilityService.isEnabled()) {
            return resultError(
                "Accessibility service is disabled. Enable PocketMCP Accessibility Service in Android settings."
            )
        }

        val packageName = if (whatsappType == "business") WHATSAPP_BUSINESS else WHATSAPP_PERSONAL
        if (!isAppInstalled(context, packageName)) {
            return resultError("WhatsApp $whatsappType is not installed")
        }

        val checkpoints = mutableListOf<UiCheckpoint>()

        return try {
            val launchIntent = getLauncherIntent(context, packageName)
                ?: return resultError(
                    "WhatsApp $whatsappType is installed but has no launchable activity ($packageName)"
                )
            context.startActivity(launchIntent)

            if (!waitForForegroundPackage(packageName, timeoutMs = 10_000L)) {
                return resultError("Failed to bring WhatsApp to foreground ($packageName).")
            }
            checkpoints.add(captureCheckpoint("opened_whatsapp", contactName, phoneNumber, null))

            val selected = searchAndOpenContactChat(contactName, phoneNumber, packageName)
            checkpoints.add(captureCheckpoint("contact_selection", contactName, phoneNumber, null))
            if (!selected) {
                return resultError(
                    "Could not open chat for '$contactName'. Check contact spelling and WhatsApp UI state."
                )
            }

            val contactCheckpoint = captureCheckpoint("contact_verified", contactName, phoneNumber, null)
            checkpoints.add(contactCheckpoint)
            if (strictContactMatch && !contactCheckpoint.contactLikelyVisible) {
                return resultError(
                    "Aborted: selected chat could not be confidently verified as '$contactName'."
                )
            }

            val typed = typeMessageInChat(message, packageName)
            val typedCheckpoint = captureCheckpoint("message_typed", contactName, phoneNumber, message)
            checkpoints.add(typedCheckpoint)
            if (!typed || !typedCheckpoint.messageLikelyVisible) {
                return resultError("Message typing could not be verified on screen.")
            }

            val preSendCheckpoint = captureCheckpoint("pre_send", contactName, phoneNumber, message)
            checkpoints.add(preSendCheckpoint)
            if (strictContactMatch && !preSendCheckpoint.contactLikelyVisible) {
                return resultError(
                    "Aborted before send: contact verification failed in pre-send state."
                )
            }

            val sent = pressSendButton(packageName)
            if (!sent) {
                return resultError(
                    "Could not press send button in WhatsApp. Use screen_state + tap to inspect current UI."
                )
            }

            delay(700)
            checkpoints.add(captureCheckpoint("after_send", contactName, phoneNumber, null))

            val payload = buildJsonObject {
                put("action", "send_whatsapp_message")
                put("success", true)
                put("whatsapp_type", whatsappType)
                put("package_name", packageName)
                put("contact_name", contactName)
                put("phone_number", phoneNumber ?: "")
                put("message", message)
                put("strict_contact_match", strictContactMatch)
                put("status", "Message flow completed with screen-state verification.")
                put("steps_completed", buildJsonArray {
                    add(JsonPrimitive("opened_whatsapp"))
                    add(JsonPrimitive("selected_contact"))
                    add(JsonPrimitive("verified_contact"))
                    add(JsonPrimitive("typed_message"))
                    add(JsonPrimitive("verified_pre_send_state"))
                    add(JsonPrimitive("pressed_send"))
                })
                put("checkpoints", buildJsonArray {
                    checkpoints.forEach { add(it.toJson()) }
                })
            }
            resultJson(payload)
        } catch (e: Exception) {
            resultError("WhatsApp automation failed: ${e.message}")
        }
    }

    private suspend fun searchAndOpenContactChat(
        contactName: String,
        phoneNumber: String?,
        packageName: String
    ): Boolean {
        var attempts = 0
        while (attempts < 12) {
            val rootNode = getRootNodeForPackage(packageName)
            if (rootNode == null) {
                attempts++
                delay(450)
                continue
            }

            if (hasMessageInput(rootNode)) {
                if (screenLikelyMatchesContact(contactName, phoneNumber)) {
                    return true
                }
            }

            val searchTrigger = findSearchTrigger(rootNode)
            if (searchTrigger != null) {
                clickNode(searchTrigger)
                delay(500)
            }

            val searchRoot = getRootNodeForPackage(packageName) ?: rootNode
            val searchInput = findSearchInputField(searchRoot)
            if (searchInput != null && setNodeText(searchInput, contactName)) {
                delay(900)
                val resultRoot = getRootNodeForPackage(packageName) ?: searchRoot
                val candidate = findContactCandidate(resultRoot, contactName, phoneNumber)
                if (candidate != null && clickNode(candidate)) {
                    delay(1200)
                    if (waitForMessageInput(packageName, timeoutMs = 6_000L)) {
                        return true
                    }
                }
            }

            attempts++
            delay(450)
        }
        return false
    }

    private suspend fun typeMessageInChat(message: String, packageName: String): Boolean {
        var attempts = 0
        while (attempts < 10) {
            val rootNode = getRootNodeForPackage(packageName)
            if (rootNode != null) {
                val messageField = findMessageInputField(rootNode)
                if (messageField != null && setNodeText(messageField, message)) {
                    delay(450)
                    if (screenLikelyContainsMessage(message)) {
                        return true
                    }
                }
            }

            attempts++
            delay(450)
        }
        return false
    }

    private suspend fun pressSendButton(packageName: String): Boolean {
        var attempts = 0
        while (attempts < 10) {
            val rootNode = getRootNodeForPackage(packageName)
            if (rootNode != null) {
                val sendButton = findSendButton(rootNode)
                if (sendButton != null && clickNode(sendButton)) {
                    return true
                }

                val tapResult = PhoneAccessibilityService.tapVisibleNodeByText(
                    query = "Send",
                    exact = false,
                    occurrence = 1
                )
                if (tapResult.success) {
                    return true
                }

                val messageField = findMessageInputField(rootNode)
                if (messageField != null) {
                    val bounds = android.graphics.Rect()
                    messageField.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        val service = PhoneAccessibilityService.getInstance()
                        val width = service?.resources?.displayMetrics?.widthPixels ?: 0
                        if (width > 0) {
                            val tapX = (bounds.right + 80).coerceAtMost(width - 24)
                            val tapY = bounds.centerY()
                            if (PhoneAccessibilityService.runTap(tapX, tapY, 90L)) {
                                return true
                            }
                        }
                    }
                }
            }

            attempts++
            delay(450)
        }
        return false
    }

    private suspend fun waitForForegroundPackage(
        targetPackage: String,
        timeoutMs: Long
    ): Boolean {
        val endAt = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < endAt) {
            val rootNode = PhoneAccessibilityService.getInstance()?.rootInActiveWindow
            val packageName = rootNode?.packageName?.toString().orEmpty()
            if (packageName == targetPackage) {
                return true
            }
            delay(280)
        }
        return false
    }

    private suspend fun waitForMessageInput(targetPackage: String, timeoutMs: Long): Boolean {
        val endAt = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < endAt) {
            val rootNode = getRootNodeForPackage(targetPackage)
            if (rootNode != null && hasMessageInput(rootNode)) {
                return true
            }
            delay(300)
        }
        return false
    }

    private fun getRootNodeForPackage(targetPackage: String): AccessibilityNodeInfo? {
        val rootNode = PhoneAccessibilityService.getInstance()?.rootInActiveWindow ?: return null
        val packageName = rootNode.packageName?.toString().orEmpty()
        return if (packageName == targetPackage) rootNode else null
    }

    private fun hasMessageInput(rootNode: AccessibilityNodeInfo): Boolean {
        return findMessageInputField(rootNode) != null
    }

    private fun findContactCandidate(
        rootNode: AccessibilityNodeInfo,
        contactName: String,
        phoneNumber: String?
    ): AccessibilityNodeInfo? {
        val exact = findNodeByText(rootNode, contactName, exact = true)
        if (exact != null) return exact

        val contains = findNodeByText(rootNode, contactName, exact = false)
        if (contains != null) return contains

        if (!phoneNumber.isNullOrBlank()) {
            val normalized = normalizeDigits(phoneNumber)
            if (normalized.isNotBlank()) {
                return findNode(rootNode) { node ->
                    val text = normalizeDigits(node.text?.toString().orEmpty())
                    text.contains(normalized)
                }
            }
        }
        return null
    }

    private fun findSearchTrigger(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeById(rootNode, "menuitem_search")
            ?: findNodeById(rootNode, "search")
            ?: findNodeByContentDescription(rootNode, "Search")
    }

    private fun findSearchInputField(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeById(rootNode, "search_src_text")
            ?: findNodeById(rootNode, "search_input")
            ?: findNode(rootNode) { node ->
                node.className?.toString()?.contains("EditText", ignoreCase = true) == true &&
                    (
                        node.viewIdResourceName?.contains("search", ignoreCase = true) == true ||
                            node.hintText?.toString()?.contains("search", ignoreCase = true) == true
                        )
            }
            ?: findNodeByClassName(rootNode, "android.widget.EditText")
    }

    private fun findMessageInputField(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeById(rootNode, "entry")
            ?: findNodeById(rootNode, "conversation_entry")
            ?: findNodeById(rootNode, "compose")
            ?: findNodeById(rootNode, "input")
            ?: findNode(rootNode) { node ->
                node.className?.toString()?.contains("EditText", ignoreCase = true) == true &&
                    (
                        node.viewIdResourceName?.contains("entry", ignoreCase = true) == true ||
                            node.viewIdResourceName?.contains("message", ignoreCase = true) == true ||
                            node.viewIdResourceName?.contains("compose", ignoreCase = true) == true
                        )
            }
            ?: findNodeByClassName(rootNode, "android.widget.EditText")
    }

    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeById(rootNode, "send")
            ?: findNodeById(rootNode, "send_button")
            ?: findNodeById(rootNode, "send_container")
            ?: findNodeByContentDescription(rootNode, "Send")
            ?: findNodeByText(rootNode, "Send", exact = true)
            ?: findNode(rootNode) { node ->
                val desc = node.contentDescription?.toString().orEmpty()
                node.isClickable && desc.contains("send", ignoreCase = true)
            }
    }

    private fun findNodeById(node: AccessibilityNodeInfo?, idPart: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val viewId = node.viewIdResourceName.orEmpty()
        if (viewId.contains(idPart, ignoreCase = true)) {
            return node
        }
        for (index in 0 until node.childCount) {
            val found = findNodeById(node.getChild(index), idPart)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String, exact: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val nodeText = node.text?.toString().orEmpty()
        val nodeDescription = node.contentDescription?.toString().orEmpty()
        val matches = if (exact) {
            nodeText.equals(text, ignoreCase = true) ||
                nodeDescription.equals(text, ignoreCase = true)
        } else {
            nodeText.contains(text, ignoreCase = true) ||
                nodeDescription.contains(text, ignoreCase = true)
        }
        if (matches) {
            return node
        }
        for (index in 0 until node.childCount) {
            val found = findNodeByText(node.getChild(index), text, exact)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByContentDescription(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val description = node.contentDescription?.toString().orEmpty()
        if (description.contains(text, ignoreCase = true)) {
            return node
        }
        for (index in 0 until node.childCount) {
            val found = findNodeByContentDescription(node.getChild(index), text)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByClassName(node: AccessibilityNodeInfo?, className: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.toString()?.contains(className, ignoreCase = true) == true) {
            return node
        }
        for (index in 0 until node.childCount) {
            val found = findNodeByClassName(node.getChild(index), className)
            if (found != null) return found
        }
        return null
    }

    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) {
            return node
        }
        for (index in 0 until node.childCount) {
            val found = findNode(node.getChild(index), predicate)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
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

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun screenLikelyMatchesContact(contactName: String, phoneNumber: String?): Boolean {
        val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120) ?: return false
        return UiCheckpoint.fromSnapshot("contact_check", snapshot, contactName, phoneNumber, null)
            .contactLikelyVisible
    }

    private fun screenLikelyContainsMessage(message: String): Boolean {
        val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120) ?: return false
        return UiCheckpoint.fromSnapshot("message_check", snapshot, "", null, message)
            .messageLikelyVisible
    }

    private fun captureCheckpoint(
        step: String,
        contactName: String,
        phoneNumber: String?,
        message: String?
    ): UiCheckpoint {
        val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
        return if (snapshot == null) {
            UiCheckpoint(
                step = step,
                foregroundPackage = "",
                nodeCount = 0,
                highlights = emptyList(),
                contactLikelyVisible = false,
                messageLikelyVisible = false,
                sendButtonLikelyVisible = false
            )
        } else {
            UiCheckpoint.fromSnapshot(
                step = step,
                snapshot = snapshot,
                contactName = contactName,
                phoneNumber = phoneNumber,
                message = message
            )
        }
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getLauncherIntent(context: Context, packageName: String): Intent? {
        val packageManager = context.packageManager

        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            return launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val queryIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val launchableActivity = packageManager
            .queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
            .firstOrNull()
            ?.activityInfo
            ?: return null

        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(launchableActivity.packageName, launchableActivity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun normalizeDigits(value: String): String {
        return value.filter { it.isDigit() }
    }

    companion object {
        private const val WHATSAPP_PERSONAL = "com.whatsapp"
        private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }
}

private data class UiCheckpoint(
    val step: String,
    val foregroundPackage: String,
    val nodeCount: Int,
    val highlights: List<String>,
    val contactLikelyVisible: Boolean,
    val messageLikelyVisible: Boolean,
    val sendButtonLikelyVisible: Boolean
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("step", step)
        put("foreground_package", foregroundPackage)
        put("node_count", nodeCount)
        put("contact_likely_visible", contactLikelyVisible)
        put("message_likely_visible", messageLikelyVisible)
        put("send_button_likely_visible", sendButtonLikelyVisible)
        put("highlights", buildJsonArray {
            highlights.forEach { add(JsonPrimitive(it)) }
        })
    }

    companion object {
        fun fromSnapshot(
            step: String,
            snapshot: ScreenSnapshot,
            contactName: String,
            phoneNumber: String?,
            message: String?
        ): UiCheckpoint {
            val highlights = snapshot.nodes.asSequence()
                .mapNotNull { node ->
                    val text = node.text.trim()
                    val desc = node.contentDescription.trim()
                    when {
                        text.isNotBlank() -> text
                        desc.isNotBlank() -> desc
                        else -> null
                    }
                }
                .distinct()
                .take(10)
                .toList()

            val contactTokens = contactName.lowercase()
                .split(Regex("\\s+"))
                .filter { it.length >= 3 }
            val digits = phoneNumber?.filter { it.isDigit() }.orEmpty()

            val contactLikelyVisible = highlights.any { item ->
                val lower = item.lowercase()
                contactTokens.any { token -> lower.contains(token) } ||
                    (digits.isNotBlank() && item.filter { it.isDigit() }.contains(digits))
            }

            val messageLikelyVisible = if (message.isNullOrBlank()) {
                false
            } else {
                val normalizedMessage = message.trim()
                val prefix = normalizedMessage.take(18)
                highlights.any { item ->
                    item.contains(normalizedMessage, ignoreCase = true) ||
                        item.contains(prefix, ignoreCase = true)
                }
            }

            val sendButtonLikelyVisible = snapshot.nodes.any { node ->
                val label = (node.text + " " + node.contentDescription).lowercase()
                node.clickable && label.contains("send")
            }

            return UiCheckpoint(
                step = step,
                foregroundPackage = snapshot.packageName,
                nodeCount = snapshot.nodes.size,
                highlights = highlights,
                contactLikelyVisible = contactLikelyVisible,
                messageLikelyVisible = messageLikelyVisible,
                sendButtonLikelyVisible = sendButtonLikelyVisible
            )
        }
    }
}
