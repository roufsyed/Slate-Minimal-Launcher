package com.slate.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.SystemClock
import com.slate.launcher.shortcuts.PinnedShortcut
import com.slate.launcher.shortcuts.PinnedShortcutStore
import com.slate.launcher.shortcuts.ShortcutDestination

class AppRepository(private val context: Context, private val prefs: PreferencesManager) {

    /**
     * One enumeration pass, serving both consumers. It replaces two independently drifting
     * bodies that previously queried the package manager separately - one for the rendered
     * list, one for the reconcile set - which could disagree and silently delete folders.
     *
     * [apps] is the RENDERED list: Slate itself and the user's hidden apps are filtered out,
     * one entry per launcher ResolveInfo (an app with two launcher activities yields two rows),
     * in package-manager iteration order. Six callers depend on both filters - see
     * shortcuts/ShortcutSourceAppPickerDialog.kt, which documents the dependency.
     *
     * ORDER IS LOAD-BEARING. Every downstream sort is stable and ties resolve to this order.
     * Never build [apps] through a set, map, dedupe or grouping operation.
     *
     * [installedKeys] is every launcher-visible key on the device with NO filters applied,
     * deduped. [FolderStore.reconcile] is its only consumer: a folder containing a hidden app
     * must not have its membership pruned, so unhiding restores the original layout. Applying
     * the hidden filter or the self filter to this set DELETES FOLDERS.
     */
    private data class Enumeration(
        val apps: List<AppInfo>,
        val installedKeys: Set<String>,
        /**
         * True only when the main-profile query returned something. False means a transient
         * package-manager failure, and [FolderStore.ReconcileScope] must then prune nothing
         * from the main profile rather than concluding every app was uninstalled.
         */
        val mainAuthoritative: Boolean,
        /**
         * Serials that returned at least one activity this pass. Positive evidence only: a
         * serial absent from this set is one we could not vouch for, NOT one that is empty.
         */
        val enumeratedSerials: Set<Long>
    )

    /** Work half of [enumerate], cached because search re-enumerates on every keystroke. */
    private data class WorkEnumeration(
        val apps: List<AppInfo>,
        val keys: Set<String>,
        val serials: Set<Long>
    )

    private var workCache: WorkEnumeration? = null
    private var workCacheAtElapsed: Long = 0L

    /**
     * Drops the cached work enumeration. Called from onResume and from each profile lifecycle
     * broadcast. A cross-user binder call per profile per keystroke is not something to inflict
     * on the search field, and search stutter is exactly what manual QA misses.
     */
    fun invalidateWorkCache() {
        workCache = null
    }

    private fun enumerateWork(): WorkEnumeration {
        if (!prefs.showWorkApps) return EMPTY_WORK

        workCache?.let {
            if (SystemClock.elapsedRealtime() - workCacheAtElapsed < WORK_CACHE_MS) return it
        }

        val launcherApps =
            context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return EMPTY_WORK

        val hidden = prefs.hiddenApps
        val apps = mutableListOf<AppInfo>()
        val keys = HashSet<String>()
        val serials = HashSet<Long>()

        WorkProfiles.profiles(context).forEach { profile ->
            val activities =
                runCatching { launcherApps.getActivityList(null, profile.handle) }.getOrNull()
            // G3. A serial earns authority only on positive evidence: the call returned AND
            // returned something. Zero results means "cannot vouch" - a stopped or locked
            // profile, direct boot, or a DPC hiding everything - never "all uninstalled".
            // Quiet mode is deliberately not in that list: paused apps still enumerate.
            if (activities.isNullOrEmpty()) return@forEach

            serials.add(profile.serial)
            activities.forEach { info ->
                val pkg = info.componentName.packageName
                val key = AppKey.of(pkg, profile.serial)
                // Unfiltered, like the main profile's set: reconcile must never see the
                // hidden filter or it strips hidden apps out of their folders.
                keys.add(key)
                if (key in hidden) return@forEach
                apps.add(
                    AppInfo(
                        name = prefs.getAppCustomName(key) ?: info.label.toString(),
                        packageName = pkg,
                        activityName = info.componentName.className,
                        profile = profile
                    )
                )
            }
        }

        return WorkEnumeration(apps, keys, serials).also {
            workCache = it
            workCacheAtElapsed = SystemClock.elapsedRealtime()
        }
    }

    /**
     * G4. A work key is only ever prunable if the system can no longer resolve its serial to a
     * user, i.e. the profile was genuinely removed.
     *
     * The candidate serials come from what is STORED - folder membership - never from what
     * enumerated. Sourcing them from enumeration would make a removed profile's keys immortal,
     * because a removed profile enumerates nothing and so would never be a candidate. The probe
     * is skipped entirely when nothing is stored, which is every device without a work profile.
     */
    private fun reconcileScope(enumeration: Enumeration): FolderStore.ReconcileScope {
        val storedSerials = FolderStore.keysInAnyFolder(prefs)
            .mapNotNullTo(HashSet()) { AppKey.serialOf(it) }
        val candidates = storedSerials - enumeration.enumeratedSerials
        val provablyGone = candidates.filterTo(HashSet()) {
            WorkProfiles.handleForSerial(context, it) == null
        }
        return FolderStore.ReconcileScope(
            installedKeys = enumeration.installedKeys,
            mainAuthoritative = enumeration.mainAuthoritative,
            enumeratedSerials = enumeration.enumeratedSerials,
            provablyGoneSerials = provablyGone
        )
    }

