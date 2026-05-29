package com.slate.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

class AppRepository(private val context: Context, private val prefs: PreferencesManager) {

    fun getAllApps(forceAlphabetical: Boolean = false): List<AppInfo> {
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
        val sortByUsage = !forceAlphabetical && prefs.sortByUsage
        val sorted = if (sortByUsage) {
            apps.sortedByDescending { prefs.getUsageCount(it.packageName) }
        } else {
            apps.sortedBy { it.name.lowercase() }
        }
        // Pinned apps float to the top, preserving sort order within each group
        return sorted.sortedByDescending { it.packageName in pinned }
    }

    /**
     * Build the home-screen list with folder awareness.
     *   - When [folderId] is null: returns pinned apps first, then a merged sort of
     *     non-foldered apps interleaved with folder entries by the global sort rule.
     *   - When [folderId] points to a folder: returns a [HomeItem.BackOut] header followed by
     *     the folder's apps (filtered for hidden / uninstalled). If the folder is missing,
     *     falls back to the main view rather than rendering nothing.
     *
     * Folders containing no currently-visible apps (everything inside is hidden or uninstalled)
     * are omitted from the main view — we'd otherwise render a folder row that expands to an
     * empty list. Reconciliation against the live install set also prunes stale package entries
     * from each folder's persisted list.
     */
    fun getHomeItems(folderId: String? = null): List<HomeItem> {
        val allApps = getAllApps()
        // Reconcile uses the FULL install set, not the hidden-filtered set, so hidden apps
        // retain their folder membership and reappear inside the folder when unhidden.
        val installedPackages = queryAllInstalledLauncherPackages()
        FolderStore.reconcile(prefs, installedPackages)

        if (folderId != null) {
            val folder = FolderStore.find(prefs, folderId)
                ?: return getHomeItems(null)  // gracefully fall back if the folder vanished
            val folderApps = allApps.filter { it.packageName in folder.packages }
            val sorted = if (prefs.sortByUsage) {
                folderApps.sortedByDescending { prefs.getUsageCount(it.packageName) }
            } else {
                folderApps.sortedBy { it.name.lowercase() }
            }
            return listOf(HomeItem.BackOut) + sorted.map { HomeItem.AppItem(it) }
        }

        val pinned = prefs.pinnedApps
        val pinnedItems = allApps.filter { it.packageName in pinned }.map { HomeItem.AppItem(it) }

        val packagesInFolders = FolderStore.packagesInAnyFolder(prefs)
        val nonPinnedFlatApps = allApps.filter {
            it.packageName !in pinned && it.packageName !in packagesInFolders
        }
        // A folder shows on the main list if it has at least one *visible* (not-hidden) app.
        // A folder containing only hidden apps stays in the data model but is omitted from
        // render — so unhiding a member restores the folder cleanly.
        val visiblePackages = allApps.mapTo(HashSet()) { it.packageName }
        val visibleFolders = FolderStore.all(prefs).filter { folder ->
            folder.packages.any { pkg -> pkg in visiblePackages }
        }

        // Interleave apps + folders under the same sort rule. visibleCount is computed against
        // the user's visible-app set so the "count" folder-style marker matches what the user
        // sees on expand (hidden / uninstalled members aren't counted).
        val mixedItems: List<HomeItem> =
            nonPinnedFlatApps.map { HomeItem.AppItem(it) } +
            visibleFolders.map { folder ->
                HomeItem.FolderItem(folder, folder.packages.count { it in visiblePackages })
            }

        val sortedMixed = if (prefs.sortByUsage) {
            mixedItems.sortedByDescending { item ->
                when (item) {
                    is HomeItem.AppItem -> prefs.getUsageCount(item.info.packageName)
                    // Folder weight is the sum of contained apps' usage counts.
                    is HomeItem.FolderItem ->
                        item.folder.packages.sumOf { prefs.getUsageCount(it) }
                    // ContactItem only appears in the live search list, never the static home
                    // mix that this sort serves. Defensive 0 keeps the `when` exhaustive.
                    is HomeItem.ContactItem -> 0
                    HomeItem.BackOut -> 0
                }
            }
        } else {
            mixedItems.sortedBy { item ->
                when (item) {
                    is HomeItem.AppItem -> item.info.name.lowercase()
                    is HomeItem.FolderItem -> item.folder.name.lowercase()
                    is HomeItem.ContactItem -> ""
                    HomeItem.BackOut -> ""
                }
            }
        }

        return pinnedItems + sortedMixed
    }

    /**
     * Set of every launcher-visible package on the device, ignoring the user's hidden-apps
     * preference. Used by [FolderStore.reconcile] so a folder containing a hidden app doesn't
     * get its membership pruned — unhiding must restore the original folder layout.
     */
    private fun queryAllInstalledLauncherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pm = context.packageManager
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return infos.mapTo(HashSet()) { it.activityInfo.packageName }
    }
}
