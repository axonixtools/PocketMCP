package com.pocketmcp.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class LocationTool : McpToolHandler {
    override val name = "get_location"
    override val description =
        "Best available device location: a recent last-known fix, or else an active " +
            "GPS/network fix with a short timeout."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("max_age_seconds") {
                put("type", "integer")
                put("description", "Reject last-known location older than this many seconds (default: 300).")
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
            ?: getFreshLocation(context, timeoutMs = 10_000)
            ?: return resultError(
                "No location available. Make sure device location (GPS) is on, then retry."
            )

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

    /**
     * Requests a single fresh fix from GPS/network providers. Used when no
     * recent last-known location exists (for example after a reboot), so
     * callers do not have to open a map app first to seed one.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun getFreshLocation(context: Context, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = manager.getProviders(true).filter {
                it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
            }
            if (providers.isEmpty()) {
                return@withTimeoutOrNull null
            }

            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (resumed.compareAndSet(false, true)) {
                            runCatching { manager.removeUpdates(this) }
                            continuation.resume(location)
                        }
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit
                }

                providers.forEach { provider ->
                    runCatching {
                        manager.requestLocationUpdates(
                            provider,
                            0L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    }
                }

                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
            }
        }
}
