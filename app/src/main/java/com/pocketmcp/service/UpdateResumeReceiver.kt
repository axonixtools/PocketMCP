package com.pocketmcp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.pocketmcp.security.SecurePrefs

/**
 * Resumes the MCP server after an in-app self-update.
 *
 * Android delivers ACTION_MY_PACKAGE_REPLACED to this app right after its
 * package is replaced. If the server was running before the update, start the
 * foreground service again so the phone keeps serving without a manual
 * "Start Server" tap. If the OS blocks the background start
 * (ForegroundServiceStartNotAllowedException), fail silently — the user can
 * still start the server from the app.
 */
public class UpdateResumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val state = context.getSharedPreferences(
            McpServerService.STATE_PREFS_NAME,
            Context.MODE_PRIVATE
        )
        if (!state.getBoolean(McpServerService.KEY_SERVER_SHOULD_RUN, false)) return

        val port = state.getInt(McpServerService.KEY_LAST_PORT, 8080)
        val apiKey = SecurePrefs.getApiKey(context)

        val serviceIntent = Intent(context, McpServerService::class.java).apply {
            action = McpServerService.ACTION_START
            putExtra(McpServerService.EXTRA_PORT, port)
            if (!apiKey.isNullOrBlank()) {
                putExtra(McpServerService.EXTRA_API_KEY, apiKey)
            }
        }

        runCatching {
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i("PocketMCP.Update", "Server auto-resumed after package update")
        }.onFailure {
            Log.w("PocketMCP.Update", "Could not auto-resume server after update: ${it.message}")
        }
    }
}
