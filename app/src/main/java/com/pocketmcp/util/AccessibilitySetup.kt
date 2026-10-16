package com.pocketmcp.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * One-time ADB grant unlock for the accessibility service.
 *
 * Samsung Auto Blocker (and similar OEM hardening) can refuse the
 * accessibility toggle in system Settings. When the user runs
 * [ADB_GRANT_COMMAND] once over USB/wireless debugging, the app receives
 * [Manifest.permission.WRITE_SECURE_SETTINGS] and can enable or disable its
 * own accessibility service directly through [Settings.Secure], bypassing
 * the blocked settings UI.
 *
 * The pure string helpers ([isEnabledIn], [mergeComponent]) are unit tested
 * without any Android framework dependency.
 */
public object AccessibilitySetup {
    public const val SERVICE_COMPONENT: String =
        "com.pocketmcp/com.pocketmcp.accessibility.PhoneAccessibilityService"

    public const val ADB_GRANT_COMMAND: String =
        "adb shell pm grant com.pocketmcp android.permission.WRITE_SECURE_SETTINGS"

    public const val ADB_ENABLE_COMMAND: String =
        "adb shell settings put secure enabled_accessibility_services " +
            SERVICE_COMPONENT +
            " && adb shell settings put secure accessibility_enabled 1"

    /** True when the one-time ADB grant has been applied to this install. */
    public fun hasSecureSettingsAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    /** True when the PocketMCP accessibility service is currently listed as enabled. */
    public fun isServiceEnabled(context: Context): Boolean = isEnabledIn(
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ),
        SERVICE_COMPONENT
    )

    /**
     * Enables or disables the PocketMCP accessibility service, preserving any
     * other services the user has enabled. Returns false when the one-time
     * ADB grant is missing or the write was rejected.
     */
    public fun setServiceEnabled(context: Context, enabled: Boolean): Boolean {
        if (!hasSecureSettingsAccess(context)) {
            return false
        }
        return runCatching {
            val resolver = context.contentResolver
            val raw = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            val merged = mergeComponent(raw, SERVICE_COMPONENT, enabled)
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged.joinToString(":")
            )
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                if (merged.isEmpty()) 0 else 1
            )
            true
        }.getOrDefault(false)
    }

    /** Pure helper: does a colon-separated secure-settings value list [component]? */
    public fun isEnabledIn(raw: String?, component: String): Boolean =
        raw?.split(':')
            ?.any { it.trim().equals(component, ignoreCase = true) }
            ?: false

    /**
     * Pure helper: returns the service list after adding or removing
     * [component], preserving order of all other entries and removing
     * duplicates (case-insensitive).
     */
    public fun mergeComponent(raw: String?, component: String, enabled: Boolean): List<String> {
        val current = raw
            ?.split(':')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toMutableList()
            ?: mutableListOf()
        current.removeAll { it.equals(component, ignoreCase = true) }
        if (enabled) {
            current.add(component)
        }
        return current
    }
}
