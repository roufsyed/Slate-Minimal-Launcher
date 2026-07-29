package com.slate.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistence and invariant enforcement for [Folder] objects. The whole library is stored as a
 * single JSON array in [PreferencesManager.foldersJson] - atomic writes, simple schema. The
 * format is intentionally trivial; no migration story needed for additive future fields.
 *
 * Invariants enforced by every mutating call:
 *   - An app (by AppKey) appears in at most one folder.
 *   - Folders are pruned when empty (or when their last visible app leaves).
 *   - Pinning an app removes it from any folder it was in.
 *   - [PreferencesManager.pinnedFolders] never references a folder that no longer exists.
 *
 * Hiding an app does NOT alter folder membership - the app is simply filtered at render time so
 * unhiding restores the previous home layout cleanly.
 *
 * [reconcile]'s input must be the FULL launcher-visible key set. Handing it a hidden-filtered
 * or self-filtered set strips every affected app from its folder and deletes any folder that
 * empties, silently, on the next render. The function's entire danger is in its input, which
 * is why the input is a [ReconcileScope] carrying its own authority rather than a bare set.
 */
object FolderStore {

    fun all(prefs: PreferencesManager): List<Folder> = runCatching {
        val arr = JSONArray(prefs.foldersJson)
        (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun find(prefs: PreferencesManager, id: String): Folder? =
        all(prefs).firstOrNull { it.id == id }

    fun folderContaining(prefs: PreferencesManager, key: String): Folder? =
        all(prefs).firstOrNull { key in it.packages }

    /** Every AppKey currently inside any folder - cheap O(1) lookup helper. */
    fun keysInAnyFolder(prefs: PreferencesManager): Set<String> =
        all(prefs).flatMap { it.packages }.toSet()

    /** Create a new empty folder with the given (trimmed, non-empty) name. */
    fun createEmpty(prefs: PreferencesManager, name: String): Folder {
        val folder = Folder(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            packages = mutableListOf()
        )
        save(prefs, all(prefs) + folder)
        return folder
    }

    /** Adds [key] to [folderId]; removes it from any other folder it was in. */
    fun addAppToFolder(prefs: PreferencesManager, folderId: String, key: String) {
        // Pinned apps cannot live in folders - enforce by unpinning first.
        if (prefs.isPinned(key)) prefs.unpinApp(key)
        val list = all(prefs).toMutableList()
        list.forEachIndexed { i, f ->
            if (f.id != folderId) {
                if (key in f.packages) {
                    f.packages.remove(key)
                    list[i] = f
                }
            }
        }
        val target = list.firstOrNull { it.id == folderId } ?: return
        if (key !in target.packages) target.packages.add(key)
        save(prefs, list)
    }

    /**
     * Removes [key] from whatever folder contains it (if any). Prunes if empty.
     * Returns the folder that was pruned, or null. Behaviour is otherwise unchanged; the
     * return value only lets a caller notice it just destroyed a work folder.
     */
    fun removeAppFromFolder(prefs: PreferencesManager, key: String): Folder? {
        val list = all(prefs).toMutableList()
        var changed = false
        var pruned: Folder? = null
        val iter = list.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            if (key in f.packages) {
                f.packages.remove(key)
                changed = true
                if (f.packages.isEmpty()) {
                    pruned = f
                    iter.remove()  // prune now-empty folder
                }
            }
        }
        if (changed) save(prefs, list)
        return pruned
    }

    fun rename(prefs: PreferencesManager, folderId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val list = all(prefs)
        val target = list.firstOrNull { it.id == folderId } ?: return
        target.name = trimmed
        save(prefs, list)
    }

    fun setColor(prefs: PreferencesManager, folderId: String, hex: String?) {
        val list = all(prefs)
        val target = list.firstOrNull { it.id == folderId } ?: return
        target.color = hex
        save(prefs, list)
    }

    fun delete(prefs: PreferencesManager, folderId: String) {
        save(prefs, all(prefs).filterNot { it.id == folderId })
    }

    /**
     * Adds [keys] to the folder that receives [serial]'s apps, minting one if none exists, and
     * records [serial] as grouped - in a single write.
     *
     * ADDITIVE ONLY. It never renames, recolours, unpins, reorders, or removes a member, so
     * every change the user has made to this folder survives untouched. The caller pre-filters
     * [keys]: anything already in a folder or pinned must not be passed, because this
     * deliberately does not strip apps from elsewhere the way addAppToFolder does.
     *
     * [nameIfCreating] is a lambda so the candidate-name walk runs only when a folder is
     * actually minted, and so that with several pending serials each one sees the previous
     * one's save().
     */
    fun createOrFillWorkFolder(
        prefs: PreferencesManager,
        serial: Long,
        keys: List<String>,
        groupedSerials: Set<String>,
        nameIfCreating: () -> String
    ) {
        val list = all(prefs).toMutableList()
        val existing = list.firstOrNull { it.profileSerial == serial }

        // Nothing to place and nothing to fill: record the attempt so the one shot does not
        // re-arm, but do not mint an empty folder that would sit as a permanent ghost.
        if (existing == null && keys.isEmpty()) {
            save(prefs, list, groupedSerials)
            return
        }

        val target = existing ?: Folder(
            id = UUID.randomUUID().toString(),
            name = nameIfCreating(),
            packages = mutableListOf(),
            profileSerial = serial
        ).also { list.add(it) }

        keys.forEach { if (it !in target.packages) target.packages.add(it) }
        save(prefs, list, groupedSerials)
    }

    /**
     * Drop any package from folders that no longer corresponds to an installed app. Prunes the
     * folder ONLY if removal actually emptied a previously non-empty folder (i.e. all of its
     * apps were uninstalled). Freshly-created empty folders are preserved so the user can add
     * apps to them via the "Move to folder" flow - otherwise a stray rebuild between
     * createEmpty() and addAppToFolder() would silently nuke the new folder.
     */
    /**
     * What reconcile is permitted to prune. Pruning requires POSITIVE evidence that an app is
     * gone; the absence of evidence must never be read as absence of the app.
     *
     * [mainAuthoritative] false means the package-manager query failed, so no main-profile key
     * is touched this pass. [enumeratedSerials] holds only serials that actually returned
     * activities, so a paused, locked, stopped or switched-off profile keeps every key it owns.
     * [provablyGoneSerials] is the one way a work key ever becomes prunable: the system no
     * longer resolves that serial to a user at all.
     */
    class ReconcileScope(
        val installedKeys: Set<String>,
        private val mainAuthoritative: Boolean,
        private val enumeratedSerials: Set<Long>,
        private val provablyGoneSerials: Set<Long>
    ) {
        fun mayPrune(key: String): Boolean {
            val serial = AppKey.serialOf(key) ?: return mainAuthoritative
            return serial in enumeratedSerials || serial in provablyGoneSerials
        }
    }

    fun reconcile(prefs: PreferencesManager, scope: ReconcileScope) {
        val installedKeys = scope.installedKeys
        val list = all(prefs).toMutableList()
        var changed = false
        val iter = list.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            val before = f.packages.size
            f.packages.removeAll { scope.mayPrune(it) && it !in installedKeys }
            if (f.packages.size != before) {
                changed = true
                // Only auto-prune folders that BECAME empty here (had members before this call).
                // Folders that started empty are user-just-created and must persist.
                if (f.packages.isEmpty() && before > 0) iter.remove()
            }
        }
        if (changed) save(prefs, list)
    }

