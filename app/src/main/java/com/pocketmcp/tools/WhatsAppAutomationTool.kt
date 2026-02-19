package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class WhatsAppAutomationTool : McpToolHandler {
    override val name = "whatsapp_automation"
    override val description =
        "Safe WhatsApp automation. send_message uses full screen-state verified flow."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Action: send_message, select_contact, type_message, press_send, press_cancel")
            }
            putJsonObject("contact_name") {
                put("type", "string")
                put("description", "Contact name to send message to")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Message content")
            }
            putJsonObject("phone_number") {
                put("type", "string")
                put("description", "Optional fallback phone number for contact verification")
            }
            putJsonObject("whatsapp_type") {
                put("type", "string")
                put("description", "WhatsApp app type: personal or business (default: business)")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("personal"))
                    add(JsonPrimitive("business"))
                })
            }
            putJsonObject("strict_contact_match") {
                put("type", "boolean")
                put(
                    "description",
                    "Abort before send if contact cannot be verified on-screen (default: true)."
                )
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("action"))
        })
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val action = argString(args, "action")?.trim()?.lowercase()
            ?: return resultError("Action is required")

        return when (action) {
            "send_message" -> runVerifiedSendMessage(args, context)

            "select_contact", "type_message", "press_send", "press_cancel" -> {
                resultError(
                    "Action '$action' is disabled because unverified step-by-step mode is unsafe. " +
                        "Use send_whatsapp_message (or whatsapp_automation with action=send_message) " +
                        "for full screen-state verified execution."
                )
            }

            else -> resultError(
                "Unsupported action. Use: send_message, select_contact, type_message, press_send, press_cancel"
            )
        }
    }

    private suspend fun runVerifiedSendMessage(
        args: JsonObject?,
        context: Context
    ): McpToolCallResult {
        val contactName = argString(args, "contact_name")?.trim()
            ?: return resultError("contact_name is required for action=send_message")
        val message = argString(args, "message")?.trim()
            ?: return resultError("message is required for action=send_message")
        val whatsappType = argString(args, "whatsapp_type")?.trim()?.lowercase() ?: "business"
        val phoneNumber = argString(args, "phone_number")?.trim()?.takeIf { it.isNotBlank() }
        val strictContactMatch = argBoolean(args, "strict_contact_match") ?: true

        val delegatedArgs = buildJsonObject {
            put("contact_name", contactName)
            put("message", message)
            put("whatsapp_type", whatsappType)
            put("strict_contact_match", strictContactMatch)
            if (phoneNumber != null) {
                put("phone_number", phoneNumber)
            }
        }
        return WhatsAppSendTool().execute(delegatedArgs, context)
    }
}
