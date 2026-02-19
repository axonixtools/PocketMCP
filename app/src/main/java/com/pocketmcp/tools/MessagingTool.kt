package com.pocketmcp.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.pocketmcp.accessibility.PhoneAccessibilityService
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

class MessagingTool : McpToolHandler {
    override val name = "send_message"
    override val description =
        "Send messages through WhatsApp (Business/Personal), Instagram, Messenger, or Google Messages"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("app") {
                put("type", "string")
                put(
                    "description",
                    "Target app. Canonical values: 'whatsapp', 'whatsapp_business', 'whatsapp_personal', 'instagram', 'messenger', 'google_messages'. Common aliases/typos like 'whatsapp business', 'whatsapp bussiness', and \"what'sapp\" are accepted."
                )
                put("enum", buildJsonArray {
                    add(JsonPrimitive("whatsapp"))
                    add(JsonPrimitive("whatsapp_business"))
                    add(JsonPrimitive("whatsapp_personal"))
                    add(JsonPrimitive("instagram"))
                    add(JsonPrimitive("messenger"))
                    add(JsonPrimitive("google_messages"))
                })
            }
            putJsonObject("phone_number") {
                put("type", "string")
                put(
                    "description",
                    "Phone number for WhatsApp/Messages (e.g. '+1234567890'). Optional when contact_name is used for WhatsApp."
                )
            }
            putJsonObject("contact_name") {
                put("type", "string")
                put("description", "Contact name for WhatsApp contact-safe flow")
            }
            putJsonObject("username") {
                put("type", "string")
                put("description", "Username for Instagram/Messenger")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Message content to send")
            }
            putJsonObject("strict_screen_state") {
                put("type", "boolean")
                put(
                    "description",
                    "When true (default), requires screen_state checks before/after each step to avoid wrong-app or wrong-target actions."
                )
            }
            putJsonObject("whatsapp_type") {
                put("type", "string")
                put("description", "For app=whatsapp only: 'business' or 'personal' (default: business)")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("business"))
                    add(JsonPrimitive("personal"))
                })
            }
            putJsonObject("strict_contact_match") {
                put("type", "boolean")
                put(
                    "description",
                    "For WhatsApp contact flow: abort if contact cannot be confidently verified on screen (default: true)."
                )
            }
        }
        put("required", kotlinx.serialization.json.buildJsonArray {
            add(JsonPrimitive("app"))
            add(JsonPrimitive("message"))
        })
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val rawApp = argString(args, "app")?.trim()
            ?: return resultError("App is required")
        val app = normalizeAppAlias(rawApp)
            ?: return resultError(
                "Unsupported app '$rawApp'. Use: whatsapp, whatsapp_business, whatsapp_personal, instagram, messenger, google_messages"
            )
        
        val message = argString(args, "message")?.trim()
            ?: return resultError("Message is required")
        
        val phoneNumber = argString(args, "phone_number")?.trim()
        val contactName = argString(args, "contact_name")?.trim()
        val username = argString(args, "username")?.trim()
        val strictScreenState = argBoolean(args, "strict_screen_state") ?: true
        val whatsappType = normalizeWhatsAppType(argString(args, "whatsapp_type"))
        val strictContactMatch = argBoolean(args, "strict_contact_match") ?: true

        if (message.isBlank()) {
            return resultError("Message cannot be empty")
        }
        if (strictScreenState && !PhoneAccessibilityService.isEnabled()) {
            return resultError(
                "strict_screen_state=true requires accessibility screen-state checks. Enable PocketMCP Accessibility Service."
            )
        }

        return when (app) {
            "whatsapp", "whatsapp_business", "whatsapp_personal" -> handleWhatsApp(
                context = context,
                app = app,
                requestedType = whatsappType,
                phoneNumber = phoneNumber,
                contactName = contactName,
                message = message,
                strictScreenState = strictScreenState,
                strictContactMatch = strictContactMatch
            )
            "instagram" -> sendInstagramMessage(
                context = context,
                username = username,
                message = message,
                strictScreenState = strictScreenState
            )
            "messenger" -> sendMessengerMessage(
                context = context,
                username = username,
                message = message,
                strictScreenState = strictScreenState
            )
            "google_messages" -> sendGoogleMessages(
                context = context,
                phoneNumber = phoneNumber,
                message = message,
                strictScreenState = strictScreenState
            )
            else -> resultError(
                "Unsupported app. Use: whatsapp, whatsapp_business, whatsapp_personal, instagram, messenger, google_messages"
            )
        }
    }

    private suspend fun handleWhatsApp(
        context: Context,
        app: String,
        requestedType: String?,
        phoneNumber: String?,
        contactName: String?,
        message: String,
        strictScreenState: Boolean,
        strictContactMatch: Boolean
    ): McpToolCallResult {
        val resolvedType = when (app) {
            "whatsapp_business" -> "business"
            "whatsapp_personal" -> "personal"
            else -> if (requestedType == "personal") "personal" else "business"
        }
        val forcedPackage = if (resolvedType == "personal") WHATSAPP_PERSONAL else WHATSAPP_BUSINESS

        if (!contactName.isNullOrBlank()) {
            if (!PhoneAccessibilityService.isEnabled()) {
                return resultError(
                    "WhatsApp contact-safe flow requires accessibility. Enable PocketMCP Accessibility Service."
                )
            }

            val delegatedArgs = buildJsonObject {
                put("contact_name", contactName)
                put("message", message)
                put("whatsapp_type", resolvedType)
                put("strict_contact_match", strictContactMatch)
                if (!phoneNumber.isNullOrBlank()) {
                    put("phone_number", phoneNumber)
                }
            }
            return WhatsAppSendTool().execute(delegatedArgs, context)
        }

        return sendWhatsAppMessage(
            context = context,
            phoneNumber = phoneNumber,
            contactName = contactName,
            message = message,
            strictScreenState = strictScreenState,
            forcedPackage = forcedPackage
        )
    }

    private suspend fun sendWhatsAppMessage(
        context: Context,
        phoneNumber: String?,
        contactName: String?,
        message: String?,
        strictScreenState: Boolean,
        forcedPackage: String? = null
    ): McpToolCallResult {
        if (message.isNullOrBlank()) {
            return resultError("Message is required for WhatsApp")
        }

        val targetNumber = phoneNumber ?: return resultError("Phone number is required for WhatsApp")
        val checkpoints = mutableListOf<ScreenStateCheckpoint>()

        return try {
            val currentPackage = PhoneAccessibilityService.captureScreenSnapshot(80)?.packageName
            val usedPackage = when {
                forcedPackage != null && isAppInstalled(context, forcedPackage) -> forcedPackage
                forcedPackage == null &&
                    (currentPackage == WHATSAPP_BUSINESS || currentPackage == WHATSAPP_PERSONAL) -> currentPackage
                forcedPackage == null && isAppInstalled(context, WHATSAPP_BUSINESS) -> WHATSAPP_BUSINESS
                forcedPackage == null && isAppInstalled(context, WHATSAPP_PERSONAL) -> WHATSAPP_PERSONAL
                else -> null
            } ?: return resultError(
                if (forcedPackage == WHATSAPP_BUSINESS) {
                    "WhatsApp Business is not installed"
                } else if (forcedPackage == WHATSAPP_PERSONAL) {
                    "WhatsApp Personal is not installed"
                } else {
                    "WhatsApp is not installed"
                }
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$targetNumber?text=${Uri.encode(message)}")
                setPackage(usedPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (strictScreenState) {
                checkpoints += captureCheckpoint("before_open", usedPackage)
            }
            context.startActivity(intent)

            var targetVerified = false
            if (strictScreenState) {
                val foreground = waitForForegroundPackage(setOf(usedPackage), timeoutMs = 10_000L)
                    ?: return resultError(
                        "Safety check failed: expected WhatsApp in foreground, but another app stayed active."
                    )
                checkpoints += ScreenStateCheckpoint(
                    step = "after_open",
                    expectedPackage = usedPackage,
                    actualPackage = foreground.packageName,
                    matchedExpectedPackage = foreground.packageName == usedPackage,
                    highlights = snapshotHighlights(foreground)
                )

                delay(900)
                val targetSnapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
                checkpoints += captureCheckpoint("target_verification", usedPackage)
                targetVerified =
                    snapshotContainsPhone(targetSnapshot, targetNumber) ||
                        (!contactName.isNullOrBlank() && snapshotContainsText(targetSnapshot, contactName))

                if (!targetVerified) {
                    return resultError(
                        "Safety check failed: could not verify target chat/number on screen. Aborted to prevent wrong-recipient action."
                    )
                }
            }

            val usedApp = if (usedPackage == WHATSAPP_BUSINESS) "WhatsApp Business" else "WhatsApp"

            val payload = buildJsonObject {
                put("app", "whatsapp")
                put("phone_number", targetNumber)
                put("contact_name", contactName ?: "")
                put("message", message)
                put("success", true)
                put("used_app", usedApp)
                put("status", "Opened $usedApp with pre-filled message to $targetNumber")
                put("note", "Message will be sent after you tap the send button in WhatsApp")
                put("strict_screen_state", strictScreenState)
                put("screen_state_verified", targetVerified || !strictScreenState)
                put("next_actions", buildJsonArray {
                    add(JsonPrimitive("SEND"))
                    add(JsonPrimitive("CANCEL"))
                })
                if (strictScreenState) {
                    put(
                        "screen_checkpoints",
                        buildJsonArray {
                            checkpoints.forEach { add(it.toJson()) }
                        }
                    )
                }
            }
            resultJson(payload)
        } catch (e: Exception) {
            resultError("Failed to open WhatsApp: ${e.message}")
        }
    }

    private suspend fun sendInstagramMessage(
        context: Context,
        username: String?,
        message: String,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.instagram.com/${username ?: "_"}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(INSTAGRAM_PACKAGE)
            }

            if (isAppInstalled(context, INSTAGRAM_PACKAGE)) {
                val checkpoints = mutableListOf<ScreenStateCheckpoint>()
                if (strictScreenState) {
                    checkpoints += captureCheckpoint("before_open", INSTAGRAM_PACKAGE)
                }
                context.startActivity(intent)

                var targetVerified = false
                if (strictScreenState) {
                    waitForForegroundPackage(setOf(INSTAGRAM_PACKAGE), timeoutMs = 10_000L)
                        ?: return resultError("Safety check failed: Instagram did not reach foreground.")
                    checkpoints += captureCheckpoint("after_open", INSTAGRAM_PACKAGE)
                    delay(900)
                    val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
                    checkpoints += captureCheckpoint("target_verification", INSTAGRAM_PACKAGE)
                    targetVerified = username.isNullOrBlank() || snapshotContainsText(snapshot, username)
                    if (!targetVerified) {
                        return resultError(
                            "Safety check failed: could not verify requested Instagram target on screen."
                        )
                    }
                }

                val payload = buildJsonObject {
                    put("app", "instagram")
                    put("username", username ?: "")
                    put("message", message)
                    put("success", true)
                    put("action", "opened_instagram_profile")
                    put("note", "Message needs to be sent manually in the app")
                    put("strict_screen_state", strictScreenState)
                    put("screen_state_verified", targetVerified || !strictScreenState)
                    if (strictScreenState) {
                        put(
                            "screen_checkpoints",
                            buildJsonArray {
                                checkpoints.forEach { add(it.toJson()) }
                            }
                        )
                    }
                }
                resultJson(payload)
            } else {
                resultError("Instagram is not installed")
            }
        } catch (e: Exception) {
            resultError("Failed to open Instagram: ${e.message}")
        }
    }

    private suspend fun sendMessengerMessage(
        context: Context,
        username: String?,
        message: String,
        strictScreenState: Boolean
    ): McpToolCallResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://m.me/${username ?: ""}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(MESSENGER_PACKAGE)
            }

            if (isAppInstalled(context, MESSENGER_PACKAGE)) {
                val checkpoints = mutableListOf<ScreenStateCheckpoint>()
                if (strictScreenState) {
                    checkpoints += captureCheckpoint("before_open", MESSENGER_PACKAGE)
                }
                context.startActivity(intent)

                var targetVerified = false
                if (strictScreenState) {
                    waitForForegroundPackage(setOf(MESSENGER_PACKAGE), timeoutMs = 10_000L)
                        ?: return resultError("Safety check failed: Messenger did not reach foreground.")
                    checkpoints += captureCheckpoint("after_open", MESSENGER_PACKAGE)
                    delay(900)
                    val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
                    checkpoints += captureCheckpoint("target_verification", MESSENGER_PACKAGE)
                    targetVerified = username.isNullOrBlank() || snapshotContainsText(snapshot, username)
                    if (!targetVerified) {
                        return resultError(
                            "Safety check failed: could not verify requested Messenger target on screen."
                        )
                    }
                }

                val payload = buildJsonObject {
                    put("app", "messenger")
                    put("username", username ?: "")
                    put("message", message)
                    put("success", true)
                    put("action", "opened_messenger_chat")
                    put("note", "Message needs to be sent manually in the app")
                    put("strict_screen_state", strictScreenState)
                    put("screen_state_verified", targetVerified || !strictScreenState)
                    if (strictScreenState) {
                        put(
                            "screen_checkpoints",
                            buildJsonArray {
                                checkpoints.forEach { add(it.toJson()) }
                            }
                        )
                    }
                }
                resultJson(payload)
            } else {
                resultError("Facebook Messenger is not installed")
            }
        } catch (e: Exception) {
            resultError("Failed to open Messenger: ${e.message}")
        }
    }

    private suspend fun sendGoogleMessages(
        context: Context,
        phoneNumber: String?,
        message: String,
        strictScreenState: Boolean
    ): McpToolCallResult {
        if (phoneNumber.isNullOrBlank()) {
            return resultError("Phone number is required for Google Messages")
        }

        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(GOOGLE_MESSAGES_PACKAGE)
            }

            if (isAppInstalled(context, GOOGLE_MESSAGES_PACKAGE)) {
                val checkpoints = mutableListOf<ScreenStateCheckpoint>()
                if (strictScreenState) {
                    checkpoints += captureCheckpoint("before_open", GOOGLE_MESSAGES_PACKAGE)
                }
                context.startActivity(intent)

                var targetVerified = false
                if (strictScreenState) {
                    waitForForegroundPackage(setOf(GOOGLE_MESSAGES_PACKAGE), timeoutMs = 10_000L)
                        ?: return resultError("Safety check failed: Google Messages did not reach foreground.")
                    checkpoints += captureCheckpoint("after_open", GOOGLE_MESSAGES_PACKAGE)
                    delay(900)
                    val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
                    checkpoints += captureCheckpoint("target_verification", GOOGLE_MESSAGES_PACKAGE)
                    targetVerified = snapshotContainsPhone(snapshot, phoneNumber)
                    if (!targetVerified) {
                        return resultError(
                            "Safety check failed: could not verify the recipient phone number on screen."
                        )
                    }
                }

                val payload = buildJsonObject {
                    put("app", "google_messages")
                    put("phone_number", phoneNumber)
                    put("message", message)
                    put("success", true)
                    put("action", "opened_messages_with_compose")
                    put("strict_screen_state", strictScreenState)
                    put("screen_state_verified", targetVerified || !strictScreenState)
                    if (strictScreenState) {
                        put(
                            "screen_checkpoints",
                            buildJsonArray {
                                checkpoints.forEach { add(it.toJson()) }
                            }
                        )
                    }
                }
                resultJson(payload)
            } else {
                resultError("Google Messages is not installed")
            }
        } catch (e: Exception) {
            resultError("Failed to open Google Messages: ${e.message}")
        }
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun normalizeAppAlias(rawApp: String): String? {
        val compact = rawApp.trim().lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        if (compact.isBlank()) {
            return null
        }
        val noSeparators = compact.replace("_", "")
        val looksLikeWhatsApp = noSeparators.contains("whatsapp") ||
            noSeparators.contains("whatsap") ||
            noSeparators.contains("watsapp")
        if (looksLikeWhatsApp) {
            val hasBusinessHint = listOf("business", "bussiness", "buisness", "biz", "w4b")
                .any { hint -> noSeparators.contains(hint) }
            val hasPersonalHint = listOf("personal", "normal", "regular", "main")
                .any { hint -> noSeparators.contains(hint) }
            return when {
                hasBusinessHint -> "whatsapp_business"
                hasPersonalHint -> "whatsapp_personal"
                else -> "whatsapp"
            }
        }
        return when (noSeparators) {
            "instagram", "insta" -> "instagram"
            "messenger", "fbmessenger", "facebookmessenger", "facebookchat", "fbchat" -> "messenger"
            "googlemessages", "googlemessage", "messages", "androidmessages", "sms", "text" -> "google_messages"
            else -> null
        }
    }

    private fun normalizeWhatsAppType(rawType: String?): String? {
        if (rawType.isNullOrBlank()) {
            return null
        }
        val normalized = rawType.trim().lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), "")
        if (normalized.isBlank()) {
            return null
        }
        return when {
            normalized.startsWith("personal") ||
                normalized == "normal" ||
                normalized == "regular" -> "personal"
            normalized.startsWith("business") ||
                normalized.startsWith("bussiness") ||
                normalized.startsWith("buisness") ||
                normalized == "biz" ||
                normalized == "w4b" -> "business"
            else -> null
        }
    }

    private companion object {
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        const val WHATSAPP_PERSONAL = "com.whatsapp"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val MESSENGER_PACKAGE = "com.facebook.orca"
        const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
    }
}
