package com.slate.launcher

import org.json.JSONArray
import org.json.JSONObject

class BackupManager(private val prefs: PreferencesManager) {

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", 1)

        // Display
        root.put("minFontSize", prefs.minFontSize)
        root.put("maxFontSize", prefs.maxFontSize)
        root.put("lineSpacing", prefs.lineSpacing)
        root.put("wordSpacing", prefs.wordSpacing)

        // Typography
        root.put("fontFamily", prefs.fontFamily)
        root.put("fontWeight", prefs.fontWeight)

        // Colors
        root.put("backgroundColor", prefs.backgroundColor)
        root.put("appTextColor", prefs.appTextColor)

        // Gestures
        root.put("doubleTapToLock", prefs.doubleTapToLock)
        val gesturesObj = JSONObject()
        prefs.getAllGestureActions().forEach { (k, v) -> gesturesObj.put(k, v) }
        root.put("gestureActions", gesturesObj)

        // Typography (extended)
        root.put("textAlignment", prefs.textAlignment)

        // General
        root.put("sortByUsage", prefs.sortByUsage)
        root.put("lockOrientation", prefs.lockOrientation)
        root.put("hideStatusBar", prefs.hideStatusBar)
        root.put("notificationColorEnabled", prefs.notificationColorEnabled)
        root.put("notificationHighlightColor", prefs.notificationHighlightColor)

        // Lockscreen
        root.put("syncToLockscreen", prefs.syncToLockscreen)

        // Search
        root.put("searchEnabled", prefs.searchEnabled)
        root.put("showSearchBarOnHome", prefs.showSearchBarOnHome)
        root.put("searchBarPosition", prefs.searchBarPosition)

        // Hidden apps
        val hiddenArr = JSONArray()
        prefs.hiddenApps.forEach { hiddenArr.put(it) }
        root.put("hiddenApps", hiddenArr)

        // Pinned apps
        val pinnedArr = JSONArray()
        prefs.pinnedApps.forEach { pinnedArr.put(it) }
        root.put("pinnedApps", pinnedArr)

        // Auto-theme
        root.put("followSystemTheme", prefs.followSystemTheme)

        // Per-app colors
        val colorsObj = JSONObject()
        prefs.getAllAppColors().forEach { (k, v) -> colorsObj.put(k, v) }
        root.put("appColors", colorsObj)

        // Per-app custom names
        val namesObj = JSONObject()
        prefs.getAllAppCustomNames().forEach { (k, v) -> namesObj.put(k, v) }
        root.put("appCustomNames", namesObj)

        // Hidden apps security — the PIN hash is a verifier, not a secret. Including it lets a
        // restored backup keep working without forcing the user to re-set the PIN.
        root.put("hiddenAppsSecurityEnabled", prefs.hiddenAppsSecurityEnabled)
        root.put("biometricEnabled", prefs.biometricEnabled)
        prefs.pinHash?.let { root.put("pinHash", it) }
        prefs.pinSalt?.let { root.put("pinSalt", it) }
        if (prefs.pinIterations > 0) root.put("pinIterations", prefs.pinIterations)

        // Quick toggles strip
        root.put("quickStripEnabled", prefs.quickStripEnabled)
        val quickStripWidgetsArr = JSONArray()
        prefs.quickStripWidgets.forEach { quickStripWidgetsArr.put(it) }
        root.put("quickStripWidgets", quickStripWidgetsArr)
        // Embed the contact-shortcut library as a parsed JSON array (not a string) so the
        // backup remains human-readable and round-trips through json libraries cleanly.
        root.put("contactShortcuts", JSONArray(prefs.contactShortcutsJson))

