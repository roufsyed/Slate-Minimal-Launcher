package com.slate.launcher

import android.content.Context
import android.os.SystemClock

/**
 * Groups a work profile's apps into a folder ONCE, then stops.
 *
 * This is a one-shot action, not an ongoing policy, and the distinction is the whole design.
 * A policy would have to re-evaluate on every render and therefore remember every disagreement:
 * which apps the user pulled out, whether they deleted the folder, whether they want it back.
 * A one shot runs when the conditions are right and is then over, so an app the user moves out
 * simply stays out because nothing ever looks again.
 *
 * Consequences, both deliberate:
 *  - A work app installed later appears in the main list like any other newly installed app,
 *    not in the folder. Every new app behaves the same way, with no exception to learn.
 *  - Deleting the folder is permanent. The serial stays marked, so nothing regroups it.
 *
 * [groupOnDemand] is the escape hatch for both.
 *
 * NOTHING here is reachable from the render path. It is driven by onResume and by the profile
 * lifecycle broadcasts, and it takes the enumeration as a parameter rather than querying, so it
 * can never add a key that reconcile strips in the same pass.
 */
object WorkGrouping {

    /**
     * Enrolment creates the profile and then pushes the managed app set over seconds to hours,
     * so the first pass with any apps in it is typically just the DPC and Play Store. Grouping
     * that set and marking the serial done would strand every real work app inline, forever.
     *
     * So the one shot is DEFERRED until the app count stops moving - never repeated, which is
     * what would reintroduce the drag-back problem. State is in-memory only: process death just
     * restarts the window, costing at most another minute.
     */
    private const val SETTLE_MS = 60_000L

    private data class Settle(val firstSeenElapsed: Long, val lastCount: Int)

    private val pending = mutableMapOf<Long, Settle>()

    /**
     * Called from onResume and from each profile lifecycle broadcast. [workApps] is the already
     * enumerated work half - this never queries the package manager or the profile list itself.
     */
    fun maybeGroupWorkAppsOnce(context: Context, prefs: PreferencesManager, workApps: List<AppInfo>) {
        if (!prefs.showWorkApps) return
        val profiles = WorkProfiles.profiles(context)
        if (profiles.isEmpty()) return

        val grouped = prefs.workGroupedSerials
        profiles.forEach { profile ->
            if (profile.serial.toString() in grouped) return@forEach
            groupSerialOnce(prefs, profile, workApps, grouped)
        }
    }

    /**
     * The Settings action. Files any work app that is not already in a folder, into the profile's
     * folder, creating it if the user deleted it. Marks every live serial, so the automatic path
     * stays disarmed afterwards.
     *
     * This can drag an app back that the user previously moved out - and that is correct here,
     * because they just asked for it. The automatic path never can.
     */
    fun groupOnDemand(context: Context, prefs: PreferencesManager, workApps: List<AppInfo>) {
        WorkProfiles.profiles(context).forEach { profile ->
            fill(prefs, profile, workApps, prefs.workGroupedSerials + profile.serial.toString())
        }
    }

    private fun groupSerialOnce(
        prefs: PreferencesManager,
        profile: WorkProfile,
        workApps: List<AppInfo>,
        grouped: Set<String>
    ) {
        val mine = workApps.count { it.profile?.serial == profile.serial }
        // G3'. A profile enumerating nothing is still provisioning, locked, or unreadable.
        // Marking it here would permanently ungroup a profile that was merely slow. This check
        // MUST stay ahead of every write.
        if (mine == 0) return

        val now = SystemClock.elapsedRealtime()
        val settle = pending[profile.serial]
        if (settle == null || settle.lastCount != mine) {
            // Still moving. Restart the clock and wait.
            pending[profile.serial] = Settle(now, mine)
            return
        }
        if (now - settle.firstSeenElapsed < SETTLE_MS) return

        fill(prefs, profile, workApps, grouped + profile.serial.toString())
        pending.remove(profile.serial)
    }

    /**
     * The single write. Pre-filters so the additive contract in
     * [FolderStore.createOrFillWorkFolder] holds: an app the user already filed somewhere is
     * left where it is, and a pinned app is never silently unpinned into a folder.
     */
    private fun fill(
        prefs: PreferencesManager,
        profile: WorkProfile,
        workApps: List<AppInfo>,
        groupedAfter: Set<String>
    ) {
        val alreadyFiled = FolderStore.keysInAnyFolder(prefs)
        val pinned = prefs.pinnedApps
        val keys = workApps
            .filter { it.profile?.serial == profile.serial }
            .map { it.key }
            .filter { it !in alreadyFiled && it !in pinned }

        FolderStore.createOrFillWorkFolder(
            prefs = prefs,
            serial = profile.serial,
            keys = keys,
            groupedSerials = groupedAfter,
            nameIfCreating = { nameFor(prefs, profile) }
        )
    }

    /**
     * First unused candidate, matched trimmed and case-insensitively, so an auto-created folder
     * never collides with one the user already named "Work". Evaluated only when a folder is
     * actually minted, and never consulted again - a rename sticks permanently.
     */
    private fun nameFor(prefs: PreferencesManager, profile: WorkProfile): String {
        val taken = FolderStore.all(prefs).mapTo(HashSet()) { it.name.trim().lowercase() }
        if (profile.label.trim().lowercase() !in taken) return profile.label
        var n = 2
        while ("${profile.label} $n".trim().lowercase() in taken) n++
        return "${profile.label} $n"
    }
}
