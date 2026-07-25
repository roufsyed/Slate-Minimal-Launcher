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
        // dismissOnSelect = false: this dialog stays open underneath Dialog 2 rather than
        // dismissing on tap - see the class doc for why.
        SlateListDialog(
            context = activity,
            title = titlePrefix,
            items = candidates.map { it.name },
            bgColor = prefs.backgroundColor,
            dismissOnSelect = false
        ) { index, _ ->
            ShortcutPickerDialog.show(
                activity = activity,
                prefs = prefs,
                launcherApps = launcherApps,
                destination = destination,
                sourcePackage = candidates[index].packageName,
                sourceAppName = candidates[index].name
            )
        }.apply { fillScreen() }.show()
    }
}
