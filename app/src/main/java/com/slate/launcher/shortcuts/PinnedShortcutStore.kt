package com.slate.launcher.shortcuts

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.slate.launcher.PreferencesManager
import org.json.JSONArray

/**
 * Persistence and OS-sync for pinned external-app shortcuts. Mirrors
 * [com.slate.launcher.widgets.ContactShortcutStore] / [com.slate.launcher.FolderStore]'s
 * single-JSON-blob-pref shape: one [PreferencesManager.pinnedShortcutsJson] string, full
 * read-modify-write on every mutation.
 *
 * [unresolvedStreak] / [disabledShortcuts] are process-lifetime only, never persisted or backed
 * up - see [performHealthCheck]. A process restart is conservative (resets the streak), never
 * accelerates a drop.
 *
 * [syncPinnedShortcutsForPackage] is the ONLY function allowed to call
 * [LauncherApps.pinShortcuts] - it always re-reads the ledger fresh and unions every shortcut id
 * pinned to a package regardless of destination, so a picker scoped to one destination never
 * clobbers the OS-level pins the other destination depends on (`pinShortcuts` replaces the
 * entire pinned set for a package).
 */
object PinnedShortcutStore {

    private const val STREAK_DROP_THRESHOLD = 5
    private const val HEALTH_CHECK_THROTTLE_MS = 60_000L
    private const val TAG = "PinnedShortcutStore"

    private val unresolvedStreak = mutableMapOf<String, Int>()
    private val disabledShortcuts = mutableSetOf<String>()
    private var lastHealthCheckAtMs = 0L

    // ── Lookup helpers ─────────────────────────────────────────────

    fun launcherApps(context: Context): LauncherApps? =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

    fun hasShortcutHostPermissionSafe(launcherApps: LauncherApps?): Boolean =
        launcherApps != null && runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    private fun streakKey(sourcePackage: String, shortcutId: String) = "$sourcePackage:$shortcutId"

    /** Clears both stale-tracking collections for one shortcut - it's now confirmed resolved. */
    private fun markResolved(sourcePackage: String, shortcutId: String) {
        val key = streakKey(sourcePackage, shortcutId)
        unresolvedStreak.remove(key)
        disabledShortcuts.remove(key)
    }

    /** True once a shortcut's disabled/unresolved state should render dimmed. Cheap - no IPC. */
    fun isLikelyStale(shortcut: PinnedShortcut): Boolean {
        val key = streakKey(shortcut.sourcePackage, shortcut.shortcutId)
        return key in unresolvedStreak || key in disabledShortcuts
    }

    // ── Persistence ────────────────────────────────────────────────

