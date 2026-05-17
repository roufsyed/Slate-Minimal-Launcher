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
        private const val KEY_SYNC_TO_LOCKSCREEN = "sync_to_lockscreen"
        private const val KEY_PINNED_APPS = "pinned_apps"
        private const val KEY_FOLLOW_SYSTEM_THEME = "follow_system_theme"
        private const val KEY_AWAITING_ACCESSIBILITY = "awaiting_accessibility_permission"
        private const val KEY_AWAITING_NOTIFICATION = "awaiting_notification_permission"
        private const val KEY_BATTERY_BANNER_DISMISSED = "battery_banner_dismissed_permanently"
        private const val KEY_HOMESCREEN_VIEW = "homescreen_view"
        private const val KEY_ALPHA_FAST_SCROLL = "alpha_fast_scroll"
        private const val KEY_HIDDEN_APPS_SECURITY = "hidden_apps_security_enabled"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_ITERATIONS = "pin_iterations"
        private const val KEY_PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until_epoch_ms"
        private const val KEY_PIN_LOCKOUT_UNTIL_ELAPSED = "pin_lockout_until_elapsed_ms"
        private const val KEY_QUICK_STRIP_ENABLED = "quick_strip_enabled"
        private const val KEY_QUICK_STRIP_WIDGETS = "quick_strip_widgets"
        private const val KEY_QUICK_STRIP_POSITION = "quick_strip_position"
        private const val KEY_QUICK_STRIP_DIVIDER_ENABLED = "quick_strip_divider_enabled"
        private const val KEY_CONTACT_SHORTCUTS = "contact_shortcuts"
        private const val KEY_FOLDERS = "folders_v1"
        private const val KEY_GUIDED_TOUR_STEP = "guided_tour_step_index"
        private const val KEY_GUIDED_TOUR_VERSION_SEEN = "guided_tour_version_seen"
        private const val KEY_FOLDER_STYLE = "folder_style"

        // How folder labels appear on the home screen. Default `chevron` preserves the
        // out-of-the-box behaviour; any unknown stored value also falls back to chevron at
        // render time so renaming or removing a style is safe.
        const val FOLDER_STYLE_CHEVRON = "chevron"     // "Work ›"
        const val FOLDER_STYLE_SLASH = "slash"         // "Work/"
        const val FOLDER_STYLE_BULLET = "bullet"       // "• Work"
        const val FOLDER_STYLE_BRACKETS = "brackets"   // "[Work]"
        const val FOLDER_STYLE_COUNT = "count"         // "Work (5)"
        const val FOLDER_STYLE_PLAIN = "plain"         // "Work"
        // Comma-separated ordered list of widget ids — small payload, simple format.
        const val DEFAULT_QUICK_STRIP_WIDGETS = "clock,battery"

        const val VIEW_FLOW = "flow"
        const val VIEW_LIST = "list"

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

    var syncToLockscreen: Boolean
        get() = prefs.getBoolean(KEY_SYNC_TO_LOCKSCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_TO_LOCKSCREEN, value).apply()

    var pinnedApps: Set<String>
        get() = prefs.getStringSet(KEY_PINNED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_PINNED_APPS, value).apply()

    fun pinApp(packageName: String) { pinnedApps = pinnedApps + packageName }
    fun unpinApp(packageName: String) { pinnedApps = pinnedApps - packageName }
    fun isPinned(packageName: String): Boolean = packageName in pinnedApps

    var followSystemTheme: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_SYSTEM_THEME, false)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_SYSTEM_THEME, value).apply()

    var awaitingAccessibilityPermission: Boolean
        get() = prefs.getBoolean(KEY_AWAITING_ACCESSIBILITY, false)
        set(value) = prefs.edit().putBoolean(KEY_AWAITING_ACCESSIBILITY, value).apply()

    var awaitingNotificationPermission: Boolean
        get() = prefs.getBoolean(KEY_AWAITING_NOTIFICATION, false)
        set(value) = prefs.edit().putBoolean(KEY_AWAITING_NOTIFICATION, value).apply()

    var batteryBannerDismissedPermanently: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_BANNER_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_BANNER_DISMISSED, value).apply()

    /** "flow" (default, word-cloud) or "list" (minimal one-per-line). */
    var homescreenView: String
        get() = prefs.getString(KEY_HOMESCREEN_VIEW, VIEW_FLOW) ?: VIEW_FLOW
        set(value) = prefs.edit().putString(KEY_HOMESCREEN_VIEW, value).apply()

    var alphabeticalFastScroll: Boolean
        get() = prefs.getBoolean(KEY_ALPHA_FAST_SCROLL, false)
        set(value) = prefs.edit().putBoolean(KEY_ALPHA_FAST_SCROLL, value).apply()

    // ── Hidden apps security ───────────────────────────────────────

    var hiddenAppsSecurityEnabled: Boolean
        get() = prefs.getBoolean(KEY_HIDDEN_APPS_SECURITY, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDDEN_APPS_SECURITY, value).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    /** Base64-encoded PBKDF2 hash of the user's PIN. */
    var pinHash: String?
        get() = prefs.getString(KEY_PIN_HASH, null)
        set(value) = prefs.edit().run {
            if (value == null) remove(KEY_PIN_HASH) else putString(KEY_PIN_HASH, value)
        }.apply()

    /** Base64-encoded per-user random salt. */
    var pinSalt: String?
        get() = prefs.getString(KEY_PIN_SALT, null)
        set(value) = prefs.edit().run {
            if (value == null) remove(KEY_PIN_SALT) else putString(KEY_PIN_SALT, value)
        }.apply()

    var pinIterations: Int
        get() = prefs.getInt(KEY_PIN_ITERATIONS, 0)
        set(value) = prefs.edit().putInt(KEY_PIN_ITERATIONS, value).apply()

    var pinFailedAttempts: Int
        get() = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, value).apply()

    var pinLockoutUntilEpochMs: Long
        get() = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_PIN_LOCKOUT_UNTIL, value).apply()

    /** Lockout deadline expressed in SystemClock.elapsedRealtime (monotonic, defeats clock rollback). */
    var pinLockoutUntilElapsedMs: Long
        get() = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL_ELAPSED, 0L)
        set(value) = prefs.edit().putLong(KEY_PIN_LOCKOUT_UNTIL_ELAPSED, value).apply()

    // ── Quick toggles strip ──────────────────────────────────────

    var quickStripEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUICK_STRIP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_QUICK_STRIP_ENABLED, value).apply()

    /** Ordered list of widget ids the user has enabled. Empty list = strip hidden. */
    var quickStripWidgets: List<String>
        get() = (prefs.getString(KEY_QUICK_STRIP_WIDGETS, DEFAULT_QUICK_STRIP_WIDGETS) ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_QUICK_STRIP_WIDGETS, value.joinToString(",")).apply()

    /** "top" or "bottom" — where the quick toggles strip sits on the home screen. */
    var quickStripPosition: String
        get() = prefs.getString(KEY_QUICK_STRIP_POSITION, "bottom") ?: "bottom"
        set(value) = prefs.edit().putString(KEY_QUICK_STRIP_POSITION, value).apply()

    /**
     * Opt-in cosmetic hairline along the strip's inner edge — below the strip when it's at the
     * top, above the strip when it's at the bottom. Has no effect when the strip itself is
     * hidden (master off, or no configured widget is available on the device).
     */
    var quickStripDividerEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUICK_STRIP_DIVIDER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_QUICK_STRIP_DIVIDER_ENABLED, value).apply()

    /** Raw JSON for the contact-shortcut library. Parsed by ContactShortcutStore. */
    var contactShortcutsJson: String
        get() = prefs.getString(KEY_CONTACT_SHORTCUTS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_CONTACT_SHORTCUTS, value).apply()

    /** Raw JSON for user-created folders. Parsed by FolderStore. */
    var foldersJson: String
        get() = prefs.getString(KEY_FOLDERS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_FOLDERS, value).apply()

    // ── Guided tour ──────────────────────────────────────────────
    // `guidedTourStepIndex` is -1 once the user has completed or skipped the tour, 0+ while
    // mid-tour. Persisted so a process-death mid-tour resumes at the same step.
    var guidedTourStepIndex: Int
        get() = prefs.getInt(KEY_GUIDED_TOUR_STEP, 0)
        set(value) = prefs.edit().putInt(KEY_GUIDED_TOUR_STEP, value).apply()

    // The last CURRENT_TOUR_VERSION the user actually saw. Bumping the in-code constant past
    // this re-triggers the tour on next resume — used to surface meaningful content updates.
    var guidedTourSeenVersion: Int
        get() = prefs.getInt(KEY_GUIDED_TOUR_VERSION_SEEN, 0)
        set(value) = prefs.edit().putInt(KEY_GUIDED_TOUR_VERSION_SEEN, value).apply()

    /** One of the `FOLDER_STYLE_*` constants. Default is chevron — matches current behaviour. */
    var folderStyle: String
        get() = prefs.getString(KEY_FOLDER_STYLE, FOLDER_STYLE_CHEVRON) ?: FOLDER_STYLE_CHEVRON
        set(value) = prefs.edit().putString(KEY_FOLDER_STYLE, value).apply()

    // ── Per-app custom names ──────────────────────────────────────

    fun getAppCustomName(packageName: String): String? =
        prefs.getString("app_name_$packageName", null)

    fun setAppCustomName(packageName: String, name: String) =
        prefs.edit().putString("app_name_$packageName", name).apply()

    fun clearAppCustomName(packageName: String) =
        prefs.edit().remove("app_name_$packageName").apply()

    fun getAllAppCustomNames(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith("app_name_") }
            .associate { it.key.removePrefix("app_name_") to (it.value as? String ?: "") }

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
