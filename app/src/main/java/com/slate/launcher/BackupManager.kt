package com.slate.launcher

import org.json.JSONArray
import org.json.JSONObject

class BackupManager(private val prefs: PreferencesManager) {

    /**
     * The private bundle inside a backup file - hidden-apps list, security flag, biometric
     * flag, and the PIN's PBKDF2 verifier. Surfaced as a separate type so the import flow can
     * (a) detect its presence cheaply via [BackupContents.privateBundle] and (b) verify the
     * backup's PIN in-memory via [PinManager.verifyAgainst] BEFORE committing any of this to
     * device prefs. A backup is considered to have a private bundle only when ALL of pinHash,
     * pinSalt, pinIterations are present and the hidden-apps key resolves cleanly - degenerate
     * cases (e.g., a hand-edited file with pinHash but no pinSalt) parse as a null bundle and
     * the hidden-apps key is silently ignored rather than written without a gate.
     */
    data class PrivateBundle(
        val hiddenApps: Set<String>,
        val hiddenAppsSecurityEnabled: Boolean,
        val biometricEnabled: Boolean,
        val pinHash: String,
        val pinSalt: String,
        val pinIterations: Int,
    )

    /**
     * Parsed backup payload. The non-private half is stored as the raw [JSONObject] so
     * [applyNonPrivate] can replay the existing pref-by-pref restore logic without rebuilding
     * a typed projection of every field. The private half is extracted into a structured
     * [PrivateBundle] for the import-time PIN dialog to consume.
     */
    data class BackupContents(
        val nonPrivate: JSONObject,
        val privateBundle: PrivateBundle?
    )

    /**
     * G9. Work-profile keys must never reach a backup file, in either direction.
     *
     * A serial is meaningful only on the device that issued it, so a restored "pkg@11" either
     * matches nothing or - worse - attaches to an unrelated profile that happens to have been
     * given the same serial. Stripping on IMPORT as well as export is what stops a hand-edited
     * file injecting one.
     */
    private fun isWorkKey(key: String): Boolean = AppKey.serialOf(key) != null

