package com.pocketmcp.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

class FlashlightTool : McpToolHandler {
    override val name = "flashlight"
    override val description = "Control flashlight (on, off, toggle) and read known state."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "One of: on, off, toggle, status. Default: status.")
            }
            putJsonObject("camera_id") {
                put("type", "string")
                put("description", "Optional camera id. Defaults to a back camera with flash.")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val hasFlash = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        if (!hasFlash) {
            return resultError("This device does not report camera flash support.")
        }

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            return resultError("CAMERA permission is not granted.")
        }

        val action = (argString(args, "action") ?: "status").trim().lowercase()
        val requestedCameraId = argString(args, "camera_id")?.trim()?.takeIf { it.isNotEmpty() }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        return runCatching {
            val cameraId = selectTorchCameraId(cameraManager, requestedCameraId)
                ?: if (requestedCameraId == null) {
                    return resultError("No flashlight-capable camera was found.")
                } else {
                    return resultError("Camera id '$requestedCameraId' is invalid or has no flash.")
                }

            when (action) {
                "on" -> setTorch(cameraManager, cameraId, enabled = true, action = action)
                "off" -> setTorch(cameraManager, cameraId, enabled = false, action = action)
                "toggle" -> {
                    val nextState = !(torchStateByCamera[cameraId] ?: false)
                    setTorch(cameraManager, cameraId, enabled = nextState, action = action)
                }
                "status" -> statusPayload(cameraId)
                else -> resultError("Invalid action '$action'. Use on, off, toggle, or status.")
            }
        }.getOrElse { error ->
            when (error) {
                is SecurityException -> resultError("Torch access denied: ${error.message ?: "permission denied"}")
                is CameraAccessException -> resultError("Camera access error: ${error.message ?: "camera unavailable"}")
                is IllegalArgumentException -> resultError("Invalid camera id or torch operation.")
                else -> resultError("Flashlight operation failed: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun setTorch(
        manager: CameraManager,
        cameraId: String,
        enabled: Boolean,
        action: String
    ): McpToolCallResult {
        manager.setTorchMode(cameraId, enabled)
        torchStateByCamera[cameraId] = enabled
        val payload = buildJsonObject {
            put("action", action)
            put("camera_id", cameraId)
            put("torch_on", enabled)
            put("state_known", true)
        }
        return resultJson(payload)
    }

    private fun statusPayload(cameraId: String): McpToolCallResult {
        val state = torchStateByCamera[cameraId]
        val payload = buildJsonObject {
            put("action", "status")
            put("camera_id", cameraId)
            put("state_known", state != null)
            if (state != null) {
                put("torch_on", state)
            } else {
                put("torch_on", "unknown")
                put("note", "State is unknown until this tool sets the flashlight at least once.")
            }
        }
        return resultJson(payload)
    }

    private fun selectTorchCameraId(manager: CameraManager, requestedCameraId: String?): String? {
        val cameraIds = manager.cameraIdList.toList()
        if (cameraIds.isEmpty()) {
            return null
        }

        if (requestedCameraId != null) {
            return requestedCameraId.takeIf { id ->
                cameraIds.contains(id) && cameraHasFlash(manager, id)
            }
        }

        var fallbackId: String? = null
        cameraIds.forEach { id ->
            if (!cameraHasFlash(manager, id)) {
                return@forEach
            }
            val characteristics = manager.getCameraCharacteristics(id)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
            if (fallbackId == null) {
                fallbackId = id
            }
        }
        return fallbackId
    }

    private fun cameraHasFlash(manager: CameraManager, cameraId: String): Boolean {
        val flashAvailable = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
        return flashAvailable == true
    }

    private companion object {
        val torchStateByCamera = ConcurrentHashMap<String, Boolean>()
    }
}
