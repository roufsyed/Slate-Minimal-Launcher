package com.slate.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

class AppRepository(private val context: Context, private val prefs: PreferencesManager) {

    fun getAllApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pm = context.packageManager
        val resolveInfos: List<ResolveInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        val hidden = prefs.hiddenApps
        val selfPackage = context.packageName

        val apps = resolveInfos
            .filter { it.activityInfo.packageName != selfPackage }
            .filter { it.activityInfo.packageName !in hidden }
            .map {
                val pkg = it.activityInfo.packageName
                AppInfo(
                    name = prefs.getAppCustomName(pkg) ?: it.loadLabel(pm).toString(),
                    packageName = pkg,
                    activityName = it.activityInfo.name
                )
            }

        val pinned = prefs.pinnedApps
        val sorted = if (prefs.sortByUsage) {
            apps.sortedByDescending { prefs.getUsageCount(it.packageName) }
        } else {
            apps.sortedBy { it.name.lowercase() }
        }
        // Pinned apps float to the top, preserving sort order within each group
        return sorted.sortedByDescending { it.packageName in pinned }
    }
}
