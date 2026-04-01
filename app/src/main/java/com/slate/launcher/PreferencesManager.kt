package com.slate.launcher

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "slate_prefs"
        private const val KEY_HIDDEN_APPS = "hidden_apps"
        private const val KEY_MIN_FONT_SIZE = "min_font_size"
        private const val KEY_MAX_FONT_SIZE = "max_font_size"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_BACKGROUND_COLOR = "background_color"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_DOUBLE_TAP_LOCK = "double_tap_lock"
        private const val KEY_SEARCH_ENABLED = "search_enabled"
        private const val KEY_SEARCH_BAR_ON_HOME = "search_bar_on_home"
        private const val KEY_SEARCH_BAR_POSITION = "search_bar_position"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_FONT_WEIGHT = "font_weight"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_WORD_SPACING = "word_spacing"
        private const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
        private const val KEY_SORT_BY_USAGE = "sort_by_usage"
        private const val KEY_TEXT_ALIGNMENT = "text_alignment"
        private const val KEY_LOCK_ORIENTATION = "lock_orientation"
        private const val KEY_NOTIF_COLOR_ENABLED = "notif_color_enabled"
        private const val KEY_NOTIF_HIGHLIGHT_COLOR = "notif_highlight_color"

        const val DEFAULT_FONT_FAMILY = "gf:tex_gyre_adventor_bold"
        const val DEFAULT_FONT_WEIGHT = 400
        const val DEFAULT_LINE_SPACING = 5
        const val DEFAULT_WORD_SPACING = 10

        const val DEFAULT_MIN_FONT_SIZE = 14
        const val DEFAULT_MAX_FONT_SIZE = 42
        const val DEFAULT_BACKGROUND_COLOR = "#000000"
        const val DEFAULT_TEXT_COLOR = "#808080"

    }

    var hiddenApps: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_APPS, value).apply()

    var minFontSize: Int
        get() = prefs.getInt(KEY_MIN_FONT_SIZE, DEFAULT_MIN_FONT_SIZE)
        set(value) = prefs.edit().putInt(KEY_MIN_FONT_SIZE, value).apply()

    var maxFontSize: Int
        get() = prefs.getInt(KEY_MAX_FONT_SIZE, DEFAULT_MAX_FONT_SIZE)
        set(value) = prefs.edit().putInt(KEY_MAX_FONT_SIZE, value).apply()

    var backgroundColor: String
        get() = prefs.getString(KEY_BACKGROUND_COLOR, DEFAULT_BACKGROUND_COLOR) ?: DEFAULT_BACKGROUND_COLOR
        set(value) = prefs.edit().putString(KEY_BACKGROUND_COLOR, value).apply()

    var appTextColor: String
        get() = prefs.getString(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR) ?: DEFAULT_TEXT_COLOR
        set(value) = prefs.edit().putString(KEY_TEXT_COLOR, value).apply()

    var doubleTapToLock: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_TAP_LOCK, value).apply()

    var searchEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SEARCH_ENABLED, value).apply()

    var showSearchBarOnHome: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_BAR_ON_HOME, false)
        set(value) = prefs.edit().putBoolean(KEY_SEARCH_BAR_ON_HOME, value).apply()

    /** "top" or "bottom" */
    var searchBarPosition: String
        get() = prefs.getString(KEY_SEARCH_BAR_POSITION, "top") ?: "top"
        set(value) = prefs.edit().putString(KEY_SEARCH_BAR_POSITION, value).apply()

    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY) ?: DEFAULT_FONT_FAMILY
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    var fontWeight: Int
        get() = prefs.getInt(KEY_FONT_WEIGHT, DEFAULT_FONT_WEIGHT)
        set(value) = prefs.edit().putInt(KEY_FONT_WEIGHT, value).apply()

    var lineSpacing: Int
        get() = prefs.getInt(KEY_LINE_SPACING, DEFAULT_LINE_SPACING)
        set(value) = prefs.edit().putInt(KEY_LINE_SPACING, value).apply()

    var wordSpacing: Int
        get() = prefs.getInt(KEY_WORD_SPACING, DEFAULT_WORD_SPACING)
        set(value) = prefs.edit().putInt(KEY_WORD_SPACING, value).apply()

    var hideStatusBar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_STATUS_BAR, value).apply()

    var sortByUsage: Boolean
        get() = prefs.getBoolean(KEY_SORT_BY_USAGE, false)
        set(value) = prefs.edit().putBoolean(KEY_SORT_BY_USAGE, value).apply()

    /** "left", "center", or "right" */
    var textAlignment: String
        get() = prefs.getString(KEY_TEXT_ALIGNMENT, "center") ?: "center"
        set(value) = prefs.edit().putString(KEY_TEXT_ALIGNMENT, value).apply()

    var lockOrientation: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ORIENTATION, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ORIENTATION, value).apply()

    var notificationColorEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_COLOR_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_COLOR_ENABLED, value).apply()

    var notificationHighlightColor: String
        get() = prefs.getString(KEY_NOTIF_HIGHLIGHT_COLOR, "#FFFFFF") ?: "#FFFFFF"
        set(value) = prefs.edit().putString(KEY_NOTIF_HIGHLIGHT_COLOR, value).apply()

    // ── Per-app text color ─────────────────────────────────────────

    fun getAppTextColor(packageName: String): String? =
        prefs.getString("app_color_$packageName", null)

    fun setAppTextColor(packageName: String, hex: String) =
        prefs.edit().putString("app_color_$packageName", hex).apply()

    fun clearAppTextColor(packageName: String) =
        prefs.edit().remove("app_color_$packageName").apply()

    fun getAllAppColors(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith("app_color_") }
            .associate { it.key.removePrefix("app_color_") to (it.value as? String ?: "") }

    fun getAllGestureActions(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith("gesture_") }
            .associate { it.key to (it.value as? String ?: "") }

    fun setGestureActionRaw(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    // ── Gesture actions ───────────────────────────────────────────

    fun getGestureAction(fingers: Int, dir: Direction): GestureAction {
        val saved = prefs.getString(gestureKey(fingers, dir), null)
            ?: return GestureAction.defaultFor(fingers, dir)
        return GestureAction.deserialize(saved)
    }

    fun setGestureAction(fingers: Int, dir: Direction, action: GestureAction) {
        prefs.edit().putString(gestureKey(fingers, dir), action.serialize()).apply()
    }

    private fun gestureKey(fingers: Int, dir: Direction) =
        "gesture_${fingers}_${dir.name.lowercase()}"

    // ── Usage tracking ────────────────────────────────────────────

    fun incrementUsage(packageName: String) {
        val key = "usage_$packageName"
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun getUsageCount(packageName: String): Int =
        prefs.getInt("usage_$packageName", 0)

    // ── App visibility ────────────────────────────────────────────

    fun hideApp(packageName: String) {
        hiddenApps = hiddenApps + packageName
    }

    fun unhideApp(packageName: String) {
        hiddenApps = hiddenApps - packageName
    }
}
