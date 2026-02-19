package com.pocketmcp.tools

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.view.WindowManager
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class ScreenshotTool : McpToolHandler {
    override val name = "take_screenshot"
    override val description = "Take a screenshot of the current screen and return as base64 image"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("format") {
                put("type", "string")
                put("description", "Image format: 'png' or 'jpg'")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("png"))
                    add(JsonPrimitive("jpg"))
                })
            }
            putJsonObject("quality") {
                put("type", "integer")
                put("description", "Image quality for JPG (1-100, default: 90)")
                put("minimum", 1)
                put("maximum", 100)
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val format = argString(args, "format")?.trim()?.lowercase() ?: "png"
        val quality = argInt(args, "quality") ?: 90

        if (format !in setOf("png", "jpg")) {
            return resultError("Invalid format. Use 'png' or 'jpg'")
        }

        if (quality !in 1..100) {
            return resultError("Quality must be between 1 and 100")
        }

        return try {
            val screenshotData = withContext(Dispatchers.Main) {
                captureScreenshot(context, format, quality)
            }

            val payload = buildJsonObject {
                put("action", "take_screenshot")
                put("format", format)
                put("quality", quality)
                put("success", true)
                put("message", "Screenshot captured successfully")
                put("data", screenshotData)
                put("size", screenshotData.length)
                put("mime_type", if (format == "png") "image/png" else "image/jpeg")
            }
            resultJson(payload)
        } catch (e: Exception) {
            resultError("Failed to capture screenshot: ${e.message}")
        }
    }

    private suspend fun captureScreenshot(context: Context, format: String, quality: Int): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureScreenshotWithPixelCopy(context, format, quality)
        } else {
            captureScreenshotLegacy(context, format, quality)
        }
    }

    private suspend fun captureScreenshotWithPixelCopy(context: Context, format: String, quality: Int): String = suspendCancellableCoroutine { continuation ->
        try {
            val activity = context as? Activity
                ?: throw IllegalStateException("PixelCopy screenshot requires an Activity context")

            val decorView = activity.window.decorView
            val width = if (decorView.width > 0) decorView.width else activity.resources.displayMetrics.widthPixels
            val height = if (decorView.height > 0) decorView.height else activity.resources.displayMetrics.heightPixels
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            PixelCopy.request(
                activity.window,
                null,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        val base64 = bitmapToBase64(bitmap, format, quality)
                        continuation.resume(base64)
                    } else {
                        continuation.resumeWith(Result.failure(Exception("PixelCopy failed with result: $result")))
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            continuation.resumeWith(Result.failure(e))
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun captureScreenshotLegacy(context: Context, format: String, quality: Int): String = withContext(Dispatchers.Main) {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = android.view.View(context)
            view.layoutParams = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            
            val display = windowManager.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            display.getMetrics(metrics)

            val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)

            bitmapToBase64(bitmap, format, quality)
        } catch (e: Exception) {
            throw Exception("Legacy screenshot capture failed: ${e.message}")
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap, format: String, quality: Int): String {
        val outputStream = ByteArrayOutputStream()
        
        when (format) {
            "png" -> {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            "jpg" -> {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }
        }
        
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
