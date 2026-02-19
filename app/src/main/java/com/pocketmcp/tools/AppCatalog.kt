package com.pocketmcp.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

internal data class LaunchableApp(
    val packageName: String,
    val appName: String
)

internal data class AppResolveResult(
    val match: LaunchableApp?,
    val suggestions: List<LaunchableApp>
)

internal fun getLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val byPackage = linkedMapOf<String, LaunchableApp>()
    
    // First, try to get all installed packages with launcher activities
    try {
        val installedPackages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        installedPackages.forEach { packageInfo ->
            val packageName = packageInfo.packageName
            if (byPackage.containsKey(packageName)) {
                return@forEach
            }
            
            // Try to get a launchable activity
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                val appInfo = packageInfo.applicationInfo
                val label = pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty()
                byPackage[packageName] = LaunchableApp(
                    packageName = packageName,
                    appName = if (label.isBlank()) packageName else label
                )
            }
        }
    } catch (e: Exception) {
        // Fallback to the original method if the new one fails
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        activities.forEach { info ->
            val activityInfo = info.activityInfo ?: return@forEach
            val pkg = activityInfo.packageName ?: return@forEach
            if (byPackage.containsKey(pkg)) {
                return@forEach
            }
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            byPackage[pkg] = LaunchableApp(
                packageName = pkg,
                appName = if (label.isBlank()) pkg else label
            )
        }
    }

    return byPackage.values.sortedBy { it.appName.lowercase(Locale.getDefault()) }
}

internal fun resolveLaunchableApp(
    apps: List<LaunchableApp>,
    packageNameArg: String?,
    appNameArg: String?
): AppResolveResult {
    if (apps.isEmpty()) {
        return AppResolveResult(match = null, suggestions = emptyList())
    }

    val pkg = packageNameArg?.trim().orEmpty()
    val name = appNameArg?.trim().orEmpty()
    if (pkg.isBlank() && name.isBlank()) {
        return AppResolveResult(match = null, suggestions = emptyList())
    }

    if (pkg.isNotBlank()) {
        val exactPackage = apps.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
        if (exactPackage != null) {
            return AppResolveResult(match = exactPackage, suggestions = emptyList())
        }

        val packageSuggestions = apps
            .mapNotNull { app ->
                val score = scoreQueryAgainstApp(pkg, app)
                if (score <= 0) null else app to score
            }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
        return AppResolveResult(match = null, suggestions = packageSuggestions)
    }

    val nameQuery = name
    val exactName = apps.firstOrNull { it.appName.equals(nameQuery, ignoreCase = true) }
    if (exactName != null) {
        return AppResolveResult(match = exactName, suggestions = emptyList())
    }

    val ranked = apps
        .mapNotNull { app ->
            val score = scoreQueryAgainstApp(nameQuery, app)
            if (score <= 0) null else app to score
        }
        .sortedByDescending { it.second }

    val match = ranked.firstOrNull()?.first
    val suggestions = ranked.take(5).map { it.first }
    return AppResolveResult(match = match, suggestions = suggestions)
}

internal fun searchLaunchableApps(
    apps: List<LaunchableApp>,
    query: String,
    limit: Int
): List<LaunchableApp> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) {
        return apps.take(limit)
    }
    return apps
        .mapNotNull { app ->
            val score = scoreQueryAgainstApp(normalizedQuery, app)
            if (score <= 0) null else app to score
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
}

private fun scoreQueryAgainstApp(query: String, app: LaunchableApp): Int {
    val normalizedQuery = normalizeForMatch(query)
    if (normalizedQuery.isBlank()) {
        return 0
    }

    val appName = app.appName
    val packageName = app.packageName
    val normalizedName = normalizeForMatch(appName)
    val normalizedPackage = normalizeForMatch(packageName)

    if (packageName.equals(query, ignoreCase = true)) return 1200
    if (appName.equals(query, ignoreCase = true)) return 1100
    if (normalizedName == normalizedQuery) return 1000
    if (normalizedPackage == normalizedQuery) return 980

    if (normalizedName.startsWith(normalizedQuery)) {
        return 920 - (normalizedName.length - normalizedQuery.length).coerceAtMost(120)
    }
    if (normalizedPackage.startsWith(normalizedQuery)) {
        return 880 - (normalizedPackage.length - normalizedQuery.length).coerceAtMost(120)
    }

    val containsInName = normalizedName.indexOf(normalizedQuery)
    if (containsInName >= 0) {
        return 820 - containsInName.coerceAtMost(120)
    }
    val containsInPackage = normalizedPackage.indexOf(normalizedQuery)
    if (containsInPackage >= 0) {
        return 760 - containsInPackage.coerceAtMost(120)
    }

    val queryTokens = tokenize(query)
    if (queryTokens.isEmpty()) {
        return 0
    }
    val nameTokens = tokenize(appName)
    val packageTokens = tokenize(packageName)
    var score = 0
    queryTokens.forEach { token ->
        val inName = nameTokens.any { it.contains(token) || token.contains(it) }
        val inPackage = packageTokens.any { it.contains(token) || token.contains(it) }
        if (inName) score += 80
        else if (inPackage) score += 60
    }
    return score
}

private fun normalizeForMatch(value: String): String {
    return value.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")
}

private fun tokenize(value: String): List<String> {
    return value.lowercase(Locale.getDefault())
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() }
}
