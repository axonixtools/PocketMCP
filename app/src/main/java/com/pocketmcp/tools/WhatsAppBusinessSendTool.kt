package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class WhatsAppBusinessSendTool : McpToolHandler {
    override val name = "send_whatsapp_business_message"
    override val description =
        "Send a WhatsApp Business message by contact using full screen-state verification."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("contact_name") {
                put("type", "string")
                put("description", "Contact name in WhatsApp Business.")
            }
            putJsonObject("recipient") {
                put("type", "string")
                put("description", "Alias of contact_name.")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Message text to send.")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Alias of message.")
            }
            putJsonObject("phone_number") {
                put("type", "string")
                put("description", "Optional number fallback for contact verification.")
            }
            putJsonObject("raw_command") {
                put("type", "string")
                put(
                    "description",
                    "Natural-language command, e.g. 'send message to Fahad Shakoor on whatsapp bussiness kesa laga mera shugal'"
                )
            }
            putJsonObject("command") {
                put("type", "string")
                put("description", "Alias of raw_command.")
            }
            putJsonObject("request") {
                put("type", "string")
                put("description", "Alias of raw_command.")
            }
            putJsonObject("strict_contact_match") {
                put("type", "boolean")
                put(
                    "description",
                    "Abort before send if selected chat cannot be confidently verified (default: true)."
                )
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        var contactName = argString(args, "contact_name")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: argString(args, "recipient")?.trim()?.takeIf { it.isNotBlank() }
        var message = argString(args, "message")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: argString(args, "text")?.trim()?.takeIf { it.isNotBlank() }
        var rawCommand = argString(args, "raw_command")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: argString(args, "command")?.trim()?.takeIf { it.isNotBlank() }
            ?: argString(args, "request")?.trim()?.takeIf { it.isNotBlank() }

        // Fallback for weaker tool-calling models that place the full command inside `message`.
        if (rawCommand == null && contactName == null && message != null) {
            val maybeParsed = parseNaturalSendCommand(message)
            if (maybeParsed.contactName != null && maybeParsed.message != null) {
                rawCommand = message
            }
        }

        if ((contactName == null || message == null) && rawCommand != null) {
            val parsed = parseNaturalSendCommand(rawCommand)
            if (contactName == null) {
                contactName = parsed.contactName
            }
            if (message == null) {
                message = parsed.message
            }
        }

        if (contactName == null) {
            return resultError(
                "contact_name is required (or provide raw_command like: send message to Fahad Shakoor on whatsapp bussiness hello)"
            )
        }
        if (message == null) {
            return resultError(
                "message is required (or provide raw_command containing the message text)"
            )
        }
        val phoneNumber = argString(args, "phone_number")?.trim()?.takeIf { it.isNotBlank() }
        val strictContactMatch = argBoolean(args, "strict_contact_match") ?: true

        val delegatedArgs = buildJsonObject {
            put("contact_name", contactName)
            put("message", message)
            put("whatsapp_type", "business")
            put("strict_contact_match", strictContactMatch)
            if (phoneNumber != null) {
                put("phone_number", phoneNumber)
            }
        }
        return WhatsAppSendTool().execute(delegatedArgs, context)
    }

    private fun parseNaturalSendCommand(rawCommand: String): ParsedCommand {
        val command = rawCommand.trim()
        if (command.isBlank()) {
            return ParsedCommand(contactName = null, message = null)
        }

        val sendPattern = Regex(
            pattern = "send\\s+(?:a\\s+)?message\\s+to\\s+(.+?)\\s+on\\s+what'?s?app(?:\\s+business|\\s+bussiness|\\s+buisness|\\s+w4b)?\\s+(.+)",
            option = RegexOption.IGNORE_CASE
        )
        val sendMatch = sendPattern.find(command)
        if (sendMatch != null) {
            return ParsedCommand(
                contactName = sendMatch.groupValues[1].trim().trim(',', '.', ':', ';'),
                message = sendMatch.groupValues[2].trim()
            )
        }

        val toPattern = Regex(
            pattern = "to\\s+(.+?)\\s+(?:on\\s+)?what'?s?app(?:\\s+business|\\s+bussiness|\\s+buisness|\\s+w4b)?\\s+(.+)",
            option = RegexOption.IGNORE_CASE
        )
        val toMatch = toPattern.find(command)
        if (toMatch != null) {
            return ParsedCommand(
                contactName = toMatch.groupValues[1].trim().trim(',', '.', ':', ';'),
                message = toMatch.groupValues[2].trim()
            )
        }

        return ParsedCommand(contactName = null, message = null)
    }

    private data class ParsedCommand(
        val contactName: String?,
        val message: String?
    )
}
