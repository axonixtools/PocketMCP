package com.pocketmcp.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.NetworkInterface

class DeviceInfoTool : McpToolHandler {
    override val name = "device_info"
    override val description = "Battery, model, OS, network, and memory details."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(args: JsonObject?, context: Context): McpToolCallResult {
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.getMemoryInfo(info)
        }

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val batteryStatusIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val statusValue = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = statusValue == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusValue == BatteryManager.BATTERY_STATUS_FULL

        val result = buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("android_version", Build.VERSION.RELEASE ?: "unknown")
            put("sdk_int", Build.VERSION.SDK_INT)
            put("battery_percent", batteryLevel)
            put("is_charging", isCharging)
            put("memory_total_mb", memoryInfo.totalMem / (1024 * 1024))
            put("memory_available_mb", memoryInfo.availMem / (1024 * 1024))
            put("network_type", getNetworkType(context))
            put("ip_address", getIpAddress())
            put("timestamp_ms", System.currentTimeMillis())
        }
        return resultJson(result)
    }

    private fun getNetworkType(context: Context): String {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "offline"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "offline"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    private fun getIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "unknown"
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress ?: "unknown"
                    }
                }
            }
            "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
