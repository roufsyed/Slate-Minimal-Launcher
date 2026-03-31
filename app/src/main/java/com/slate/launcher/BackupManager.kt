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
        root.put("nightMode", prefs.nightMode)

        // Search
        root.put("searchEnabled", prefs.searchEnabled)
        root.put("showSearchBarOnHome", prefs.showSearchBarOnHome)
        root.put("searchBarPosition", prefs.searchBarPosition)

        // Hidden apps
        val hiddenArr = JSONArray()
        prefs.hiddenApps.forEach { hiddenArr.put(it) }
        root.put("hiddenApps", hiddenArr)

        // Per-app colors
        val colorsObj = JSONObject()
        prefs.getAllAppColors().forEach { (k, v) -> colorsObj.put(k, v) }
        root.put("appColors", colorsObj)

        return root.toString(2)
    }

    fun fromJson(json: String) {
        val root = JSONObject(json)
        if (root.optInt("version", 0) < 1) return

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
        prefs.nightMode         = root.optInt("nightMode", -1)
        prefs.searchEnabled     = root.optBoolean("searchEnabled", true)
        prefs.showSearchBarOnHome = root.optBoolean("showSearchBarOnHome", false)
        prefs.searchBarPosition = root.optString("searchBarPosition", "top")

        // Hidden apps
        root.optJSONArray("hiddenApps")?.let { arr ->
            prefs.hiddenApps = (0 until arr.length()).map { arr.getString(it) }.toSet()
        }

        // Gesture actions
        root.optJSONObject("gestureActions")?.let { obj ->
            obj.keys().forEach { key -> prefs.setGestureActionRaw(key, obj.getString(key)) }
        }

        // Per-app colors
        root.optJSONObject("appColors")?.let { obj ->
            obj.keys().forEach { pkg -> prefs.setAppTextColor(pkg, obj.getString(pkg)) }
        }
    }
}
