package com.pocketmcp.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ContactsSearchTool : McpToolHandler {
    override val name = "search_contacts"
    override val description = "Search contacts by display name and return phone numbers."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Name query, e.g. 'Alice'.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Maximum results, 1-50 (default: 10).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            return resultError("READ_CONTACTS permission is not granted.")
        }

        val query = argString(args, "query")?.trim().orEmpty()
        if (query.isBlank()) {
            return resultError("Missing required argument: query")
        }
        val limit = (argInt(args, "limit") ?: 10).coerceIn(1, 50)

        val contactsUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

        val seen = HashSet<String>()
        val contacts = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            contactsUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext() && contacts.size < limit) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val number = if (numberIndex >= 0) cursor.getString(numberIndex) else null
                if (name.isNullOrBlank() || number.isNullOrBlank()) {
                    continue
                }

                val dedupeKey = "$name::$number"
                if (!seen.add(dedupeKey)) {
                    continue
                }
                contacts.add(name to number)
            }
        }

        val payload = buildJsonObject {
            put("query", query)
            put("count", contacts.size)
            put("contacts", buildJsonArray {
                contacts.forEach { (name, number) ->
                    add(
                        buildJsonObject {
                            put("name", name)
                            put("phone", number)
                        }
                    )
                }
            })
        }
        return resultJson(payload)
    }
}