    /**
     * Folders, with work keys and the profile marker removed. A folder left with no members is
     * dropped entirely: an exported empty folder would be permanently immune to reconcile's
     * `before > 0` guard and would sit in the picker as an invisible ghost forever.
     */
    private fun strippedFolders(json: String): JSONArray {
        val out = JSONArray()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val folder = arr.getJSONObject(i)
                val pkgs = folder.optJSONArray("packages") ?: JSONArray()
                val kept = JSONArray()
                for (j in 0 until pkgs.length()) {
                    val key = pkgs.getString(j)
                    if (!isWorkKey(key)) kept.put(key)
                }
                if (kept.length() == 0) continue
                folder.put("packages", kept)
                folder.remove("profileSerial")
                out.put(folder)
            }
        }
        return out
    }

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
        prefs.getAllGestureActions()
            .filterNot { (_, v) -> v.startsWith("app:") && isWorkKey(v.removePrefix("app:")) }
            .forEach { (k, v) -> gesturesObj.put(k, v) }
        root.put("gestureActions", gesturesObj)

        // Typography (extended)
        root.put("textAlignment", prefs.textAlignment)

        // General
        root.put("sortByUsage", prefs.sortByUsage)
        root.put("mostUsedPosition", prefs.mostUsedPosition)
        root.put("lockOrientation", prefs.lockOrientation)
        root.put("hideStatusBar", prefs.hideStatusBar)
        root.put("notificationColorEnabled", prefs.notificationColorEnabled)
        root.put("notificationHighlightColor", prefs.notificationHighlightColor)
        root.put("ignoreSilentNotifications", prefs.ignoreSilentNotifications)

        // Lockscreen
        root.put("syncToLockscreen", prefs.syncToLockscreen)

        // Search
        root.put("searchEnabled", prefs.searchEnabled)
        root.put("showSearchBarOnHome", prefs.showSearchBarOnHome)
        root.put("searchBarPosition", prefs.searchBarPosition)

        // Pinned apps
        val pinnedArr = JSONArray()
        prefs.pinnedApps.filterNot { isWorkKey(it) }.forEach { pinnedArr.put(it) }
        root.put("pinnedApps", pinnedArr)

        // Pinned folders, by folder id. Ids are serialised verbatim by FolderStore and are
        // never regenerated on import, so these stay matched to the "folders" array below.
        val pinnedFoldersArr = JSONArray()
        prefs.pinnedFolders.forEach { pinnedFoldersArr.put(it) }
        root.put("pinnedFolders", pinnedFoldersArr)

        // Auto-theme
        root.put("followSystemTheme", prefs.followSystemTheme)

        // Per-app colors
        val colorsObj = JSONObject()
        prefs.getAllAppColors().filterNot { isWorkKey(it.key) }
            .forEach { (k, v) -> colorsObj.put(k, v) }
        root.put("appColors", colorsObj)

        // Per-app custom names
        val namesObj = JSONObject()
        prefs.getAllAppCustomNames().filterNot { isWorkKey(it.key) }
            .forEach { (k, v) -> namesObj.put(k, v) }
        root.put("appCustomNames", namesObj)

        // Private bundle - hidden apps + PIN + biometric. Opt-in via Settings → Backup. When
        // OFF (the default), none of these keys appear in the JSON, so the backup file cannot
        // carry the user's hidden-apps list or the PIN's PBKDF2 verifier off-device. The
        // toggle itself (`includePrivateInBackup`) is intentionally NOT written into the
        // backup - it's a per-device privacy preference.
        if (prefs.includePrivateInBackup) {
            val hiddenArr = JSONArray()
            prefs.hiddenApps.filterNot { isWorkKey(it) }.forEach { hiddenArr.put(it) }
            root.put("hiddenApps", hiddenArr)
            root.put("hiddenAppsSecurityEnabled", prefs.hiddenAppsSecurityEnabled)
            root.put("biometricEnabled", prefs.biometricEnabled)
            prefs.pinHash?.let { root.put("pinHash", it) }
            prefs.pinSalt?.let { root.put("pinSalt", it) }
            if (prefs.pinIterations > 0) root.put("pinIterations", prefs.pinIterations)
        }

        // Quick toggles strip
        root.put("quickStripEnabled", prefs.quickStripEnabled)
        root.put("quickStripPosition", prefs.quickStripPosition)
        root.put("quickStripDividerEnabled", prefs.quickStripDividerEnabled)
        root.put("widgetTextSize", prefs.widgetTextSize)
        root.put("widgetLineGap", prefs.widgetLineGap)
        root.put("widgetWordGap", prefs.widgetWordGap)
        root.put("widgetFontFamily", prefs.widgetFontFamily)
        root.put("widgetFontWeight", prefs.widgetFontWeight)
        root.put("widgetTextAlignment", prefs.widgetTextAlignment)
        root.put("directCallEnabled", prefs.directCallEnabled)
        root.put("directCallTrigger", prefs.directCallTrigger)
        val quickStripWidgetsArr = JSONArray()
        prefs.quickStripWidgets.forEach { quickStripWidgetsArr.put(it) }
        root.put("quickStripWidgets", quickStripWidgetsArr)
        // Embed the contact-shortcut library as a parsed JSON array (not a string) so the
        // backup remains human-readable and round-trips through json libraries cleanly.
        root.put("contactShortcuts", JSONArray(prefs.contactShortcutsJson))

        // User-created folders. Same human-readable strategy as contactShortcuts.
        root.put("folders", strippedFolders(prefs.foldersJson))

        // Pinned external-app shortcuts. Same human-readable strategy as contactShortcuts/folders.
        // Only the durable fields ever appear here - PinnedShortcut has no device-local-only
        // fields serialized in its own toJson(), so no special-casing is needed to keep any out.
        root.put("pinnedShortcuts", JSONArray(prefs.pinnedShortcutsJson))

        // Guided tour: persist the version the user finished - restoring on a new device should
        // NOT re-trigger the tour. Don't persist `stepIndex`; mid-tour state is device-local.
        root.put("guidedTourSeenVersion", prefs.guidedTourSeenVersion)

        // Folder display style (chevron/slash/bullet/brackets/count/plain).
        root.put("folderStyle", prefs.folderStyle)
        root.put("workMarkerStyle", prefs.workMarkerStyle)
        root.put("suppressWorkMarkerInFolder", prefs.suppressWorkMarkerInFolder)

        return root.toString(2)
    }

    /**
     * Pure parse - no writes. Returns the JSON for non-private fields plus an optional
     * [PrivateBundle]. Caller is responsible for orchestrating the dialogs that gate the
     * privateBundle's eventual application via [applyPrivate].
     */
    fun parse(json: String): BackupContents {
        val root = JSONObject(json)
        if (root.optInt("version", 0) < 1) {
            throw IllegalArgumentException("Unsupported backup version")
        }

        // Detect a structurally-complete private bundle. Anything less than all-three-PIN-
        // fields-present-and-non-empty causes us to drop the private half entirely. This
        // explicitly avoids the pre-refactor pitfall where a backup with hiddenApps but no
        // PIN would silently disable the device's hiddenAppsSecurityEnabled flag.
        val pinHash = root.optString("pinHash").takeIf { it.isNotEmpty() }
        val pinSalt = root.optString("pinSalt").takeIf { it.isNotEmpty() }
        val pinIters = root.optInt("pinIterations", 0)
        val hiddenArr = root.optJSONArray("hiddenApps")
        val privateBundle: PrivateBundle? = if (
            pinHash != null && pinSalt != null && pinIters > 0 && hiddenArr != null
        ) {
            PrivateBundle(
                hiddenApps = (0 until hiddenArr.length()).map { hiddenArr.getString(it) }.toSet(),
                hiddenAppsSecurityEnabled = root.optBoolean("hiddenAppsSecurityEnabled", false),
                biometricEnabled = root.optBoolean("biometricEnabled", false),
                pinHash = pinHash,
                pinSalt = pinSalt,
                pinIterations = pinIters,
            )
        } else null

        return BackupContents(nonPrivate = root, privateBundle = privateBundle)
    }

    /**
     * Apply every non-private pref from a parsed [BackupContents] to disk. Theme, gestures,
     * folders, widgets, pinned apps, custom names/colors, contact shortcuts, etc. The private
     * bundle is NOT touched here - see [applyPrivate].
     */
    fun applyNonPrivate(contents: BackupContents) {
        val root = contents.nonPrivate

        // Restoring a backup always turns "keep hidden apps in Recents" back off. The pref is
        // never exported (it lives in slate_device_prefs, which no backup route carries), so
        // this is not a restore of a stored value - it is a deliberate reset, so a weakened
        // privacy setting can never survive an import the user may not have thought about.
        // Deliberately the FIRST statement: several optString calls below can throw on a
        // hand-edited file, and this must still have run if the import dies partway.
        prefs.keepHiddenAppsInRecents = false

        // Deliberately NOT reset here, and the contrast with the line above is the point:
        //  - showWorkApps is an ordinary display preference; resetting it would silently
        //    re-show work apps for someone who had turned them off.
        //  - workGroupedSerials is never exported and must not be cleared, because clearing it
        //    re-arms automatic grouping and would drag back apps the user had moved out.
        //
        // A backup carries no work keys at all (see isWorkKey), so an import would otherwise
        // wipe the local Work folder entirely. Capture it here and restore it after the
        // wholesale folders write below.
        val localWorkFolders = FolderStore.all(prefs).filter { it.profileSerial != null }
        val localWorkPins = prefs.pinnedFolders.filter { id ->
            localWorkFolders.any { it.id == id }
        }

        prefs.minFontSize  = root.optInt("minFontSize",  PreferencesManager.DEFAULT_MIN_FONT_SIZE)
        prefs.maxFontSize  = root.optInt("maxFontSize",  PreferencesManager.DEFAULT_MAX_FONT_SIZE)
        prefs.lineSpacing  = root.optInt("lineSpacing",  PreferencesManager.DEFAULT_LINE_SPACING)
        prefs.wordSpacing  = root.optInt("wordSpacing",  PreferencesManager.DEFAULT_WORD_SPACING)
        prefs.fontFamily   = root.optString("fontFamily",   PreferencesManager.DEFAULT_FONT_FAMILY)
        prefs.fontWeight   = root.optInt("fontWeight",   PreferencesManager.DEFAULT_FONT_WEIGHT)
        prefs.backgroundColor = root.optString("backgroundColor", PreferencesManager.DEFAULT_BACKGROUND_COLOR)
        prefs.appTextColor    = root.optString("appTextColor",    PreferencesManager.DEFAULT_TEXT_COLOR)
        prefs.doubleTapToLock   = root.optBoolean("doubleTapToLock", false)
        // Sanitise: only "left"/"center"/"right" are valid. A hand-edited or future-version
        // backup with anything else falls back to the default rather than persisting an
        // unsupported value. Read sites in AppDrawerFragment already fall through to "center"
        // for unknown values, so this is defence-in-depth.
        prefs.textAlignment     = root.optString("textAlignment", "center")
            .takeIf { it == "left" || it == "center" || it == "right" } ?: "center"
        prefs.sortByUsage       = root.optBoolean("sortByUsage", false)
        // Sanitise: only "top"/"bottom" are valid. Same defence-in-depth pattern as
        // `searchBarPosition` below.
        prefs.mostUsedPosition  = root.optString("mostUsedPosition", "top")
            .takeIf { it == "top" || it == "bottom" } ?: "top"
        prefs.lockOrientation   = root.optBoolean("lockOrientation", true)
        prefs.hideStatusBar     = root.optBoolean("hideStatusBar", false)
        prefs.notificationColorEnabled   = root.optBoolean("notificationColorEnabled", false)
        prefs.notificationHighlightColor = root.optString("notificationHighlightColor", "#FFFFFF")
        prefs.ignoreSilentNotifications = root.optBoolean("ignoreSilentNotifications", false)
        prefs.syncToLockscreen  = root.optBoolean("syncToLockscreen", false)
        prefs.searchEnabled     = root.optBoolean("searchEnabled", true)
        prefs.showSearchBarOnHome = root.optBoolean("showSearchBarOnHome", false)
        // Sanitise: only "top"/"bottom" are valid. Matches the same defence-in-depth pattern
        // used for `quickStripPosition` further down. Unknown values silently fall through to
        // "top" at read time anyway, but this keeps the on-disk pref clean.
        prefs.searchBarPosition = root.optString("searchBarPosition", "top")
            .takeIf { it == "top" || it == "bottom" } ?: "top"

        // Pinned apps
        root.optJSONArray("pinnedApps")?.let { arr ->
            prefs.pinnedApps = (0 until arr.length())
                .map { arr.getString(it) }.filterNot { isWorkKey(it) }.toSet()
        }
        // Not cross-checked against the restored folder set: an id with no matching folder is
        // simply never rendered, exactly as pinnedApps tolerates an uninstalled package. An
        // older backup with no "pinnedFolders" key leaves the device's existing pins alone.
        root.optJSONArray("pinnedFolders")?.let { arr ->
            // localWorkPins survive for the same reason the folders themselves do.
            prefs.pinnedFolders =
                (0 until arr.length()).map { arr.getString(it) }.toSet() + localWorkPins
        }

        // Auto-theme
        prefs.followSystemTheme = root.optBoolean("followSystemTheme", false)

        // Gesture actions
        root.optJSONObject("gestureActions")?.let { obj ->
            obj.keys().forEach { key ->
                val value = obj.getString(key)
                if (value.startsWith("app:") && isWorkKey(value.removePrefix("app:"))) return@forEach
                prefs.setGestureActionRaw(key, value)
            }
        }

        // Per-app colors
        root.optJSONObject("appColors")?.let { obj ->
            obj.keys().forEach { key ->
                if (!isWorkKey(key)) prefs.setAppTextColor(key, obj.getString(key))
            }
        }

        // Per-app custom names
        root.optJSONObject("appCustomNames")?.let { obj ->
            obj.keys().forEach { key ->
                if (!isWorkKey(key)) prefs.setAppCustomName(key, obj.getString(key))
            }
        }

        // Quick toggles strip
        prefs.quickStripEnabled = root.optBoolean("quickStripEnabled", false)
        // Absence falls back to "bottom" - current behaviour for older backups.
        prefs.quickStripPosition = root.optString("quickStripPosition", "bottom")
            .takeIf { it == "top" || it == "bottom" } ?: "bottom"
        prefs.quickStripDividerEnabled = root.optBoolean("quickStripDividerEnabled", false)
        prefs.widgetTextSize = root.optInt("widgetTextSize", PreferencesManager.DEFAULT_WIDGET_TEXT_SIZE)
        prefs.widgetLineGap  = root.optInt("widgetLineGap",  PreferencesManager.DEFAULT_WIDGET_LINE_GAP)
        prefs.widgetWordGap  = root.optInt("widgetWordGap",  PreferencesManager.DEFAULT_WIDGET_WORD_GAP)
        prefs.widgetFontFamily = root.optString("widgetFontFamily", PreferencesManager.DEFAULT_WIDGET_FONT_FAMILY)
        prefs.widgetFontWeight = root.optInt("widgetFontWeight", PreferencesManager.DEFAULT_WIDGET_FONT_WEIGHT)
        // Sanitise the alignment string - only "left"/"center"/"right" are valid. A backup with
        // a typo or a future-version value falls back to the default rather than persisting an
        // unsupported state.
        prefs.widgetTextAlignment = root.optString(
            "widgetTextAlignment", PreferencesManager.DEFAULT_WIDGET_TEXT_ALIGNMENT
        ).takeIf { it == "left" || it == "center" || it == "right" }
            ?: PreferencesManager.DEFAULT_WIDGET_TEXT_ALIGNMENT
        prefs.directCallEnabled = root.optBoolean("directCallEnabled", false)
        // Sanitise: only "tap"/"longPress" are accepted. Defence-in-depth - the pref getter
        // also guards against an unknown stored value, so a corrupt write here resolves to
        // default on read.
        prefs.directCallTrigger = root.optString(
            "directCallTrigger", PreferencesManager.DEFAULT_DIRECT_CALL_TRIGGER
        ).takeIf { it == "tap" || it == "longPress" }
            ?: PreferencesManager.DEFAULT_DIRECT_CALL_TRIGGER
        root.optJSONArray("quickStripWidgets")?.let { arr ->
            prefs.quickStripWidgets = (0 until arr.length()).map { arr.getString(it) }
        }
        root.optJSONArray("contactShortcuts")?.let { arr ->
            prefs.contactShortcutsJson = arr.toString()
        }
        root.optJSONArray("folders")?.let { arr ->
            // Re-append the local work folders the import would otherwise destroy. Appending
            // rather than merging by id is correct: an imported folder and a local work folder
            // are different objects with different ids, and the user can dissolve either.
            val merged = JSONArray(arr.toString())
            localWorkFolders.forEach { folder ->
                merged.put(
                    JSONObject().apply {
                        put("id", folder.id)
                        put("name", folder.name)
                        put("packages", JSONArray().also { a -> folder.packages.forEach(a::put) })
                        folder.color?.let { put("color", it) }
                        folder.profileSerial?.let { put("profileSerial", it) }
                    }
                )
            }
            prefs.foldersJson = merged.toString()
        }
        // Absent on a backup taken before this feature shipped - the pref stays at its "[]"
        // default, which PinnedShortcutStore.all() parses as an empty list defensively anyway.
        root.optJSONArray("pinnedShortcuts")?.let { arr ->
            prefs.pinnedShortcutsJson = arr.toString()
        }
        // Restoring `seenVersion` suppresses the auto-tour on the new device (the user already
        // saw it before exporting). Mid-tour `stepIndex` is intentionally reset so a partial
        // tour on the old device doesn't pop up here.
        if (root.has("guidedTourSeenVersion")) {
            prefs.guidedTourSeenVersion = root.optInt("guidedTourSeenVersion", 0)
            prefs.guidedTourStepIndex = -1
        }

        // Folder display style; sanitise against the known FOLDER_STYLE_* set. Unknown or
        // empty values leave the existing pref alone (which itself reads as chevron by default
        // when unset), so an older backup that pre-dates this set still restores cleanly.
        val knownFolderStyles = setOf(
            PreferencesManager.FOLDER_STYLE_CHEVRON,
            PreferencesManager.FOLDER_STYLE_SLASH,
            PreferencesManager.FOLDER_STYLE_BULLET,
            PreferencesManager.FOLDER_STYLE_BRACKETS,
            PreferencesManager.FOLDER_STYLE_COUNT,
            PreferencesManager.FOLDER_STYLE_PLAIN
        )
        if (root.has("folderStyle")) {
            val style = root.optString("folderStyle")
            if (style in knownFolderStyles) prefs.folderStyle = style
        }

        // Same whitelist idiom, and for the same reason: an absent key leaves the device's own
        // choice alone, and an unknown value is ignored rather than written, so a hand-edited
        // or newer-version file cannot persist a style this build does not render. One of the
        // four hand-maintained enumerations of these eight values.
        val knownWorkMarkers = setOf(
            PreferencesManager.WORK_MARKER_WORD,
            PreferencesManager.WORK_MARKER_BRACKETS,
            PreferencesManager.WORK_MARKER_DAGGER,
            PreferencesManager.WORK_MARKER_STAR,
            PreferencesManager.WORK_MARKER_DOT,
            PreferencesManager.WORK_MARKER_SQUARE,
            PreferencesManager.WORK_MARKER_DIAMOND,
            PreferencesManager.WORK_MARKER_NONE
        )
        if (root.has("workMarkerStyle")) {
            val marker = root.optString("workMarkerStyle")
            if (marker in knownWorkMarkers) prefs.workMarkerStyle = marker
        }
        // No whitelist needed - a boolean cannot carry an unrenderable value. Default matches
        // PreferencesManager so a pre-existing backup file imports as ON, same as a fresh install.
        prefs.suppressWorkMarkerInFolder =
            root.optBoolean("suppressWorkMarkerInFolder", true)
    }

    /**
     * Apply the private bundle to disk. Called ONLY after the import-time PIN dialog has
     * verified the backup's PIN in-memory against [PinManager.verifyAgainst]. Replaces the
     * device's hidden-apps list, security flag, biometric flag, and PIN verifier with the
     * backup's. Lockout counters reset to zero because they're device-local state, not user
     * data - the backup wasn't authorised to carry past failure counts forward.
     */
    fun applyPrivate(bundle: PrivateBundle) {
        prefs.hiddenApps = bundle.hiddenApps
        prefs.pinHash = bundle.pinHash
        prefs.pinSalt = bundle.pinSalt
        prefs.pinIterations = bundle.pinIterations
        prefs.hiddenAppsSecurityEnabled = bundle.hiddenAppsSecurityEnabled
        prefs.biometricEnabled = bundle.biometricEnabled
        prefs.pinFailedAttempts = 0
        prefs.pinLockoutUntilEpochMs = 0L
        prefs.pinLockoutUntilElapsedMs = 0L
    }
}
