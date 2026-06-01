package com.slate.launcher

/**
 * A user-named collection of apps. Identity is the stable [id] (UUID), so renames don't break
 * references in prefs. [packages] preserves user-add order; the home renderer applies the global
 * sort rule (alpha or by-usage) at render time - internal order is just the persistence default.
 *
 * Per-folder colour is optional and falls back to the global app text colour when null.
 */
data class Folder(
    val id: String,
    var name: String,
    val packages: MutableList<String>,
    var color: String? = null
)
