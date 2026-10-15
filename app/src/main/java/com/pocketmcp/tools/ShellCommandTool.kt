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
        if (isBlockedShellCommand(command)) {
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

}

/**
 * Best-effort safety filter for the shell tool.
 *
 * This is a speed bump, not a security boundary: commands run as the app user
 * on a non-rooted device. The filter rejects obvious destructive operations
 * before they are executed.
 */
internal fun isBlockedShellCommand(command: String): Boolean {
    if (command.contains('\n') || command.contains('\r')) return true

    val normalized = command.lowercase().replace(WHITESPACE, " ").trim()
    if (BLOCKED_FRAGMENTS.any { fragment -> normalized.contains(fragment) }) return true
    return isRecursiveForceDelete(normalized)
}

private val WHITESPACE = Regex("\\s+")

private val BLOCKED_FRAGMENTS = listOf(
    "rm -rf",
    "rm -fr",
    "mkfs",
    "dd if=",
    "reboot",
    "shutdown",
    "poweroff",
    "halt",
    "fastboot",
    ":(){",
    "setenforce 0",
    "mount -o remount",
    "pm uninstall"
)

/**
 * Detects `rm` invocations that combine recursive and force flags regardless
 * of flag order, spacing (`rm -rf`, `rm -fr`, `rm -r -f`, `rm -f -r`).
 */
private fun isRecursiveForceDelete(normalized: String): Boolean {
    val tokens = normalized.split(' ')
    val rmIndex = tokens.indexOf("rm")
    if (rmIndex == -1) return false

    var recursive = false
    var force = false
    for (token in tokens.drop(rmIndex + 1).takeWhile { it.startsWith("-") }) {
        if ('r' in token) recursive = true
        if ('f' in token) force = true
    }
    return recursive && force
}
