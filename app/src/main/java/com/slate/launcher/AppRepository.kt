package com.slate.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.slate.launcher.shortcuts.PinnedShortcutStore
import com.slate.launcher.shortcuts.ShortcutDestination

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
     * Reverses a usage-sorted list when the user wants the most-used apps at the bottom
     * (easier thumb reach) instead of the top. Only ever applied to the home-screen lists
     * built by [getHomeItems] - [getAllApps] is shared by the app drawer, search, and pickers,
     * which must keep the most-used app first regardless of this preference.
     */
    private fun <T> List<T>.applyMostUsedDirection(): List<T> =
        if (prefs.mostUsedPosition == "bottom") reversed() else this

    fun getHomeItems(folderId: String? = null): List<HomeItem> {
        val allApps = getAllApps()
        // Reconcile uses the FULL install set, not the hidden-filtered set, so hidden apps
        // retain their folder membership and reappear inside the folder when unhidden.
        val installedPackages = queryAllInstalledLauncherPackages()
        FolderStore.reconcile(prefs, installedPackages)

        val pinnedShortcuts = PinnedShortcutStore.reconcileInstalled(context, prefs)

        if (folderId != null) {
            val folder = FolderStore.find(prefs, folderId)
                ?: return getHomeItems(null)  // gracefully fall back if the folder vanished
            val folderApps = allApps.filter { it.packageName in folder.packages }
            val sorted = if (prefs.sortByUsage) {
                folderApps.sortedByDescending { prefs.getUsageCount(it.packageName) }
                    .applyMostUsedDirection()
            } else {
                folderApps.sortedBy { it.name.lowercase() }
            }
            return listOf(HomeItem.BackOut) + sorted.map { HomeItem.AppItem(it) }
        }

        val pinned = prefs.pinnedApps
        val pinnedFolderIds = prefs.pinnedFolders

        val packagesInFolders = FolderStore.packagesInAnyFolder(prefs)
        val nonPinnedFlatApps = allApps.filter {
            it.packageName !in pinned && it.packageName !in packagesInFolders
        }
        // A folder shows on the main list if it has at least one *visible* (not-hidden) app.
        // A folder containing only hidden apps stays in the data model but is omitted from
        // render - so unhiding a member restores the folder cleanly. Pinning doesn't change
        // that: a pinned folder with no visible members disappears too and comes back on
        // unhide, its id sitting untouched in the pin set meanwhile.
        val visiblePackages = allApps.mapTo(HashSet()) { it.packageName }
        val visibleFolders = FolderStore.all(prefs).filter { folder ->
            folder.packages.any { pkg -> pkg in visiblePackages }
        }
        val (pinnedFolderList, unpinnedFolderList) =
            visibleFolders.partition { it.id in pinnedFolderIds }

        // visibleCount is computed against the user's visible-app set so the "count"
        // folder-style marker matches what the user sees on expand (hidden / uninstalled
        // members aren't counted).
        fun folderItem(folder: Folder): HomeItem.FolderItem =
            HomeItem.FolderItem(folder, folder.packages.count { it in visiblePackages })

        // Pinned shortcuts targeting the application list render as permanent rows alongside
        // apps/folders here - never inside a folder (shortcuts have no folder-membership concept)
        // and never in the pinned section (out of scope for v1; see the shortcuts plan).
        val shortcutItems: List<HomeItem> = pinnedShortcuts
            .filter { ShortcutDestination.APP_LIST in it.destinations }
            .map { HomeItem.ShortcutItem(it) }

        // Pinned apps and pinned folders share one block at the top, ordered by the same rule
        // as everything else so a pinned folder sits among pinned apps the way an unpinned one
        // sits among unpinned apps.
        val pinnedItems: List<HomeItem> =
            allApps.filter { it.packageName in pinned }.map { HomeItem.AppItem(it) } +
            pinnedFolderList.map(::folderItem)

        // Interleave apps + folders + shortcuts under the same sort rule.
        val mixedItems: List<HomeItem> =
            nonPinnedFlatApps.map { HomeItem.AppItem(it) } +
            unpinnedFolderList.map(::folderItem) +
            shortcutItems

        return sortSection(pinnedItems, applyDirection = false) +
            sortSection(mixedItems, applyDirection = true)
    }

    /**
     * Orders one section of the home list. Both the pinned block and the main mixed list go
     * through here, so apps, folders and shortcuts stay under a single ordering rule.
     *
     * [applyDirection] is false for the pinned block on purpose: [PreferencesManager.mostUsedPosition]
     * moves the most-used entries within the main list, but the pinned block is an explicit
     * user-chosen anchor at the top and has never been reversed by that preference.
     */
    private fun sortSection(items: List<HomeItem>, applyDirection: Boolean): List<HomeItem> =
        if (prefs.sortByUsage) {
            val sorted = items.sortedByDescending { item ->
                when (item) {
                    is HomeItem.AppItem -> prefs.getUsageCount(item.info.packageName)
                    // Folder weight is the sum of contained apps' usage counts.
                    is HomeItem.FolderItem ->
                        item.folder.packages.sumOf { prefs.getUsageCount(it) }
                    // ContactItem only appears in the live search list, never the static home
                    // mix that this sort serves. Defensive 0 keeps the `when` exhaustive.
                    is HomeItem.ContactItem -> 0
                    // Shortcuts carry no usage signal of their own for v1 - render at the
                    // minimum weight, matching ContactItem's precedent.
                    is HomeItem.ShortcutItem -> 0
                    HomeItem.BackOut -> 0
                }
            }
            if (applyDirection) sorted.applyMostUsedDirection() else sorted
        } else {
            items.sortedBy { item ->
                when (item) {
                    is HomeItem.AppItem -> item.info.name.lowercase()
                    is HomeItem.FolderItem -> item.folder.name.lowercase()
                    is HomeItem.ContactItem -> ""
                    is HomeItem.ShortcutItem -> item.shortcut.pinnedLabel.lowercase()
                    HomeItem.BackOut -> ""
                }
            }
        }

    /**
     * Set of every launcher-visible package on the device, ignoring the user's hidden-apps
     * preference. Used by [FolderStore.reconcile] so a folder containing a hidden app doesn't
     * get its membership pruned - unhiding must restore the original folder layout.
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
