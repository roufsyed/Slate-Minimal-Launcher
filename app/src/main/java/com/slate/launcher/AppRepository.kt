package com.slate.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

class AppRepository(private val context: Context, private val prefs: PreferencesManager) {

    fun getAllApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        val hidden = prefs.hiddenApps
        val selfPackage = context.packageName

        val apps = resolveInfos
            .filter { it.activityInfo.packageName != selfPackage }
            .filter { it.activityInfo.packageName !in hidden }
            .map {
                AppInfo(
                    name = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    activityName = it.activityInfo.name
                )
            }

        return if (prefs.sortByUsage) {
            apps.sortedByDescending { prefs.getUsageCount(it.packageName) }
        } else {
            apps.sortedBy { it.name.lowercase() }
        }
    }
}
