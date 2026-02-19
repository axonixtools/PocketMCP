package com.pocketmcp.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "PocketMCP.Accessibility"

class PhoneAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: this service is used for user-triggered global actions and gestures.
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private suspend fun dispatchSwipe(
        direction: String,
        distanceRatio: Float,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val ratio = distanceRatio.coerceIn(0.15f, 0.9f)
        val centerX = width / 2f
        val centerY = height / 2f
        val dx = width * ratio
        val dy = height * ratio

        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up" -> floatArrayOf(centerX, centerY + dy / 2f, centerX, centerY - dy / 2f)
            "down" -> floatArrayOf(centerX, centerY - dy / 2f, centerX, centerY + dy / 2f)
            "left" -> floatArrayOf(centerX + dx / 2f, centerY, centerX - dx / 2f, centerY)
            "right" -> floatArrayOf(centerX - dx / 2f, centerY, centerX + dx / 2f, centerY)
            else -> {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(120L, 1500L)
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val started = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }, null)

        if (!started && continuation.isActive) {
            continuation.resume(false)
        }
    }

    private suspend fun dispatchTap(
        x: Int,
        y: Int,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val clampedX = x.coerceIn(0, width - 1).toFloat()
        val clampedY = y.coerceIn(0, height - 1).toFloat()
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(40L, 600L)
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val started = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }, null)

        if (!started && continuation.isActive) {
            continuation.resume(false)
        }
    }

    companion object {
        @Volatile
        private var instance: PhoneAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        fun getInstance(): PhoneAccessibilityService? = instance

        fun runGlobalAction(action: Int): Boolean {
            return instance?.performGlobalAction(action) == true
        }

        suspend fun runSwipe(
            direction: String,
            distanceRatio: Float,
            durationMs: Long
        ): Boolean {
            val service = instance ?: return false
            return service.dispatchSwipe(direction, distanceRatio, durationMs)
        }

        suspend fun runTap(
            x: Int,
            y: Int,
            durationMs: Long = 80L
        ): Boolean {
            val service = instance ?: return false
            return service.dispatchTap(x, y, durationMs)
        }

        suspend fun tapVisibleNodeByText(
            query: String,
            exact: Boolean = false,
            occurrence: Int = 1
        ): TapResult {
            val service = instance ?: return TapResult(
                success = false,
                error = "Accessibility service is not connected."
            )
            val root = service.rootInActiveWindow ?: return TapResult(
                success = false,
                error = "No active window found. Unlock your phone and open an app."
            )

            val target = findNodeByTextOrDescription(root, query, exact, occurrence)
                ?: return TapResult(
                    success = false,
                    error = "No matching node found for '$query'."
                )

            if (clickNodeOrParent(target)) {
                return TapResult(
                    success = true,
                    packageName = root.packageName?.toString().orEmpty(),
                    matchedText = target.text?.toString(),
                    matchedDescription = target.contentDescription?.toString()
                )
            }

            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val tapped = service.dispatchTap(bounds.centerX(), bounds.centerY(), 80L)
                if (tapped) {
                    return TapResult(
                        success = true,
                        packageName = root.packageName?.toString().orEmpty(),
                        matchedText = target.text?.toString(),
                        matchedDescription = target.contentDescription?.toString()
                    )
                }
            }

            return TapResult(
                success = false,
                error = "Found a matching node, but click action failed."
            )
        }

        /**
         * Capture a lightweight snapshot of the current screen using the accessibility tree.
         *
         * This is text-focused: it surfaces visible text and content descriptions that an AI
         * can use to describe "what is going on" on screen (e.g., current YouTube video title).
         */
        fun captureScreenSnapshot(maxNodes: Int = 80): ScreenSnapshot? {
            val service = instance ?: return null
            val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: return null

            val nodes = mutableListOf<ScreenNodeSnapshot>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)

            while (queue.isNotEmpty() && nodes.size < maxNodes) {
                val node = queue.removeFirst()

                val text = node.text
                val description = node.contentDescription
                if (!text.isNullOrBlank() || !description.isNullOrBlank()) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    nodes.add(
                        ScreenNodeSnapshot(
                            text = text?.toString().orEmpty(),
                            contentDescription = description?.toString().orEmpty(),
                            className = node.className?.toString().orEmpty(),
                            clickable = node.isClickable,
                            bounds = bounds
                        )
                    )
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child)
                }
            }

            return ScreenSnapshot(
                packageName = root.packageName?.toString().orEmpty(),
                rootClassName = root.className?.toString().orEmpty(),
                nodes = nodes
            )
        }

        private fun findNodeByTextOrDescription(
            root: AccessibilityNodeInfo,
            query: String,
            exact: Boolean,
            occurrence: Int
        ): AccessibilityNodeInfo? {
            if (query.isBlank()) {
                return null
            }

            val wantedIndex = occurrence.coerceAtLeast(1)
            var matchCount = 0
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)

            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val match = if (exact) {
                    text.equals(query, ignoreCase = true) || desc.equals(query, ignoreCase = true)
                } else {
                    text.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)
                }

                if (match) {
                    matchCount += 1
                    if (matchCount == wantedIndex) {
                        return node
                    }
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child)
                }
            }
            return null
        }

        private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            var current = node.parent
            while (current != null) {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                current = current.parent
            }
            return false
        }
    }
}

data class ScreenNodeSnapshot(
    val text: String,
    val contentDescription: String,
    val className: String,
    val clickable: Boolean,
    val bounds: Rect
)

data class ScreenSnapshot(
    val packageName: String,
    val rootClassName: String,
    val nodes: List<ScreenNodeSnapshot>
)

data class TapResult(
    val success: Boolean,
    val packageName: String? = null,
    val matchedText: String? = null,
    val matchedDescription: String? = null,
    val error: String? = null
)
