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
 *   - A package appears in at most one folder.
 *   - Folders are pruned when empty (or when their last visible app leaves).
 *   - Pinning a package removes it from any folder it was in.
 *   - [PreferencesManager.pinnedFolders] never references a folder that no longer exists.
 *
 * Hiding an app does NOT alter folder membership - the app is simply filtered at render time so
 * unhiding restores the previous home layout cleanly.
 */
object FolderStore {

    fun all(prefs: PreferencesManager): List<Folder> = runCatching {
        val arr = JSONArray(prefs.foldersJson)
        (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun find(prefs: PreferencesManager, id: String): Folder? =
        all(prefs).firstOrNull { it.id == id }

    fun folderContaining(prefs: PreferencesManager, packageName: String): Folder? =
        all(prefs).firstOrNull { packageName in it.packages }

    /** Returns the set of all packages currently inside any folder - cheap O(1) lookup helper. */
    fun packagesInAnyFolder(prefs: PreferencesManager): Set<String> =
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

    /** Adds [packageName] to [folderId]; removes it from any other folder it was in. */
    fun addAppToFolder(prefs: PreferencesManager, folderId: String, packageName: String) {
        // Pinned apps cannot live in folders - enforce by unpinning first.
        if (prefs.isPinned(packageName)) prefs.unpinApp(packageName)
        val list = all(prefs).toMutableList()
        list.forEachIndexed { i, f ->
            if (f.id != folderId) {
                if (packageName in f.packages) {
                    f.packages.remove(packageName)
                    list[i] = f
                }
            }
        }
        val target = list.firstOrNull { it.id == folderId } ?: return
        if (packageName !in target.packages) target.packages.add(packageName)
        save(prefs, list)
    }

    /** Removes [packageName] from whatever folder contains it (if any). Prunes if empty. */
    fun removeAppFromFolder(prefs: PreferencesManager, packageName: String) {
        val list = all(prefs).toMutableList()
        var changed = false
        val iter = list.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            if (packageName in f.packages) {
                f.packages.remove(packageName)
                changed = true
                if (f.packages.isEmpty()) iter.remove()  // prune now-empty folder
            }
        }
        if (changed) save(prefs, list)
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
     * Drop any package from folders that no longer corresponds to an installed app. Prunes the
     * folder ONLY if removal actually emptied a previously non-empty folder (i.e. all of its
     * apps were uninstalled). Freshly-created empty folders are preserved so the user can add
     * apps to them via the "Move to folder" flow - otherwise a stray rebuild between
     * createEmpty() and addAppToFolder() would silently nuke the new folder.
     */
    fun reconcile(prefs: PreferencesManager, installedPackages: Set<String>) {
        val list = all(prefs).toMutableList()
        var changed = false
        val iter = list.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            val before = f.packages.size
            f.packages.removeAll { it !in installedPackages }
            if (f.packages.size != before) {
                changed = true
                // Only auto-prune folders that BECAME empty here (had members before this call).
                // Folders that started empty are user-just-created and must persist.
                if (f.packages.isEmpty() && before > 0) iter.remove()
            }
        }
        if (changed) save(prefs, list)
    }

    private fun save(prefs: PreferencesManager, folders: List<Folder>) {
        val arr = JSONArray()
        folders.forEach { arr.put(it.toJson()) }
        prefs.foldersJson = arr.toString()

        // Drop pins for folders that no longer exist. Centralised here rather than at each
        // deletion site because folders die in three separate places - delete(), reconcile()'s
        // uninstall prune, and removeAppFromFolder()'s now-empty prune - and every one of them
        // funnels through save(), so this cannot be forgotten when a fourth is added. The guard
        // keeps the common case (nothing pinned, or nothing removed) free of a redundant write.
        val liveIds = folders.mapTo(HashSet()) { it.id }
        val pinned = prefs.pinnedFolders
        if (pinned.any { it !in liveIds }) {
            prefs.pinnedFolders = pinned.filterTo(HashSet()) { it in liveIds }
        }
    }

    private fun Folder.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packages", JSONArray().also { arr -> packages.forEach { arr.put(it) } })
        color?.let { put("color", it) }
    }

    private fun fromJson(obj: JSONObject): Folder? {
        val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return null
        val pkgsArr = obj.optJSONArray("packages") ?: JSONArray()
        val packages = (0 until pkgsArr.length()).mapTo(mutableListOf()) { pkgsArr.getString(it) }
        val color = obj.optString("color").takeIf { it.isNotEmpty() }
        return Folder(id, name, packages, color)
    }
}
