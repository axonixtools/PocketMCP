package com.pocketmcp.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores sensitive app preferences (currently the MCP API key) using
 * [EncryptedSharedPreferences], backed by a master key that lives in the
 * Android Keystore.
 *
 * If the encrypted store cannot be initialized the app degrades gracefully:
 * reads return null and writes are dropped (the key simply does not persist),
 * rather than silently falling back to plaintext storage.
 */
public object SecurePrefs {
    private const val TAG = "PocketMCP.SecurePrefs"
    private const val FILE_NAME = "pocket_mcp_secure_prefs"
    private const val KEY_API_KEY = "enc_api_key"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.onSuccess { cached = it }
                .onFailure {
                    Log.w(TAG, "Encrypted preferences unavailable; API key will not persist", it)
                }
                .getOrNull()
        }
    }

    public fun getApiKey(context: Context): String? =
        prefs(context)?.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    public fun setApiKey(context: Context, key: String) {
        if (key.isBlank()) {
            clearApiKey(context)
            return
        }
        runCatching { prefs(context)?.edit()?.putString(KEY_API_KEY, key)?.apply() }
            .onFailure { Log.w(TAG, "Failed to persist API key", it) }
    }

    public fun clearApiKey(context: Context) {
        runCatching { prefs(context)?.edit()?.remove(KEY_API_KEY)?.apply() }
            .onFailure { Log.w(TAG, "Failed to clear API key", it) }
    }
}
