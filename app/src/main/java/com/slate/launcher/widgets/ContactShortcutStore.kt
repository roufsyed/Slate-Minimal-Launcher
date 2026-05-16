package com.slate.launcher.widgets

import com.slate.launcher.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Library of per-contact call/SMS shortcuts. Each shortcut is rendered as its own
 * [QuickWidget] instance with id `"call:<lookupUri>"` or `"sms:<lookupUri>"`. The display name
 * and phone number are cached so the shortcut still works if the user later revokes contacts
 * access or the contact is renamed.
 *
 * Persisted as a JSON array under [PreferencesManager.contactShortcutsJson]. The format is
 * intentionally trivial — no schema migration needed.
 */
data class ContactShortcut(
    val type: Type,
    val lookupUri: String,
    val displayName: String,
    val number: String
) {
    enum class Type(val prefix: String) {
        CALL("call"), SMS("sms");

        companion object {
            fun fromPrefix(p: String): Type? = entries.firstOrNull { it.prefix == p }
        }
    }

    /** Stable widget id used in `quickStripWidgets` and as the catalog lookup key. */
    val id: String get() = "${type.prefix}:$lookupUri"

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.prefix)
        put("lookupUri", lookupUri)
        put("displayName", displayName)
        put("number", number)
    }

    companion object {
        fun fromJson(obj: JSONObject): ContactShortcut? {
            val type = Type.fromPrefix(obj.optString("type")) ?: return null
            val lookupUri = obj.optString("lookupUri").takeIf { it.isNotEmpty() } ?: return null
            val displayName = obj.optString("displayName").takeIf { it.isNotEmpty() } ?: return null
            val number = obj.optString("number").takeIf { it.isNotEmpty() } ?: return null
            return ContactShortcut(type, lookupUri, displayName, number)
        }
    }
}

object ContactShortcutStore {

    fun all(prefs: PreferencesManager): List<ContactShortcut> {
        return runCatching {
            val arr = JSONArray(prefs.contactShortcutsJson)
            (0 until arr.length()).mapNotNull { ContactShortcut.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun find(prefs: PreferencesManager, id: String): ContactShortcut? =
        all(prefs).firstOrNull { it.id == id }

    /** Adds or replaces by id (so re-pinning the same contact updates cached name/number). */
    fun add(prefs: PreferencesManager, shortcut: ContactShortcut) {
        val list = all(prefs).filterNot { it.id == shortcut.id } + shortcut
        save(prefs, list)
    }

    fun remove(prefs: PreferencesManager, id: String) {
        val list = all(prefs).filterNot { it.id == id }
        save(prefs, list)
    }

    private fun save(prefs: PreferencesManager, list: List<ContactShortcut>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.contactShortcutsJson = arr.toString()
    }
}