        return root.toString(2)
    }

    fun fromJson(json: String) {
        val root = JSONObject(json)
        if (root.optInt("version", 0) < 1) throw IllegalArgumentException("Unsupported backup version")

        prefs.minFontSize  = root.optInt("minFontSize",  PreferencesManager.DEFAULT_MIN_FONT_SIZE)
        prefs.maxFontSize  = root.optInt("maxFontSize",  PreferencesManager.DEFAULT_MAX_FONT_SIZE)
        prefs.lineSpacing  = root.optInt("lineSpacing",  PreferencesManager.DEFAULT_LINE_SPACING)
        prefs.wordSpacing  = root.optInt("wordSpacing",  PreferencesManager.DEFAULT_WORD_SPACING)
        prefs.fontFamily   = root.optString("fontFamily",   PreferencesManager.DEFAULT_FONT_FAMILY)
        prefs.fontWeight   = root.optInt("fontWeight",   PreferencesManager.DEFAULT_FONT_WEIGHT)
        prefs.backgroundColor = root.optString("backgroundColor", PreferencesManager.DEFAULT_BACKGROUND_COLOR)
        prefs.appTextColor    = root.optString("appTextColor",    PreferencesManager.DEFAULT_TEXT_COLOR)
        prefs.doubleTapToLock   = root.optBoolean("doubleTapToLock", false)
        prefs.textAlignment     = root.optString("textAlignment", "center")
        prefs.sortByUsage       = root.optBoolean("sortByUsage", false)
        prefs.lockOrientation   = root.optBoolean("lockOrientation", true)
        prefs.hideStatusBar     = root.optBoolean("hideStatusBar", false)
        prefs.notificationColorEnabled   = root.optBoolean("notificationColorEnabled", false)
        prefs.notificationHighlightColor = root.optString("notificationHighlightColor", "#FFFFFF")
        prefs.syncToLockscreen  = root.optBoolean("syncToLockscreen", false)
        prefs.searchEnabled     = root.optBoolean("searchEnabled", true)
        prefs.showSearchBarOnHome = root.optBoolean("showSearchBarOnHome", false)
        prefs.searchBarPosition = root.optString("searchBarPosition", "top")

        // Hidden apps
        root.optJSONArray("hiddenApps")?.let { arr ->
            prefs.hiddenApps = (0 until arr.length()).map { arr.getString(it) }.toSet()
        }

        // Pinned apps
        root.optJSONArray("pinnedApps")?.let { arr ->
            prefs.pinnedApps = (0 until arr.length()).map { arr.getString(it) }.toSet()
        }

        // Auto-theme
        prefs.followSystemTheme = root.optBoolean("followSystemTheme", false)

        // Gesture actions
        root.optJSONObject("gestureActions")?.let { obj ->
            obj.keys().forEach { key -> prefs.setGestureActionRaw(key, obj.getString(key)) }
        }

        // Per-app colors
        root.optJSONObject("appColors")?.let { obj ->
            obj.keys().forEach { pkg -> prefs.setAppTextColor(pkg, obj.getString(pkg)) }
        }

        // Per-app custom names
        root.optJSONObject("appCustomNames")?.let { obj ->
            obj.keys().forEach { pkg -> prefs.setAppCustomName(pkg, obj.getString(pkg)) }
        }

        // Hidden apps security — restore PIN verifier so backup is usable on a new device.
        // Reset failed-attempt counters since they are device-local state, not user data.
        prefs.pinHash       = root.optString("pinHash").takeIf { it.isNotEmpty() }
        prefs.pinSalt       = root.optString("pinSalt").takeIf { it.isNotEmpty() }
        prefs.pinIterations = root.optInt("pinIterations", 0)
        val pinFullyPresent = prefs.pinHash != null && prefs.pinSalt != null && prefs.pinIterations > 0
        // If the backup claims security is on but the PIN bundle is missing/partial, drop the
        // claim — otherwise AuthGate would short-circuit to success and silently bypass the gate.
        prefs.hiddenAppsSecurityEnabled = pinFullyPresent &&
                root.optBoolean("hiddenAppsSecurityEnabled", false)
        prefs.biometricEnabled = pinFullyPresent &&
                root.optBoolean("biometricEnabled", false)
        prefs.pinFailedAttempts        = 0
        prefs.pinLockoutUntilEpochMs   = 0L
        prefs.pinLockoutUntilElapsedMs = 0L

        // Quick toggles strip
        prefs.quickStripEnabled = root.optBoolean("quickStripEnabled", false)
        root.optJSONArray("quickStripWidgets")?.let { arr ->
            prefs.quickStripWidgets = (0 until arr.length()).map { arr.getString(it) }
        }
        root.optJSONArray("contactShortcuts")?.let { arr ->
            prefs.contactShortcutsJson = arr.toString()
        }
    }
}
