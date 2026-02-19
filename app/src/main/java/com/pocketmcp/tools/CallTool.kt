package com.pocketmcp.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.ActivityCompat
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive

class CallTool : McpToolHandler {
    override val name = "make_call"
    override val description = "Make a phone call to a specific number or contact"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("phone_number") {
                put("type", "string")
                put("description", "Phone number to call (e.g. '+1234567890' or '1234567890')")
            }
            putJsonObject("contact_name") {
                put("type", "string")
                put("description", "Optional: Contact name to search for first")
            }
        }
        put("required", kotlinx.serialization.json.buildJsonArray {
            add(JsonPrimitive("phone_number"))
        })
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val phoneNumber = argString(args, "phone_number")?.trim()
            ?: return resultError("Phone number is required")
        
        val contactName = argString(args, "contact_name")?.trim()

        if (phoneNumber.isBlank()) {
            return resultError("Phone number cannot be empty")
        }

        // Check for CALL_PHONE permission
        if (ActivityCompat.checkSelfPermission(
                context, 
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return resultError("CALL_PHONE permission not granted. Please enable in app settings.")
        }

        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(callIntent)
            
            val payload = buildJsonObject {
                put("action", "call_initiated")
                put("phone_number", phoneNumber)
                put("contact_name", contactName ?: "")
                put("success", true)
                put("message", "Call initiated successfully")
            }
            resultJson(payload)
            
        } catch (e: Exception) {
            resultError("Failed to initiate call: ${e.message}")
        }
    }
}
