package com.pocketmcp.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class LocationTool : McpToolHandler {
    override val name = "get_location"
    override val description = "Best available last-known location from device providers."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("max_age_seconds") {
                put("type", "integer")
                put("description", "Reject location older than this many seconds (default: 300).")
            }
        }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return resultError("Location permission is not granted.")
        }

        val maxAgeSeconds = (argInt(args, "max_age_seconds") ?: 300).coerceIn(1, 86_400)
        val location = getBestLastKnownLocation(context, maxAgeSeconds)
            ?: return resultError("No recent location available. Open a map app and try again.")

        val ageMs = (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        val payload = buildJsonObject {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy_m", location.accuracy)
            put("provider", location.provider ?: "unknown")
            put("timestamp_ms", location.time)
            put("age_seconds", ageMs / 1000)
        }
        return resultJson(payload)
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(context: Context, maxAgeSeconds: Int): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true)
        var best: Location? = null
        val cutoff = System.currentTimeMillis() - (maxAgeSeconds * 1000L)

        providers.forEach { provider ->
            val candidate = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            if (candidate == null || candidate.time < cutoff) {
                return@forEach
            }

            val existing = best
            if (existing == null) {
                best = candidate
                return@forEach
            }

            val newer = candidate.time > existing.time
            val moreAccurate = candidate.accuracy < existing.accuracy
            if (newer || moreAccurate) {
                best = candidate
            }
        }

        return best
    }
}
