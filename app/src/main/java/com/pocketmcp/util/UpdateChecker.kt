package com.pocketmcp.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
public data class UpdateManifest(
    public val versionName: String,
    public val versionCode: Int,
    public val apkUrl: String,
    public val notes: String? = null,
)

/**
 * Lightweight in-app updater. Fetches a small JSON manifest from a
 * configurable source, compares its versionCode with the running build, and
 * downloads the APK for the system package installer.
 *
 * Manifest shape:
 * {
 *   "versionName": "1.0.0-beta.9",
 *   "versionCode": 9,
 *   "apkUrl": "http://host:port/PocketMCP-debug.apk",
 *   "notes": "optional changelog"
 * }
 */
public object UpdateChecker {
    public const val PREF_UPDATE_URL: String = "update_source_url"
    // No URL ships with the app: set your own manifest URL in
    // Settings → App Updates (stored only on the device). Personal endpoints
    // are never committed or baked into builds.
    public const val DEFAULT_SOURCE_URL: String = ""

    private val json = Json { ignoreUnknownKeys = true }
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 20_000

    public fun parseManifest(raw: String): UpdateManifest? =
        runCatching { json.decodeFromString(UpdateManifest.serializer(), raw) }
            .getOrNull()
            ?.takeIf { it.versionCode > 0 && it.apkUrl.isNotBlank() }

    public fun isNewer(manifest: UpdateManifest, currentVersionCode: Int): Boolean =
        manifest.versionCode > currentVersionCode

    public suspend fun fetch(sourceUrl: String): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            try {
                val status = connection.responseCode
                check(status in 200..299) { "Update server returned HTTP $status" }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseManifest(body) ?: error("Update manifest is not valid JSON")
            } finally {
                connection.disconnect()
            }
        }
    }

    public suspend fun download(apkUrl: String, target: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            try {
                val status = connection.responseCode
                check(status in 200..299) { "Download failed with HTTP $status" }
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
                }
                check(target.length() > 0) { "Downloaded file is empty" }
                target
            } finally {
                connection.disconnect()
            }
        }
    }
}