    fun all(prefs: PreferencesManager): List<PinnedShortcut> = runCatching {
        val arr = JSONArray(prefs.pinnedShortcutsJson)
        (0 until arr.length()).mapNotNull { PinnedShortcut.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun find(prefs: PreferencesManager, id: String): PinnedShortcut? =
        all(prefs).firstOrNull { it.id == id }

    fun findByShortcut(prefs: PreferencesManager, sourcePackage: String, shortcutId: String): PinnedShortcut? {
        val list = all(prefs)
        return list.getOrNull(indexOfShortcut(list, sourcePackage, shortcutId))
    }

    fun forPackage(prefs: PreferencesManager, sourcePackage: String): List<PinnedShortcut> =
        all(prefs).filter { it.sourcePackage == sourcePackage }

    /**
     * Per-source-package count of pinned-shortcut records for one [destination]. Always
     * destination-scoped by design, never a "total across both destinations" reading - Dialog
     * 2's own switch-checked test is `destination in existing.destinations`, so any count that
     * doesn't apply the same filter can show "N enabled" on Dialog 1 for an app that opens to
     * zero checked switches on Dialog 2. [destination] is required, not nullable/defaulted -
     * there is deliberately no way to get the wrong (total) reading by omission.
     *
     * One [all] parse total per call, regardless of how many source packages end up in the
     * result. Call this once per dialog build/rebuild, never once per candidate app in a loop -
     * that would reintroduce the redundant-parse anti-pattern [forPackage] already has (it calls
     * [all] internally, so calling it per-candidate for N candidates re-parses the same JSON N
     * times).
     *
     * Deliberately does not consult [isLikelyStale] / [disabledShortcuts] - those are
     * process-only, OS-shortcut-validity concerns, unrelated to whether a record exists in this
     * destination's set.
     */
    fun pinnedCountsBySourcePackage(prefs: PreferencesManager, destination: ShortcutDestination): Map<String, Int> =
        all(prefs)
            .filter { destination in it.destinations }
            .groupingBy { it.sourcePackage }
            .eachCount()

    private fun indexOfShortcut(list: List<PinnedShortcut>, sourcePackage: String, shortcutId: String): Int =
        list.indexOfFirst { it.sourcePackage == sourcePackage && it.shortcutId == shortcutId }

    /**
     * Merge-by-compound-key: adds [destination] to the existing record's set, or creates one.
     * When [destination] is [ShortcutDestination.WIDGET_STRIP], also enrolls the shortcut in
     * [PreferencesManager.quickStripWidgets] - the strip only ever renders ids present in that
     * separate ordered "currently shown" list, so a record with WIDGET_STRIP in its destinations
     * but absent from that list would silently never appear.
     */
    fun add(
        prefs: PreferencesManager,
        launcherApps: LauncherApps?,
        sourcePackage: String,
        shortcutId: String,
        label: String,
        destination: ShortcutDestination,
        pinnedAtMs: Long
    ) {
        val list = all(prefs).toMutableList()
        val idx = indexOfShortcut(list, sourcePackage, shortcutId)
        val record: PinnedShortcut
        if (idx >= 0) {
            val existing = list[idx]
            record = existing.copy(pinnedLabel = label, destinations = existing.destinations + destination)
            list[idx] = record
        } else {
            record = PinnedShortcut(sourcePackage, shortcutId, label, setOf(destination), pinnedAtMs)
            list.add(record)
        }
        save(prefs, list)
        markResolved(sourcePackage, shortcutId)
        if (destination == ShortcutDestination.WIDGET_STRIP) addToQuickStrip(prefs, record.id)
        syncPinnedShortcutsForPackage(prefs, launcherApps, sourcePackage)
    }

    /**
     * Shrinks the destination set for (sourcePackage, shortcutId); drops the record if empty.
     * Mirrors [add]'s quickStripWidgets bookkeeping in reverse when [destination] is
     * [ShortcutDestination.WIDGET_STRIP].
     */
    fun remove(
        prefs: PreferencesManager,
        launcherApps: LauncherApps?,
        sourcePackage: String,
        shortcutId: String,
        destination: ShortcutDestination
    ) {
        val list = all(prefs).toMutableList()
        val idx = indexOfShortcut(list, sourcePackage, shortcutId)
        if (idx < 0) return
        val existing = list[idx]
        val newDestinations = existing.destinations - destination
        if (newDestinations.isEmpty()) list.removeAt(idx) else list[idx] = existing.copy(destinations = newDestinations)
        save(prefs, list)
        if (destination == ShortcutDestination.WIDGET_STRIP) removeFromQuickStrip(prefs, existing.id)
        syncPinnedShortcutsForPackage(prefs, launcherApps, sourcePackage)
    }

    /**
     * Drops every record for [sourcePackage] in one batched pass (one read, one write, one
     * quick-strip cleanup, one OS resync) - used when the source app itself is hidden, so N
     * pinned shortcuts don't cost N redundant read-modify-write-and-pinShortcuts cycles. Returns
     * the dropped records so the caller can report how many were removed.
     */
    fun removeForPackage(prefs: PreferencesManager, launcherApps: LauncherApps?, sourcePackage: String): List<PinnedShortcut> {
        val list = all(prefs)
        val (dropped, kept) = list.partition { it.sourcePackage == sourcePackage }
        if (dropped.isEmpty()) return dropped
        save(prefs, kept)
        removeIdsFromQuickStrip(prefs, dropped.map { it.id })
        syncPinnedShortcutsForPackage(prefs, launcherApps, sourcePackage)
        return dropped
    }

    /**
     * Tier 1 (cheap, synchronous, every rebuild): drops records whose source app is no longer
     * installed, using the same per-package existence check [QuickStripManager] independently
     * needs anyway - a pinned shortcut's validity depends on its source package existing at all,
     * not on whether that package has a launcher-visible activity. Returns the reconciled
     * (post-drop) list so callers that already need it don't have to re-parse the ledger.
     */
    fun reconcileInstalled(context: Context, prefs: PreferencesManager): List<PinnedShortcut> {
        val list = all(prefs)
        val pm = context.packageManager
        val kept = list.filter { shortcut -> runCatching { pm.getApplicationInfo(shortcut.sourcePackage, 0) }.isSuccess }
        if (kept.size != list.size) {
            save(prefs, kept)
            val keptIds = kept.mapTo(HashSet()) { it.id }
            removeIdsFromQuickStrip(prefs, list.map { it.id }.filterNot { it in keptIds })
        }
        return kept
    }

    private fun addToQuickStrip(prefs: PreferencesManager, id: String) {
        val current = prefs.quickStripWidgets
        if (id !in current) prefs.quickStripWidgets = current + id
    }

    private fun removeFromQuickStrip(prefs: PreferencesManager, id: String) =
        removeIdsFromQuickStrip(prefs, listOf(id))

    private fun removeIdsFromQuickStrip(prefs: PreferencesManager, ids: List<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        val current = prefs.quickStripWidgets
        val filtered = current.filterNot { it in idSet }
        if (filtered.size != current.size) prefs.quickStripWidgets = filtered
    }

    /**
     * Re-issues [syncPinnedShortcutsForPackage] for every distinct source package in the ledger.
     * Safe to call any time - a no-op if the OS already holds the pins, a repair if it doesn't
     * (e.g. right after a JSON restore, or when Slate regains default-launcher status).
     */
    fun resyncAllWithOs(prefs: PreferencesManager, launcherApps: LauncherApps?) {
        if (!hasShortcutHostPermissionSafe(launcherApps)) return
        all(prefs).map { it.sourcePackage }.distinct().forEach { pkg ->
            syncPinnedShortcutsForPackage(prefs, launcherApps, pkg)
        }
    }

    fun syncPinnedShortcutsForPackage(prefs: PreferencesManager, launcherApps: LauncherApps?, sourcePackage: String) {
        if (!hasShortcutHostPermissionSafe(launcherApps)) return
        val ids = forPackage(prefs, sourcePackage).map { it.shortcutId }
        runCatching {
            launcherApps!!.pinShortcuts(sourcePackage, ids, Process.myUserHandle())
        }.onFailure { Log.w(TAG, "pinShortcuts failed for $sourcePackage", it) }
    }

    private fun save(prefs: PreferencesManager, list: List<PinnedShortcut>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.pinnedShortcutsJson = arr.toString()
    }

    // ── Querying shortcuts from a source app ──────────────────────

    /**
     * One package's shortcuts via FLAG_MATCH_MANIFEST|DYNAMIC|PINNED. Always scoped to a single
     * package - there is no unscoped/cross-package call anywhere in this feature, since that
     * behaviour is undocumented and couldn't be verified against a real device. `PINNED` is
     * included so already-pinned candidates can be detected for checkbox pre-check, not to
     * change what's displayed.
     */
    fun queryShortcuts(launcherApps: LauncherApps, sourcePackage: String): List<ShortcutInfo> {
        if (!hasShortcutHostPermissionSafe(launcherApps)) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
            .setPackage(sourcePackage)
        return runCatching {
            launcherApps.getShortcuts(query, Process.myUserHandle()) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun startShortcut(launcherApps: LauncherApps?, shortcut: PinnedShortcut): Boolean {
        if (!hasShortcutHostPermissionSafe(launcherApps)) return false
        return runCatching {
            launcherApps!!.startShortcut(shortcut.sourcePackage, shortcut.shortcutId, null, null, Process.myUserHandle())
            true
        }.getOrElse { false }
    }

    // ── Health check (Tier 2) ──────────────────────────────────────

    /** Throttled entry point - call from app-foreground. Runs on a background thread; posts
     * [onComplete] back to the main thread only if anything may have changed. */
    fun performHealthCheckIfDue(context: Context, prefs: PreferencesManager, onComplete: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastHealthCheckAtMs < HEALTH_CHECK_THROTTLE_MS) return
        lastHealthCheckAtMs = now
        val launcherApps = launcherApps(context) ?: return
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val changed = performHealthCheck(prefs, launcherApps)
            if (changed) mainHandler.post(onComplete)
        }.start()
    }

    /**
     * Re-resolves every pinned shortcut's live state. Never called from a render path (see
     * AppRepository.getHomeItems / WidgetCatalog.byId) - only from [performHealthCheckIfDue], the
     * manual per-row [refreshOne], or [resyncAllWithOs]. Drops a record after
     * [STREAK_DROP_THRESHOLD] consecutive confirmed-permission passes with no resolution -
     * passes where permission is false don't count, so losing default-launcher status for a
     * while never silently deletes anything.
     */
    fun performHealthCheck(prefs: PreferencesManager, launcherApps: LauncherApps?): Boolean {
        if (!hasShortcutHostPermissionSafe(launcherApps)) return false
        val bySource = all(prefs).groupBy { it.sourcePackage }
        val updated = mutableListOf<PinnedShortcut>()
        val packagesToResync = mutableSetOf<String>()
        val droppedIds = mutableListOf<String>()
        var mutated = false

        bySource.forEach { (pkg, shortcuts) ->
            val live = queryShortcuts(launcherApps!!, pkg).associateBy { it.id }
            shortcuts.forEach { pinned ->
                val key = streakKey(pinned.sourcePackage, pinned.shortcutId)
                val liveShortcut = live[pinned.shortcutId]
                when {
                    liveShortcut != null && liveShortcut.isEnabled -> {
                        markResolved(pinned.sourcePackage, pinned.shortcutId)
                        val newLabel = (liveShortcut.longLabel ?: liveShortcut.shortLabel)?.toString()
                        if (newLabel != null && newLabel != pinned.pinnedLabel) {
                            updated.add(pinned.copy(pinnedLabel = newLabel)); mutated = true
                        } else {
                            updated.add(pinned)
                        }
                    }
                    liveShortcut != null -> {
                        // Resolvable but disabled - a confirmed state, not streak-based.
                        unresolvedStreak.remove(key)
                        if (key !in disabledShortcuts) { disabledShortcuts.add(key); mutated = true }
                        updated.add(pinned)
                    }
                    else -> {
                        val streak = (unresolvedStreak[key] ?: 0) + 1
                        unresolvedStreak[key] = streak
                        if (streak >= STREAK_DROP_THRESHOLD) {
                            mutated = true
                            packagesToResync += pkg
                            droppedIds += pinned.id
                            unresolvedStreak.remove(key)
                            disabledShortcuts.remove(key)
                            // Dropped - intentionally not added to `updated`.
                        } else {
                            updated.add(pinned)
                        }
                    }
                }
            }
        }

        if (mutated) save(prefs, updated)
        removeIdsFromQuickStrip(prefs, droppedIds)
        // Resync AFTER saving the pruned list, so the sync call's fresh read of the ledger no
        // longer contains the id(s) that were just dropped.
        packagesToResync.forEach { syncPinnedShortcutsForPackage(prefs, launcherApps, it) }
        return mutated
    }

    /** Unthrottled single-shortcut refresh for the long-press "Refresh" action. */
    fun refreshOne(prefs: PreferencesManager, launcherApps: LauncherApps?, shortcut: PinnedShortcut) {
        if (!hasShortcutHostPermissionSafe(launcherApps)) return
        val key = streakKey(shortcut.sourcePackage, shortcut.shortcutId)
        val live = queryShortcuts(launcherApps!!, shortcut.sourcePackage).firstOrNull { it.id == shortcut.shortcutId }
        when {
            live != null && live.isEnabled -> {
                markResolved(shortcut.sourcePackage, shortcut.shortcutId)
                val newLabel = (live.longLabel ?: live.shortLabel)?.toString()
                if (newLabel != null && newLabel != shortcut.pinnedLabel) {
                    val list = all(prefs).map { if (it.id == shortcut.id) it.copy(pinnedLabel = newLabel) else it }
                    save(prefs, list)
                }
            }
            live != null -> {
                unresolvedStreak.remove(key)
                disabledShortcuts.add(key)
            }
            else -> {
                disabledShortcuts.remove(key)
                unresolvedStreak[key] = (unresolvedStreak[key] ?: 0) + 1
            }
        }
    }
}
