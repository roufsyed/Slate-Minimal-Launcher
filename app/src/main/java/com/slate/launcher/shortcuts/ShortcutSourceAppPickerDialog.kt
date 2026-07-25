package com.slate.launcher.shortcuts

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import com.slate.launcher.AppInfo
import com.slate.launcher.AppRepository
import com.slate.launcher.PreferencesManager
import com.slate.launcher.SlateListDialog

/**
 * Dialog 1 of the shortcut-pinning flow: pick which installed app to browse shortcuts from.
 * Built on [SlateListDialog] with `dismissOnSelect = false` - unlike every other SlateListDialog
 * call site, this one stays alive underneath Dialog 2 rather than dismissing on tap, so the
 * system back gesture/button naturally reveals it again when Dialog 2 closes, with nothing to
 * recreate or cache.
 *
 * Population runs one [LauncherApps.getShortcuts] query per installed app on a background
 * thread, bounded to this dialog's open time only - never on a render path. There is no
 * unscoped/cross-package query anywhere in this feature: that behaviour is undocumented and
 * couldn't be verified against a real device, so this always does the safe, guaranteed-correct
 * per-package loop instead of assuming an unverified shortcut.
 */
object ShortcutSourceAppPickerDialog {

    /**
     * SlateListDialog defaults to a wrap-content, 80%-width card (right for its dozen other call
     * sites - Font, Position, Hidden Apps, etc.), but that makes this flow's box visibly jump
     * size between the loading placeholder, a short app list, a long one, and the empty state.
     * Fill the screen instead, so the size is stable across every state in this flow. Applied
     * here per call site rather than in SlateListDialog itself, which stays wrap-content/80% for
     * everyone else.
     */
    private fun SlateListDialog.fillScreen() {
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun show(
        activity: Activity,
        prefs: PreferencesManager,
        launcherApps: LauncherApps,
        destination: ShortcutDestination
    ) {
        val titlePrefix = "${destination.displayLabel()} - choose an app"
        val mainHandler = Handler(Looper.getMainLooper())

        val loading = SlateListDialog(
            context = activity,
            title = titlePrefix,
            items = listOf("Loading apps…"),
            bgColor = prefs.backgroundColor
        ) { _, _ -> }
        loading.fillScreen()
        loading.show()

        Thread {
            // getAllApps() already excludes Slate itself and the user's hidden apps - the same
            // set the home list itself draws from. A hidden app is deliberately not offered as a
            // shortcut source: pinning from an app the user has chosen not to see would defeat
            // the point of hiding it.
            val candidates = AppRepository(activity, prefs)
                .getAllApps(forceAlphabetical = true)
                .filter { PinnedShortcutStore.queryShortcuts(launcherApps, it.packageName).isNotEmpty() }

            mainHandler.post {
                if (!loading.isShowing) return@post
                loading.dismiss()
                showList(activity, prefs, launcherApps, destination, titlePrefix, candidates)
            }
        }.start()
    }

    private fun showList(
        activity: Activity,
        prefs: PreferencesManager,
        launcherApps: LauncherApps,
        destination: ShortcutDestination,
        titlePrefix: String,
        candidates: List<AppInfo>
    ) {
        if (candidates.isEmpty()) {
            SlateListDialog(
                context = activity,
                title = titlePrefix,
                items = listOf("No apps with shortcuts found"),
                bgColor = prefs.backgroundColor
            ) { _, _ -> }.apply { fillScreen() }.show()
            return
        }

        var dialog: SlateListDialog? = null

        // Recomputes order + hints from current PinnedShortcutStore state and applies them to
        // the dialog in place - constructing it on the first call, updating its rows in place
        // (SlateListDialog.updateContent) on every later call, which happens from onDismissed
        // below every time Dialog 2 closes. Updating in place rather than dismissing and
        // recreating the window is deliberate: this dialog is never actually hidden (dismissOnSelect
        // = false keeps it alive underneath Dialog 2), so tearing the window down and rebuilding
        // it would flash the Settings screen behind it for a frame - a real, visible glitch, not
        // just a theoretical one. Never re-runs the LauncherApps scan and never recomputes
        // `candidates` - only re-reads PinnedShortcutStore (SharedPreferences + JSON, no IPC).
        fun refresh() {
            val pinnedCounts = PinnedShortcutStore.pinnedCountsBySourcePackage(prefs, destination)
            val ordered = candidates.sortedByDescending { app -> (pinnedCounts[app.packageName] ?: 0) > 0 }
            val hints = ordered.map { app ->
                val count = pinnedCounts[app.packageName] ?: 0
                if (count > 0) "$count enabled" else ""
            }
            // `index` below is a position in `ordered`, not `candidates` - both the displayed
            // label and this callback read `ordered`, since it may be resorted relative to
            // `candidates`' original order.
            val onRowSelected: (Int, String) -> Unit = { index, _ ->
                ShortcutPickerDialog.show(
                    activity = activity,
                    prefs = prefs,
                    launcherApps = launcherApps,
                    destination = destination,
                    sourcePackage = ordered[index].packageName,
                    sourceAppName = ordered[index].name,
                    onDismissed = { refresh() }
                )
            }
            val existing = dialog
            if (existing != null) {
                existing.updateContent(items = ordered.map { it.name }, secondaryItems = hints, onItemSelected = onRowSelected)
            } else {
                // dismissOnSelect = false: this dialog stays open underneath Dialog 2 rather
                // than dismissing on tap - see the class doc for why.
                val newDialog = SlateListDialog(
                    context = activity,
                    title = titlePrefix,
                    items = ordered.map { it.name },
                    bgColor = prefs.backgroundColor,
                    secondaryItems = hints,
                    dismissOnSelect = false,
                    onItemSelected = onRowSelected
                ).apply { fillScreen() }
                dialog = newDialog
                newDialog.show()
            }
        }

        refresh()
    }
}