    /**
     * Work-profile apps only, from the cached enumeration. Exists solely so the grouping
     * trigger can be driven from onResume without re-querying; no render path uses it.
     */
    fun workAppsForGrouping(): List<AppInfo> = enumerateWork().apps

    private fun enumerate(): Enumeration {
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

        // Built BEFORE both filters, deduped independently of `apps`. See the KDoc.
        val installedKeys = resolveInfos.mapTo(HashSet()) {
            AppKey.of(it.activityInfo.packageName, null)
        }

        val hidden = prefs.hiddenApps
        val selfPackage = context.packageName
        val apps = resolveInfos
            .filter { it.activityInfo.packageName != selfPackage }
            .filter { AppKey.of(it.activityInfo.packageName, null) !in hidden }
            .map {
                val pkg = it.activityInfo.packageName
                val key = AppKey.of(pkg, null)
                AppInfo(
                    name = prefs.getAppCustomName(key) ?: it.loadLabel(pm).toString(),
                    packageName = pkg,
                    activityName = it.activityInfo.name,
                    profile = null
                )
            }

        val work = enumerateWork()

        // G2, the main-profile circuit breaker. getHomeItems runs on every resume AND every
        // notification change, so a single transient package-manager failure that reported
        // "nothing installed" would delete the entire folder library, pins included.
        val allKeys = installedKeys + work.keys
        return Enumeration(
            // Work apps append after main apps, preserving package-manager iteration order for
            // the main profile. Every downstream sort is stable, so ties still resolve to it.
            apps = apps + work.apps,
            installedKeys = allKeys,
            mainAuthoritative = allKeys.any { AppKey.serialOf(it) == null },
            enumeratedSerials = work.serials
        )
    }

    private companion object {
        val EMPTY_WORK = WorkEnumeration(emptyList(), emptySet(), emptySet())

        /** Backstop only; the real invalidation is onResume plus the profile broadcasts. */
        const val WORK_CACHE_MS = 30_000L
    }

    fun getAllApps(forceAlphabetical: Boolean = false): List<AppInfo> {
        val apps = enumerate().apps
        val pinned = prefs.pinnedApps
        val sortByUsage = !forceAlphabetical && prefs.sortByUsage
        val sorted = if (sortByUsage) {
            apps.sortedByDescending { prefs.getUsageCount(it.key) }
        } else {
            apps.sortedBy { it.name.lowercase() }
        }
        // Pinned apps float to the top, preserving sort order within each group
        return sorted.sortedByDescending { it.key in pinned }
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
        // ONE enumeration per call. Reconcile uses the FULL install set, not the
        // hidden-filtered set, so hidden apps retain their folder membership and reappear
        // inside the folder when unhidden.
        val enumeration = enumerate()
        val allApps = enumeration.apps
        FolderStore.reconcile(prefs, reconcileScope(enumeration))

        val pinnedShortcuts = PinnedShortcutStore.reconcileInstalled(context, prefs)

        if (folderId != null) {
            val folder = FolderStore.find(prefs, folderId)
            // Gracefully fall back if the folder vanished. Calls buildMainList rather than
            // re-entering getHomeItems(null), so reconcile and reconcileInstalled can never
            // run twice in one frame.
                ?: return buildMainList(enumeration, pinnedShortcuts)
            val folderApps = allApps.filter { it.key in folder.packages }
            val sorted = if (prefs.sortByUsage) {
                folderApps.sortedByDescending { prefs.getUsageCount(it.key) }
                    .applyMostUsedDirection()
            } else {
                folderApps.sortedBy { it.name.lowercase() }
            }
            return listOf(HomeItem.BackOut) + sorted.map { HomeItem.AppItem(it) }
        }

        return buildMainList(enumeration, pinnedShortcuts)
    }

    private fun buildMainList(
        enumeration: Enumeration,
        pinnedShortcuts: List<PinnedShortcut>
    ): List<HomeItem> {
        val allApps = enumeration.apps
        val pinned = prefs.pinnedApps
        val pinnedFolderIds = prefs.pinnedFolders

        val keysInFolders = FolderStore.keysInAnyFolder(prefs)
        val nonPinnedFlatApps = allApps.filter {
            it.key !in pinned && it.key !in keysInFolders
        }
        // A folder shows on the main list if it has at least one *visible* (not-hidden) app.
        // A folder containing only hidden apps stays in the data model but is omitted from
        // render - so unhiding a member restores the folder cleanly. Pinning doesn't change
        // that: a pinned folder with no visible members disappears too and comes back on
        // unhide, its id sitting untouched in the pin set meanwhile.
        val visibleKeys = allApps.mapTo(HashSet()) { it.key }
        val visibleFolders = FolderStore.all(prefs).filter { folder ->
            folder.packages.any { key -> key in visibleKeys }
        }
        val (pinnedFolderList, unpinnedFolderList) =
            visibleFolders.partition { it.id in pinnedFolderIds }

        // visibleCount is computed against the user's visible-app set so the "count"
        // folder-style marker matches what the user sees on expand (hidden / uninstalled
        // members aren't counted).
        fun folderItem(folder: Folder): HomeItem.FolderItem =
            HomeItem.FolderItem(folder, folder.packages.count { it in visibleKeys })

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
            allApps.filter { it.key in pinned }.map { HomeItem.AppItem(it) } +
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
                    is HomeItem.AppItem -> prefs.getUsageCount(item.info.key)
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
}
