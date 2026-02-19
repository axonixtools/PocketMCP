package com.pocketmcp.tools

import android.accessibilityservice.AccessibilityService
import com.pocketmcp.accessibility.PhoneAccessibilityService
import kotlinx.coroutines.delay

internal suspend fun closeForegroundAppBestEffort(): Boolean {
    val openedRecents = PhoneAccessibilityService.runGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    if (!openedRecents) {
        return false
    }

    delay(300)
    val swiped = PhoneAccessibilityService.runSwipe(
        direction = "up",
        distanceRatio = 0.7f,
        durationMs = 260L
    )
    delay(120)
    PhoneAccessibilityService.runGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    return swiped
}
