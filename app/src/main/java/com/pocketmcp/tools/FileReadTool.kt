package com.pocketmcp.tools

import android.content.Context
import android.os.Environment
import android.util.Base64
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File

private const val DEFAULT_MAX_BYTES = 10_000

class FileReadTool : McpToolHandler {
    override val name = "read_file"
    override val description = "Read a file from allowed storage paths."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute file path.")
            }
            putJsonObject("max_bytes") {
                put("type", "integer")
                put("description", "Max bytes to read, 256-200000 (default: 10000).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val path = argString(args, "path")?.trim()
        if (path.isNullOrBlank()) {
            return resultError("Missing required argument: path")
        }

        val maxBytes = (argInt(args, "max_bytes") ?: DEFAULT_MAX_BYTES).coerceIn(256, 200_000)
        val canonicalFile = runCatching { File(path).canonicalFile }.getOrNull()
            ?: return resultError("Invalid path.")

        if (!canonicalFile.exists() || !canonicalFile.isFile) {
            return resultError("File not found: $path")
        }
        if (!isAllowedFile(canonicalFile, context)) {
            return resultError("Access denied. Path is outside allowed directories.")
        }

        return runCatching {
            val bytes = readLimited(canonicalFile, maxBytes + 1)
            val truncated = bytes.size > maxBytes
            val payloadBytes = if (truncated) bytes.copyOf(maxBytes) else bytes
            val binary = payloadBytes.take(512).any { it == 0.toByte() }

            val payload = buildJsonObject {
                put("path", canonicalFile.absolutePath)
                put("size_bytes", canonicalFile.length())
                put("returned_bytes", payloadBytes.size)
                put("truncated", truncated)
                if (binary) {
                    put("encoding", "base64")
                    put("content", Base64.encodeToString(payloadBytes, Base64.NO_WRAP))
                } else {
                    put("encoding", "utf-8")
                    put("content", payloadBytes.toString(Charsets.UTF_8))
                }
            }
            resultJson(payload)
        }.getOrElse { error ->
            resultError("Failed to read file: ${error.message ?: "unknown error"}")
        }
    }

    private fun readLimited(file: File, maxBytes: Int): ByteArray {
        file.inputStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() < maxBytes) {
                val remaining = maxBytes - out.size()
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) {
                    break
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun isAllowedFile(file: File, context: Context): Boolean {
        val allowedRoots = buildList {
            add(File("/sdcard"))
            add(Environment.getExternalStorageDirectory())
            context.filesDir?.let { add(it) }
            context.cacheDir?.let { add(it) }
            context.externalCacheDir?.let { add(it) }
            context.getExternalFilesDir(null)?.let { add(it) }
            context.getExternalFilesDirs(null)?.forEach { candidate ->
                if (candidate != null) add(candidate)
            }
        }.mapNotNull { root ->
            runCatching { root.canonicalFile }.getOrNull()
        }.distinctBy { it.absolutePath }

        return allowedRoots.any { root -> isChildPath(root, file) }
    }

    private fun isChildPath(root: File, file: File): Boolean {
        val rootPath = root.absolutePath
        val filePath = file.absolutePath
        return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }
}