    private fun save(
        prefs: PreferencesManager,
        folders: List<Folder>,
        groupedSerials: Set<String>? = null
    ) {
        val arr = JSONArray()
        folders.forEach { arr.put(it.toJson()) }

        // Drop pins for folders that no longer exist. Centralised here rather than at each
        // deletion site because folders die in three separate places - delete(), reconcile()'s
        // uninstall prune, and removeAppFromFolder()'s now-empty prune - and every one of them
        // funnels through save(), so this cannot be forgotten when a fourth is added. The guard
        // keeps the common case (nothing pinned, or nothing removed) free of a redundant write.
        // Both keys now land in ONE editor and ONE apply(), so a process kill can no longer
        // leave folders_v1 written and pinned_folders referencing a folder that just died.
        val liveIds = folders.mapTo(HashSet()) { it.id }
        val pinned = prefs.pinnedFolders
        val prunedPins =
            if (pinned.any { it !in liveIds }) pinned.filterTo(HashSet()) { it in liveIds }
            else null

        prefs.commitFolderState(
            foldersJson = arr.toString(),
            pinnedFolders = prunedPins,
            workGroupedSerials = groupedSerials
        )
    }

    private fun Folder.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packages", JSONArray().also { arr -> packages.forEach { arr.put(it) } })
        color?.let { put("color", it) }
        profileSerial?.let { put("profileSerial", it) }
    }

    private fun fromJson(obj: JSONObject): Folder? {
        val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return null
        val pkgsArr = obj.optJSONArray("packages") ?: JSONArray()
        val packages = (0 until pkgsArr.length()).mapTo(mutableListOf()) { pkgsArr.getString(it) }
        val color = obj.optString("color").takeIf { it.isNotEmpty() }
        // has() is mandatory: optLong returns 0 for an absent key, and 0 is a valid user
        // serial, so a bare optLong would make every pre-existing folder claim serial 0.
        val profileSerial = if (obj.has("profileSerial")) obj.optLong("profileSerial") else null
        return Folder(id, name, packages, color, profileSerial)
    }
}
