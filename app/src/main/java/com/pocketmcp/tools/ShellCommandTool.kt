package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.TimeUnit

private const val MAX_OUTPUT_CHARS = 12_000

class ShellCommandTool : McpToolHandler {
    override val name = "shell"
    override val description = "Run a shell command with timeout and safety filters."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "Command to execute.")
            }
            putJsonObject("timeout_seconds") {
                put("type", "integer")
                put("description", "Execution timeout, 1-30 (default: 10).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val command = argString(args, "command")?.trim()
        if (command.isNullOrBlank()) {
            return resultError("Missing required argument: command")
        }
        if (command.length > 500) {
            return resultError("Command too long. Maximum length is 500 characters.")
        }
        if (isBlocked(command)) {
            return resultError("Blocked command for safety.")
        }

        val timeoutSeconds = (argInt(args, "timeout_seconds") ?: 10).coerceIn(1, 30)
        return runCatching {
            runCommand(command, timeoutSeconds)
        }.getOrElse { error ->
            resultError("Command failed: ${error.message ?: "unknown error"}")
        }
    }

    private suspend fun runCommand(command: String, timeoutSeconds: Int): McpToolCallResult =
        coroutineScope {
            val process = ProcessBuilder("sh", "-c", command).start()

            val stdoutDeferred = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrDeferred = async(Dispatchers.IO) {
                process.errorStream.bufferedReader().use { it.readText() }
            }

            val startMs = System.currentTimeMillis()
            val finished = withContext(Dispatchers.IO) {
                process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            }

            if (!finished) {
                process.destroy()
                withContext(Dispatchers.IO) {
                    process.waitFor(250, TimeUnit.MILLISECONDS)
                }
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }

            val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            val rawStdout = stdoutDeferred.await()
            val rawStderr = stderrDeferred.await()
            val stdout = truncate(rawStdout)
            val stderr = truncate(rawStderr)

            val payload = buildJsonObject {
                put("command", command)
                put("timed_out", !finished)
                put("timeout_seconds", timeoutSeconds)
                put("duration_ms", durationMs)
                put("exit_code", if (finished) process.exitValue() else -1)
                put("stdout", stdout)
                put("stderr", stderr)
                put("stdout_truncated", rawStdout.length > MAX_OUTPUT_CHARS)
                put("stderr_truncated", rawStderr.length > MAX_OUTPUT_CHARS)
            }
            resultJson(payload)
        }

    private fun truncate(value: String): String {
        return if (value.length <= MAX_OUTPUT_CHARS) value else value.take(MAX_OUTPUT_CHARS)
    }

    private fun isBlocked(command: String): Boolean {
        val normalized = command.lowercase()
        if (normalized.contains('\n') || normalized.contains('\r')) {
            return true
        }

        val blockedFragments = listOf(
            "rm -rf",
            "mkfs",
            "dd if=",
            "reboot",
            "shutdown",
            "poweroff",
            ":(){",
            "setenforce 0",
            "mount -o remount",
            "pm uninstall"
        )
        return blockedFragments.any { fragment -> normalized.contains(fragment) }
    }
}
