package com.slate.launcher

import android.app.ActivityManager
import android.app.Dialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.google.android.material.materialswitch.MaterialSwitch
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import com.slate.launcher.widgets.ContactShortcut
import com.slate.launcher.widgets.ContactShortcutStore
import com.slate.launcher.widgets.WidgetPickerDialog
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var switchDoubleTap: MaterialSwitch
    private lateinit var createBackupLauncher: ActivityResultLauncher<String>
    private lateinit var openBackupLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var importFontLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>
    private lateinit var batteryExemptLauncher: ActivityResultLauncher<Intent>
    private lateinit var pickContactLauncher: ActivityResultLauncher<Intent>
    private lateinit var requestCallPhoneLauncher: ActivityResultLauncher<String>
    // Tracks whether the user is currently adding a "call" or "sms" shortcut so the picker
    // callback knows which widget kind to construct.
    private var pendingShortcutType: ContactShortcut.Type? = null
    // Re-runs the biometric-row visibility/reconcile closure from setupSecurity. Captured on
    // setup; invoked from syncPermissionToggles so a biometric-enrollment change made outside
    // Slate (Android system Settings) is detected on the next onResume — mirrors the
    // syncAccessibilityToggle pattern but without an `awaiting*` flag (biometric grant is
    // entirely in-app, no system-settings round-trip).
    private var biometricReconcile: (() -> Unit)? = null
    // awaitingAccessibilityPermission and awaitingNotificationPermission
    // are persisted in PreferencesManager to survive process death

    companion object {
        private val MIN_SIZES     = (8..24).toList()
        private val MAX_SIZES     = (20..60).toList()
        private val LINE_SPACINGS = (0..24).toList()
        private val WORD_SPACINGS = (2..28).toList()
        // Widget-strip typography ranges. Text size capped at 32 — labels are short, so a
        // narrower range than the app list is appropriate. Word gap floored at 2 (mirrors
        // WORD_SPACINGS) to prevent adjacent widget labels from running into each other.
        private val WIDGET_TEXT_SIZES = (10..32).toList()
        private val WIDGET_LINE_GAPS  = (0..20).toList()
        private val WIDGET_WORD_GAPS  = (2..28).toList()

        // Fixed sample labels for the Settings preview. Chosen to span the four widget label
        // shapes used on the home strip: short state toggle, long state toggle, Name:value with
        // a time, Name:value with a percentage. Kept identical in style to live home-screen
        // labels so the preview is faithful — what the user sees here is what they get there.
        // Independent of the user's actual widget selection so the preview is deterministic.
        private val PREVIEW_SAMPLE_LABELS =
            listOf("Wi-Fi", "Bluetooth", "Time: 12:34", "Battery: 65%")

        private val GESTURE_SLOTS = listOf(
            Triple(1, Direction.UP,    "1 finger  ↑"),
            Triple(1, Direction.DOWN,  "1 finger  ↓"),
            Triple(1, Direction.LEFT,  "1 finger  ←"),
            Triple(1, Direction.RIGHT, "1 finger  →"),
        )

        data class FontOption(val key: String, val displayName: String)

        val FONTS = listOf(
            FontOption("gf:tex_gyre_adventor_bold", "TeX Gyre Adventor Bold"),
            FontOption("gf:roboto",                 "Roboto"),
            FontOption("gf:noto_sans",              "Noto Sans"),
            FontOption("gf:coming_soon",            "Coming Soon"),
            FontOption("gf:cutive_mono",            "Cutive Mono"),
            FontOption("sans-serif",                "Sans-serif"),
            FontOption("serif",                     "Serif"),
            FontOption("monospace",                 "Monospace"),
            FontOption("cursive",                   "Cursive"),
        )
        val WEIGHTS = listOf(
            300 to "Light",
            400 to "Regular",
            500 to "Medium",
            700 to "Bold",
        )

        data class ColorPreset(val bg: String, val text: String)
        val PRESETS = listOf(
            ColorPreset("#000000", "#808080"),
            ColorPreset("#101010", "#808080"),
            ColorPreset("#d0d0d0", "#263238"),
            ColorPreset("#0f3460", "#d0d0d0"),
        )

        // (pref value, picker label) — order here drives picker order and the default-on-unknown
        // fallback in `folderStyleDisplayLabel`. Keep Chevron first so it doubles as the default.
        val FOLDER_STYLE_LABELS: List<Pair<String, String>> = listOf(
            PreferencesManager.FOLDER_STYLE_CHEVRON  to "Chevron",
            PreferencesManager.FOLDER_STYLE_SLASH    to "Slash",
            PreferencesManager.FOLDER_STYLE_BULLET   to "Bullet",
            PreferencesManager.FOLDER_STYLE_BRACKETS to "Brackets",
            PreferencesManager.FOLDER_STYLE_COUNT    to "Count",
            PreferencesManager.FOLDER_STYLE_PLAIN    to "Plain",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createBackupLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> if (uri != null) saveBackup(uri) }

        openBackupLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) loadBackup(uri) }

        importFontLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) importFont(uri) }

        requestRoleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* result handled; just return to settings */ }

        batteryExemptLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { updateBatteryBanner() }

        // Picker on Phone.CONTENT_URI returns a URI directly to the selected Phone row, with a
        // one-shot read grant. This avoids needing READ_CONTACTS (the system grants temporary
        // access to that exact row), and naturally resolves contacts with multiple numbers since
        // the user picks the specific number in the picker UI.
        pickContactLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { onContactPicked(it) }
            } else {
                pendingShortcutType = null
            }
        }

        // CALL_PHONE runtime grant. Owned by the Direct-call setup flow — we register the
        // launcher unconditionally (must be done before STARTED), but it is only `launch`ed
        // when the user toggles Direct call ON without an existing grant. The callback writes
        // the final pref + switch state; the detach-set-reattach inside the callback prevents
        // the silent revert from re-firing the listener.
        requestCallPhoneLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val switch = findViewById<MaterialSwitch>(R.id.switchDirectCall)
            switch.setOnCheckedChangeListener(null)
            prefs.directCallEnabled = granted
            switch.isChecked = granted
            attachDirectCallListener(switch)
            // Trigger sub-row appears only when both master Quick toggles AND Direct call are
            // on. On a denial we flip the master back to OFF so it must also be hidden.
            val rowTrigger = findViewById<View>(R.id.rowDirectCallTrigger)
            rowTrigger.visibility =
                if (granted && prefs.quickStripEnabled) View.VISIBLE else View.GONE
            if (!granted) {
                Toast.makeText(this, "Permission required for direct call", Toast.LENGTH_SHORT)
                    .show()
            }
        }

        prefs = PreferencesManager(this)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        switchDoubleTap = findViewById(R.id.switchDoubleTap)

        applyBackgroundColor()
        setupTextSize()
        setupTypography()
        setupColors()
        setupGestures()
        setupSearch()
        setupBackup()
        setupGeneral()
        setupQuickStrip()
        setupSecurity()
        setupAbout()
        setupBatteryBanner()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        // Prevent android.view.WindowLeaked if any of our owned dialogs is showing during a
        // configuration change.
        com.slate.launcher.widgets.WidgetPickerDialog.dismissActive()
        com.slate.launcher.widgets.WidgetArrangeDialog.dismissActive()
        GuidedTourManager.dismissActive()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.followSystemTheme) {
            applySystemThemeColors()
            applyBackgroundColor()
        }
        updateDefaultLauncherRow()
        syncPermissionToggles()
        updateBatteryBanner()
    }

    private fun syncPermissionToggles() {
        syncAccessibilityToggle()
        syncNotificationToggle()
        // Biometric reconcile: the user might have removed their fingerprint / face
        // enrollment from Android system Settings while Slate Settings was in the background.
        // The closure is set during setupSecurity and re-runs applyVisibility, which now
        // flips `prefs.biometricEnabled` off when enrollment is gone. No-op when biometric
        // is still available or pref is already false.
        biometricReconcile?.invoke()
    }

    private fun syncAccessibilityToggle() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        if (prefs.awaitingAccessibilityPermission && accessibilityEnabled) {
            // Returning from permission grant flow and service is enabled — auto-enable
            prefs.doubleTapToLock = true
            switchDoubleTap.setOnCheckedChangeListener(null)
            switchDoubleTap.isChecked = true
            setupDoubleTapListener()
            prefs.awaitingAccessibilityPermission = false
        } else if (prefs.awaitingAccessibilityPermission && !accessibilityEnabled) {
            // Returned from settings but service not detected yet — retry after delay
            // (service binding can lag behind the secure setting on some OEMs)
            prefs.awaitingAccessibilityPermission = false
            switchDoubleTap.postDelayed({
                if (isAccessibilityServiceEnabled()) {
                    prefs.doubleTapToLock = true
                    switchDoubleTap.setOnCheckedChangeListener(null)
                    switchDoubleTap.isChecked = true
                    setupDoubleTapListener()
                }
            }, 500)
        } else if (prefs.doubleTapToLock && !accessibilityEnabled) {
            // Permission was revoked externally — but give the service a moment
            // to bind before aggressively disabling the toggle
            switchDoubleTap.postDelayed({
                if (!isAccessibilityServiceEnabled()) {
                    prefs.doubleTapToLock = false
                    switchDoubleTap.setOnCheckedChangeListener(null)
                    switchDoubleTap.isChecked = false
                    setupDoubleTapListener()
                }
            }, 500)
        }
    }

    private fun syncNotificationToggle() {
        val notifEnabled = isNotificationListenerEnabled()
        val switchNotif = findViewById<MaterialSwitch>(R.id.switchNotifColor) ?: return

        if (prefs.awaitingNotificationPermission && notifEnabled) {
            prefs.notificationColorEnabled = true
            switchNotif.setOnCheckedChangeListener(null)
            switchNotif.isChecked = true
            setupNotifListener(switchNotif)
            findViewById<View>(R.id.rowNotifHighlight).visibility = View.VISIBLE
            prefs.awaitingNotificationPermission = false
        } else if (prefs.awaitingNotificationPermission && !notifEnabled) {
            prefs.awaitingNotificationPermission = false
            switchNotif.postDelayed({
                if (isNotificationListenerEnabled()) {
                    prefs.notificationColorEnabled = true
                    switchNotif.setOnCheckedChangeListener(null)
                    switchNotif.isChecked = true
                    setupNotifListener(switchNotif)
                    findViewById<View>(R.id.rowNotifHighlight).visibility = View.VISIBLE
                }
            }, 500)
        } else if (prefs.notificationColorEnabled && !notifEnabled) {
            switchNotif.postDelayed({
                if (!isNotificationListenerEnabled()) {
                    prefs.notificationColorEnabled = false
                    switchNotif.setOnCheckedChangeListener(null)
                    switchNotif.isChecked = false
                    setupNotifListener(switchNotif)
                    findViewById<View>(R.id.rowNotifHighlight).visibility = View.GONE
                }
            }, 500)
        }
    }

    private fun updateDefaultLauncherRow() {
        val sub = findViewById<TextView>(R.id.labelDefaultLauncherSub) ?: return
        sub.text = if (isAlreadyDefaultLauncher())
            "Slate is your default launcher"
        else
            "Open system launcher picker"
    }

    // ── Background ───────────────────────────────────────────────

    private fun applyBackgroundColor() {
        val color = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(color)
        val drawable = ColorDrawable(color)

        window.setBackgroundDrawable(drawable)
        findViewById<View>(android.R.id.content).setBackgroundColor(color)
        supportActionBar?.setBackgroundDrawable(drawable)
        applySystemBarColors(color)

        val primary   = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")
        val accent    = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")

        val title = SpannableString("Settings").apply {
            setSpan(ForegroundColorSpan(primary), 0, length, 0)
        }
        supportActionBar?.title = title

        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        applyTextColors(root, primary, secondary, accent)

        val dividerColor = if (isLight) Color.parseColor("#22000000") else Color.parseColor("#22FFFFFF")
        listOf(R.id.divider0, R.id.divider1, R.id.divider1b, R.id.divider2,
               R.id.divider3, R.id.divider3b, R.id.divider4, R.id.divider5, R.id.divider6).forEach { id ->
            findViewById<View>(id)?.setBackgroundColor(dividerColor)
        }

        applySwitchColors(
            switchDoubleTap,
            findViewById(R.id.switchSearch),
            findViewById(R.id.switchSearchOnHome),
            findViewById(R.id.switchHideStatusBar),
            findViewById(R.id.switchSortByUsage),
            findViewById(R.id.switchLockOrientation),
            findViewById(R.id.switchNotifColor),
            findViewById(R.id.switchSyncToLockscreen),
            findViewById(R.id.switchFollowSystemTheme),
            findViewById(R.id.switchAlphaFastScroll),
            findViewById(R.id.switchHiddenAppsSecurity),
            findViewById(R.id.switchBiometric),
            findViewById(R.id.switchQuickStrip),
            findViewById(R.id.switchDirectCall),
            findViewById(R.id.switchQuickStripDivider)
        )
    }

    private fun applySwitchColors(vararg switches: MaterialSwitch?) {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val accent   = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val trackOff = if (isLight) Color.parseColor("#CCCCCC") else Color.parseColor("#555555")
        val thumbOff = if (isLight) Color.parseColor("#FFFFFF") else Color.parseColor("#888888")

        val thumbStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, thumbOff)
        )
        val trackStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, trackOff)
        )
        switches.filterNotNull().forEach { sw ->
            sw.thumbTintList = thumbStates
            sw.trackTintList = trackStates
        }
    }

    private fun applyTextColors(
        view: android.view.View,
        primary: Int, secondary: Int, accent: Int
    ) {
        when (view) {
            is android.widget.EditText -> {
                view.setTextColor(primary)
                view.setHintTextColor(secondary)
            }
            is TextView -> {
                val isSectionLabel = view.isAllCaps && view.typeface?.isBold == true
                val isDimmed = view.alpha < 0.99f
                view.setTextColor(when {
                    isSectionLabel -> accent
                    isDimmed       -> secondary
                    else           -> primary
                })
            }
            is android.view.ViewGroup -> {
                if (view.tag == "no_theme") return
                for (i in 0 until view.childCount) {
                    applyTextColors(view.getChildAt(i), primary, secondary, accent)
                }
            }
        }
    }

    // ── Text Size ────────────────────────────────────────────────

    private fun setupTextSize() {
        val minSeekBar = findViewById<SeekBar>(R.id.minFontSeekBar)
        val minLabel = findViewById<TextView>(R.id.minFontLabel)
        val maxSeekBar = findViewById<SeekBar>(R.id.maxFontSeekBar)
        val maxLabel = findViewById<TextView>(R.id.maxFontLabel)

        // Normalize any inverted Min/Max that may have been persisted (e.g., from a backup
        // imported before the cross-clamp listeners shipped, or from a much older app version
        // that allowed inversion). Pull Max up to Min so the slider state is always valid by
        // the time the sliders render. The overlap zone 20–24 sp is present in both MIN_SIZES
        // (8–24) and MAX_SIZES (20–60), so this assignment always lands on a valid index.
        if (prefs.maxFontSize < prefs.minFontSize) {
            prefs.maxFontSize = prefs.minFontSize
        }

        minSeekBar.max = MIN_SIZES.size - 1
        minSeekBar.progress = MIN_SIZES.indexOf(prefs.minFontSize)
            .takeIf { it >= 0 } ?: MIN_SIZES.indexOf(PreferencesManager.DEFAULT_MIN_FONT_SIZE).coerceAtLeast(0)
        minLabel.text = "${prefs.minFontSize}sp"

        maxSeekBar.max = MAX_SIZES.size - 1
        maxSeekBar.progress = MAX_SIZES.indexOf(prefs.maxFontSize)
            .takeIf { it >= 0 } ?: MAX_SIZES.indexOf(PreferencesManager.DEFAULT_MAX_FONT_SIZE).coerceAtLeast(0)
        maxLabel.text = "${prefs.maxFontSize}sp"

        // Cross-clamp: when the user drags Min above Max (or Max below Min), pull the other
        // slider along visibly. The two slider ranges overlap in 20–24 sp, so the indexOf
        // lookup on the paired list always succeeds for any reachable cross-point.
        //
        // The programmatic `progress = …` does NOT recursively fire the paired listener:
        // `seekBarListener` guards on `fromUser`, so user-initiated and programmatic updates
        // are distinguishable. No feedback loop, no debouncing needed.
        //
        // Updating the pref BEFORE the visual progress so any other reader (e.g., the home
        // renderer if it ever woke up mid-update) sees the consistent state.
        minSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            val newMin = MIN_SIZES[p]
            prefs.minFontSize = newMin
            minLabel.text = "${newMin}sp"
            if (newMin > prefs.maxFontSize) {
                prefs.maxFontSize = newMin
                maxSeekBar.progress = MAX_SIZES.indexOf(newMin).coerceAtLeast(0)
                maxLabel.text = "${newMin}sp"
            }
        })
        maxSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            val newMax = MAX_SIZES[p]
            prefs.maxFontSize = newMax
            maxLabel.text = "${newMax}sp"
            if (newMax < prefs.minFontSize) {
                prefs.minFontSize = newMax
                minSeekBar.progress = MIN_SIZES.indexOf(newMax).coerceAtLeast(0)
                minLabel.text = "${newMax}sp"
            }
        })

        val lineSeekBar = findViewById<SeekBar>(R.id.lineSpacingSeekBar)
        val lineLabel   = findViewById<TextView>(R.id.lineSpacingLabel)
        val wordSeekBar = findViewById<SeekBar>(R.id.wordSpacingSeekBar)
        val wordLabel   = findViewById<TextView>(R.id.wordSpacingLabel)

        lineSeekBar.max = LINE_SPACINGS.size - 1
        lineSeekBar.progress = LINE_SPACINGS.indexOf(prefs.lineSpacing)
            .takeIf { it >= 0 } ?: LINE_SPACINGS.indexOf(PreferencesManager.DEFAULT_LINE_SPACING).coerceAtLeast(0)
        lineLabel.text = "${prefs.lineSpacing}dp"

        wordSeekBar.max = WORD_SPACINGS.size - 1
        wordSeekBar.progress = WORD_SPACINGS.indexOf(prefs.wordSpacing)
            .takeIf { it >= 0 } ?: WORD_SPACINGS.indexOf(PreferencesManager.DEFAULT_WORD_SPACING).coerceAtLeast(0)
        wordLabel.text = "${prefs.wordSpacing}dp"

        lineSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            prefs.lineSpacing = LINE_SPACINGS[p]; lineLabel.text = "${LINE_SPACINGS[p]}dp"
        })
        wordSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            prefs.wordSpacing = WORD_SPACINGS[p]; wordLabel.text = "${WORD_SPACINGS[p]}dp"
        })

        applyHomescreenViewToTextSize()
    }

    /**
     * In Flow mode both Min and Max sliders are meaningful (scale by usage). In Minimal List mode
     * every app is one uniform size, driven by `prefs.maxFontSize`, so the Min slider is hidden
     * and the Max slider's label reads "Size" instead of "Maximum".
     */
    private fun applyHomescreenViewToTextSize() {
        val isList = prefs.homescreenView == PreferencesManager.VIEW_LIST
        findViewById<View>(R.id.rowMinFontSize)?.visibility =
            if (isList) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.labelMaximum)?.text =
            if (isList) "Size" else "Maximum"
    }

    private fun seekBarListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChanged(p) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    // ── Typography ───────────────────────────────────────────────

    private fun setupTypography() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        val fontValue = findViewById<TextView>(R.id.fontValue)
        val weightValue = findViewById<TextView>(R.id.weightValue)

        fontValue.setTextColor(secondary)
        weightValue.setTextColor(secondary)

        fontValue.text = fontDisplayName(prefs.fontFamily)
        weightValue.text = WEIGHTS.find { it.first == prefs.fontWeight }?.second ?: "Regular"

        val fontItems = FONTS.map { it.displayName } + listOf("Import from storage…")

        findViewById<android.view.View>(R.id.rowFont).setOnClickListener {
            SlateListDialog(
                context = this,
                title = "Font",
                items = fontItems,
                bgColor = prefs.backgroundColor
            ) { index, label ->
                if (index < FONTS.size) {
                    prefs.fontFamily = FONTS[index].key
                    fontValue.text = label
                } else {
                    importFontLauncher.launch(arrayOf("*/*"))
                }
            }.show()
        }

        _fontValueRef = fontValue

        val alignmentValue = findViewById<TextView>(R.id.alignmentValue)
        alignmentValue.setTextColor(secondary)
        alignmentValue.text = prefs.textAlignment.replaceFirstChar { it.uppercaseChar() }

        findViewById<android.view.View>(R.id.rowAlignment).setOnClickListener {
            SlateListDialog(
                context = this,
                title = "Alignment",
                items = listOf("Left", "Center", "Right"),
                bgColor = prefs.backgroundColor
            ) { _, label ->
                prefs.textAlignment = label.lowercase()
                alignmentValue.text = label
            }.show()
        }

        findViewById<android.view.View>(R.id.rowWeight).setOnClickListener {
            SlateListDialog(
                context = this,
                title = "Weight",
                items = WEIGHTS.map { it.second },
                bgColor = prefs.backgroundColor
            ) { index, label ->
                prefs.fontWeight = WEIGHTS[index].first
                weightValue.text = label
            }.show()
        }

        // Folder style — picker maps human labels to the FOLDER_STYLE_* pref values.
        val folderStyleValue = findViewById<TextView>(R.id.folderStyleValue)
        folderStyleValue.setTextColor(secondary)
        folderStyleValue.text = folderStyleDisplayLabel(prefs.folderStyle)

        findViewById<android.view.View>(R.id.rowFolderStyle).setOnClickListener {
            SlateListDialog(
                context = this,
                title = "Folder style",
                items = FOLDER_STYLE_LABELS.map { it.second },
                bgColor = prefs.backgroundColor,
                // Right-column preview shows exactly how the marker renders for a folder named
                // "Work". Order MUST match FOLDER_STYLE_LABELS one-for-one — the dialog falls
                // back to single-column on a size mismatch, so the alignment is enforced by
                // building this list from the same source.
                secondaryItems = FOLDER_STYLE_LABELS.map { (key, _) ->
                    folderStylePreview(key)
                }
            ) { index, label ->
                prefs.folderStyle = FOLDER_STYLE_LABELS[index].first
                folderStyleValue.text = label
            }.show()
        }
    }

    /** Resolve the persisted pref value to the user-visible row label. */
    private fun folderStyleDisplayLabel(value: String): String =
        FOLDER_STYLE_LABELS.firstOrNull { it.first == value }?.second
            ?: FOLDER_STYLE_LABELS.first().second

    /**
     * Sample render of each folder marker style for the picker preview column. Mirrors the
     * `folderLabel` helper in AppDrawerFragment but with a fixed sample folder name and a
     * fixed visible-count so the preview is stable. Uses a regular space (not NBSP) for the
     * bullet/count styles — orphan-wrap protection only matters in Flow's wrapping paragraph,
     * not inside a single dialog row.
     */
    private fun folderStylePreview(styleKey: String): String =
        when (styleKey) {
            PreferencesManager.FOLDER_STYLE_SLASH    -> "Work/"
            PreferencesManager.FOLDER_STYLE_BULLET   -> "• Work"
            PreferencesManager.FOLDER_STYLE_BRACKETS -> "[Work]"
            PreferencesManager.FOLDER_STYLE_COUNT    -> "Work (5)"
            PreferencesManager.FOLDER_STYLE_PLAIN    -> "Work"
            else                                     -> "Work ›"
        }

    private var _fontValueRef: TextView? = null

    private fun fontDisplayName(key: String): String = when {
        key.startsWith("/") -> File(key).nameWithoutExtension
        else -> FONTS.find { it.key == key }?.displayName ?: "Default"
    }

    private fun importFont(uri: Uri) {
        try {
            val rawName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: "custom_font.ttf"

            val fontDir = File(filesDir, "fonts").apply { mkdirs() }
            val destFile = File(fontDir, rawName)
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }

            prefs.fontFamily = destFile.absolutePath
            val displayName = destFile.nameWithoutExtension
            _fontValueRef?.text = displayName
            Toast.makeText(this, "Font imported: $displayName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Colors ───────────────────────────────────────────────────

    private fun setupColors() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")
        val borderColor = if (isLight) Color.parseColor("#BBBBBB") else Color.parseColor("#444444")
        val density = resources.displayMetrics.density

        val bgDisplay   = findViewById<TextView>(R.id.bgColorDisplay)
        val bgSwatch    = findViewById<View>(R.id.bgColorSwatch)
        val textDisplay = findViewById<TextView>(R.id.textColorDisplay)
        val textSwatch  = findViewById<View>(R.id.textColorSwatch)

        bgDisplay.setTextColor(secondary)
        textDisplay.setTextColor(secondary)

        fun updateBgSwatch(hex: String) {
            bgDisplay.text = hex
            bgSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * density
                setColor(parseColorSafe(hex))
                setStroke((1.5f * density).toInt(), borderColor)
            }
        }

        fun updateTextSwatch(hex: String) {
            textDisplay.text = hex
            textSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * density
                setColor(parseColorSafe(hex, Color.GRAY))
                setStroke((1.5f * density).toInt(), borderColor)
            }
        }

        updateBgSwatch(prefs.backgroundColor)
        updateTextSwatch(prefs.appTextColor)

        fun syncLockscreenIfNeeded(colorInt: Int) {
            if (!prefs.syncToLockscreen) return
            val ok = MainActivity.applyColorToLockscreen(this, colorInt)
            if (!ok) Toast.makeText(this, "Could not set lockscreen wallpaper", Toast.LENGTH_SHORT).show()
        }

        // Entire row opens the picker — no keyboard input
        fun openBgPicker() {
            ColorPickerDialog(
                context = this,
                title = "Background",
                initialColor = prefs.backgroundColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.followSystemTheme = false
                prefs.backgroundColor = hex
                updateBgSwatch(hex)
                applyBackgroundColor()
                syncLockscreenIfNeeded(parseColorSafe(hex))
                setupColors()
            }.show()
        }

        fun openTextPicker() {
            ColorPickerDialog(
                context = this,
                title = "App Text",
                initialColor = prefs.appTextColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.followSystemTheme = false
                prefs.appTextColor = hex
                updateTextSwatch(hex)
                setupColors()
            }.show()
        }

        findViewById<View>(R.id.rowBgColor).setOnClickListener { openBgPicker() }
        findViewById<View>(R.id.rowTextColor).setOnClickListener { openTextPicker() }

        // Follow system theme toggle
        val switchFollowSystem = findViewById<MaterialSwitch>(R.id.switchFollowSystemTheme)
        switchFollowSystem.isChecked = prefs.followSystemTheme
        switchFollowSystem.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                switchFollowSystem.isChecked = false
                this@SettingsActivity.showFollowSystemThemeDialog(switchFollowSystem)
            } else {
                prefs.followSystemTheme = false
            }
        }

        // Apply to lockscreen toggle
        val switchSyncToLockscreen = findViewById<MaterialSwitch>(R.id.switchSyncToLockscreen)
        switchSyncToLockscreen.isChecked = prefs.syncToLockscreen
        switchSyncToLockscreen.setOnCheckedChangeListener { _, checked ->
            prefs.syncToLockscreen = checked
            if (checked) {
                val ok = MainActivity.applyColorToLockscreen(this, parseColorSafe(prefs.backgroundColor))
                if (!ok) {
                    Toast.makeText(this, "Could not set lockscreen wallpaper", Toast.LENGTH_SHORT).show()
                    prefs.syncToLockscreen = false
                    switchSyncToLockscreen.isChecked = false
                }
            }
        }

        // Preset tiles
        val presetIds = listOf(R.id.preset1, R.id.preset2, R.id.preset3, R.id.preset4)
        presetIds.forEachIndexed { i, id ->
            val preset = PRESETS[i]
            val tile = findViewById<TextView>(id)
            tile.setTextColor(Color.parseColor(preset.text))
            tile.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.parseColor(preset.bg))
                setStroke((1f * density).toInt(), borderColor)
            }
            tile.setOnClickListener {
                prefs.followSystemTheme = false
                prefs.backgroundColor = preset.bg
                prefs.appTextColor = preset.text
                updateBgSwatch(preset.bg)
                updateTextSwatch(preset.text)
                applyBackgroundColor()
                syncLockscreenIfNeeded(parseColorSafe(preset.bg))
                setupColors()
            }
        }
    }


    private fun isSystemDarkMode(): Boolean {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun showFollowSystemThemeDialog(switchFollowSystem: MaterialSwitch) {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = "FOLLOW SYSTEM THEME"
            setTextColor(accent)
        }

        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = "This will override your current background and text colors " +
                    "to match your system's dark or light mode.\n\n" +
                    "Your custom color selections will be replaced and cannot be restored automatically."
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = "You can turn this off at any time and pick new colors manually."
            setTextColor(secondary)
        }

        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = "Enable"
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.followSystemTheme = true
                switchFollowSystem.setOnCheckedChangeListener(null)
                switchFollowSystem.isChecked = true
                setupColors()
                applySystemThemeColors()
                applyBackgroundColor()
                setupColors()
            }
        }

        dialog.show()
    }

    private fun applySystemThemeColors() {
        val preset = if (isSystemDarkMode()) PRESETS[1] else PRESETS[2]
        prefs.backgroundColor = preset.bg
        prefs.appTextColor = preset.text
    }

    private fun applySystemBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
        val isLight = isColorLight(color)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
    }

    // ── Gestures ─────────────────────────────────────────────────

    private fun setupGestures() {
        switchDoubleTap.isChecked = prefs.doubleTapToLock
        setupDoubleTapListener()

        val container = findViewById<LinearLayout>(R.id.gesturesContainer)
        val inflater = LayoutInflater.from(this)

        GESTURE_SLOTS.forEach { (fingers, dir, label) ->
            val row = inflater.inflate(R.layout.item_gesture_row, container, false)
            val labelView = row.findViewById<TextView>(R.id.gestureLabel)
            val actionView = row.findViewById<TextView>(R.id.gestureAction)

            labelView.text = label
            actionView.text = resolveGestureLabel(prefs.getGestureAction(fingers, dir))

            val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
            val textColor = if (isLight) Color.BLACK else Color.WHITE
            val accentColor = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")
            labelView.setTextColor(textColor)
            actionView.setTextColor(accentColor)

            row.setOnClickListener {
                showGestureActionPicker(fingers, dir, actionView)
            }
            container.addView(row)
        }
    }

    private fun setupDoubleTapListener() {
        switchDoubleTap.setOnCheckedChangeListener { _, checked ->
            if (checked && !isAccessibilityServiceEnabled()) {
                switchDoubleTap.isChecked = false
                showAccessibilityDialog()
            } else {
                prefs.doubleTapToLock = checked
            }
        }
    }

    private fun showAccessibilityDialog() {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.setTextColor(accent)

        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = "Double-tap to lock uses Android's Accessibility Service to lock your screen.\n\n" +
                    "On the next screen, find \"Slate\" in the list and enable it."
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = "Slate only uses this permission to lock the screen. " +
                    "No data is collected, read, or sent anywhere."
            setTextColor(secondary)
        }

        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.awaitingAccessibilityPermission = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        dialog.show()
    }

    // Kept as a thin wrapper for readability — the 4 call sites within Settings already use
    // this name. The OEM-compat logic lives in [SlateAccessibilityService.isEnabled] so the
    // home reconciliation in AppDrawerFragment.onResume can share it.
    private fun isAccessibilityServiceEnabled(): Boolean =
        SlateAccessibilityService.isEnabled(this)

    // ── Search ───────────────────────────────────────────────────

    private fun setupSearch() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        val switchEnable = findViewById<MaterialSwitch>(R.id.switchSearch)
        val rowOnHome = findViewById<View>(R.id.rowSearchOnHome)
        val switchOnHome = findViewById<MaterialSwitch>(R.id.switchSearchOnHome)
        val labelOnHomeSub = findViewById<TextView>(R.id.labelSearchOnHomeSub)
        val rowPosition = findViewById<View>(R.id.rowSearchBarPosition)
        val positionValue = findViewById<TextView>(R.id.searchBarPositionValue)

        positionValue.setTextColor(secondary)
        positionValue.text = prefs.searchBarPosition.replaceFirstChar { it.uppercaseChar() }

        // Normalize a contradictory persisted state (e.g., from a backup that predates this
        // gate, or manually-edited JSON) where Show-on-home is true while Search is off. Bring
        // the sub-option down to false so the visible state matches the gate below.
        if (!prefs.searchEnabled && prefs.showSearchBarOnHome) {
            prefs.showSearchBarOnHome = false
        }

        // Cross-row gate for the Search section. "Show on home" can't exist without Search
        // itself, so we visibly disable + grey the row when the master is off, and the sub-
        // label explains how to unlock it. Pattern matches the Sort-by-usage × Fast-scroll
        // gate added earlier — keeps the dependency self-documenting instead of relying on a
        // silent auto-enable (the old behaviour, which surprised users).
        val defaultOnHomeSub = "Keep search bar visible, closes with keyboard"
        val blockedOnHomeSub = "Turn on Search to enable"
        fun refreshSearchGates() {
            val on = prefs.searchEnabled
            switchOnHome.isEnabled = on
            rowOnHome.alpha = if (on) 1f else 0.4f
            labelOnHomeSub.text = if (on) defaultOnHomeSub else blockedOnHomeSub
            // Position sub-row only shows when BOTH the master and Show-on-home are on.
            rowPosition.visibility =
                if (on && prefs.showSearchBarOnHome) View.VISIBLE else View.GONE
        }

        switchEnable.isChecked = prefs.searchEnabled
        switchEnable.setOnCheckedChangeListener { _, checked ->
            prefs.searchEnabled = checked
            if (!checked) {
                // Master OFF cascades the sub-option off so it can't linger as a stale-but-
                // disabled "ON" state (which would also show the wrong slider in the gate).
                prefs.showSearchBarOnHome = false
                switchOnHome.setOnCheckedChangeListener(null)
                switchOnHome.isChecked = false
                attachOnHomeListener(switchOnHome, rowPosition)
            }
            refreshSearchGates()
        }

        switchOnHome.isChecked = prefs.showSearchBarOnHome
        attachOnHomeListener(switchOnHome, rowPosition)

        rowPosition.setOnClickListener {
            SlateListDialog(
                context = this,
                title = "Search bar position",
                items = listOf("Top", "Bottom"),
                bgColor = prefs.backgroundColor
            ) { _, label ->
                prefs.searchBarPosition = label.lowercase()
                positionValue.text = label
            }.show()
        }

        // Initial gate pass — handles a freshly-opened Settings.
        refreshSearchGates()
    }

    /**
     * The Show-on-home listener. Extracted so the master's cascade can detach-set-reattach
     * (silent revert pattern) without duplicating the listener body.
     *
     * Note: the old silent `if (checked && !searchEnabled) searchEnabled = true` auto-enable
     * branch is gone. With the gate in place, `switchOnHome.isEnabled = false` when the master
     * is off, so a user-initiated `checked=true` is impossible while Search is disabled.
     */
    private fun attachOnHomeListener(switchOnHome: MaterialSwitch, rowPosition: View) {
        switchOnHome.setOnCheckedChangeListener { _, checked ->
            prefs.showSearchBarOnHome = checked
            rowPosition.visibility = if (checked) View.VISIBLE else View.GONE
        }
    }

    // ── Gesture action picker ─────────────────────────────────────

    private fun showGestureActionPicker(
        fingers: Int,
        dir: Direction,
        actionView: TextView
    ) {
        val labels = GestureAction.staticActions.map { it.staticLabel } + listOf("Open app…")
        SlateListDialog(
            context = this,
            title = "Gesture Action",
            items = labels,
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            if (index == GestureAction.staticActions.size) {
                showAppPicker(fingers, dir, actionView)
            } else {
                val action = GestureAction.staticActions[index]
                prefs.setGestureAction(fingers, dir, action)
                actionView.text = action.staticLabel
            }
        }.show()
    }

    private fun showAppPicker(fingers: Int, dir: Direction, actionView: TextView) {
        val apps = AppRepository(this, prefs).getAllApps()
        SlateListDialog(
            context = this,
            title = "Choose App",
            items = apps.map { it.name },
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            val app = apps[index]
            val action = GestureAction.OpenApp(app.packageName)
            prefs.setGestureAction(fingers, dir, action)
            actionView.text = app.name
        }.show()
    }

    private fun resolveGestureLabel(action: GestureAction): String =
        when (action) {
            is GestureAction.OpenApp -> try {
                val info = packageManager.getApplicationInfo(action.packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) { action.packageName }
            else -> action.staticLabel
        }

    // ── Backup & Restore ─────────────────────────────────────────

    private fun setupBackup() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val primary = if (isLight) Color.BLACK else Color.WHITE

        findViewById<View>(R.id.rowExportBackup).apply {
            setOnClickListener { createBackupLauncher.launch("slate_backup.json") }
            (this as? LinearLayout)?.let {
                for (i in 0 until it.childCount)
                    (it.getChildAt(i) as? TextView)?.setTextColor(primary)
            }
        }
        findViewById<View>(R.id.rowImportBackup).apply {
            setOnClickListener { openBackupLauncher.launch(arrayOf("application/json", "*/*")) }
        }
    }

    private fun saveBackup(uri: Uri) {
        try {
            val json = BackupManager(prefs).toJson()
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
            Toast.makeText(this, "Backup saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadBackup(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            BackupManager(prefs).fromJson(json)
            Toast.makeText(this, "Settings restored", Toast.LENGTH_SHORT).show()
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── General ───────────────────────────────────────────────────

    private fun setupGeneral() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")
        val density = resources.displayMetrics.density

        // Sort by usage — also gates Alphabetical fast scroll (see refreshAlphaFastScrollGate
        // below). Local closures are used to keep the cross-row dependency explicit and
        // co-located with both setters.
        val switchSortUsage = findViewById<MaterialSwitch>(R.id.switchSortByUsage)
        switchSortUsage.isChecked = prefs.sortByUsage
        // Listener registered AFTER the gate closure is declared below so the listener can
        // call refreshAlphaFastScrollGate() — see below.

        // Lock orientation
        val switchLockOrientation = findViewById<MaterialSwitch>(R.id.switchLockOrientation)
        switchLockOrientation.isChecked = prefs.lockOrientation
        switchLockOrientation.setOnCheckedChangeListener { _, checked ->
            prefs.lockOrientation = checked
            requestedOrientation = if (checked)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Notification highlight + color sub-row
        val switchNotif = findViewById<MaterialSwitch>(R.id.switchNotifColor)
        val rowNotifHighlight = findViewById<View>(R.id.rowNotifHighlight)
        val notifColorSwatch = findViewById<View>(R.id.notifColorSwatch)
        val notifColorValue = findViewById<TextView>(R.id.notifColorValue)

        notifColorValue.setTextColor(secondary)

        fun updateNotifSwatch(hex: String) {
            notifColorValue.text = hex
            notifColorSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 5f * density
                setColor(parseColorSafe(hex))
                val borderColor = if (isLight) Color.parseColor("#BBBBBB") else Color.parseColor("#444444")
                setStroke((1.5f * density).toInt(), borderColor)
            }
        }

        updateNotifSwatch(prefs.notificationHighlightColor)
        rowNotifHighlight.visibility = if (prefs.notificationColorEnabled) View.VISIBLE else View.GONE

        switchNotif.isChecked = prefs.notificationColorEnabled
        setupNotifListener(switchNotif)

        rowNotifHighlight.setOnClickListener {
            ColorPickerDialog(
                context = this,
                title = "Highlight color",
                initialColor = prefs.notificationHighlightColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.notificationHighlightColor = hex
                updateNotifSwatch(hex)
            }.show()
        }

        // Status bar toggle
        val switchStatusBar = findViewById<MaterialSwitch>(R.id.switchHideStatusBar)
        switchStatusBar.isChecked = prefs.hideStatusBar
        switchStatusBar.setOnCheckedChangeListener { _, checked ->
            prefs.hideStatusBar = checked
        }

        // Homescreen view + alphabetical fast scroll sub-option
        val homescreenViewValue = findViewById<TextView>(R.id.homescreenViewValue)
        val rowAlphaFastScroll = findViewById<View>(R.id.rowAlphaFastScroll)
        val switchAlphaFastScroll = findViewById<MaterialSwitch>(R.id.switchAlphaFastScroll)
        val labelAlphaFastScrollSub = findViewById<TextView>(R.id.labelAlphaFastScrollSub)

        homescreenViewValue.setTextColor(secondary)
        homescreenViewValue.text = homescreenViewLabel(prefs.homescreenView)

        // Cross-row gate for "Alphabetical fast scroll":
        //   - HIDDEN when homescreenView != list (the feature is list-only).
        //   - VISIBLE + DISABLED + greyed when sortByUsage is on, because alphabetical fast
        //     scroll only makes sense over an alphabetical list. The sub-label changes to tell
        //     the user how to enable it. Keeping the row visible (rather than hiding it again)
        //     preserves discoverability — if we hid it on `sortByUsage`, users who enable
        //     sort-by-usage would think the feature vanished.
        //   - VISIBLE + ENABLED otherwise.
        // The pref value `alphabeticalFastScroll` is PRESERVED across both transitions so the
        // toggle re-lights at the user's previous position when they switch back to alphabetical
        // sort. Matches how `notificationHighlightColor` persists when highlight is off.
        val defaultSubLabel = "Side index to jump letters (sorts A–Z)"
        val blockedSubLabel = "Turn off Sort by most used to enable"
        fun refreshAlphaFastScrollGate() {
            val isList = prefs.homescreenView == PreferencesManager.VIEW_LIST
            rowAlphaFastScroll.visibility = if (isList) View.VISIBLE else View.GONE
            if (!isList) return
            val blocked = prefs.sortByUsage
            switchAlphaFastScroll.isEnabled = !blocked
            rowAlphaFastScroll.alpha = if (blocked) 0.4f else 1f
            labelAlphaFastScrollSub.text = if (blocked) blockedSubLabel else defaultSubLabel
        }

        findViewById<View>(R.id.rowHomescreenView).setOnClickListener {
            SlateListDialog(
                context = this,
                title = "Homescreen view",
                items = listOf("Flow", "Minimal List"),
                bgColor = prefs.backgroundColor
            ) { index, label ->
                prefs.homescreenView =
                    if (index == 0) PreferencesManager.VIEW_FLOW else PreferencesManager.VIEW_LIST
                homescreenViewValue.text = label
                refreshAlphaFastScrollGate()
                applyHomescreenViewToTextSize()
            }.show()
        }

        switchAlphaFastScroll.isChecked = prefs.alphabeticalFastScroll
        switchAlphaFastScroll.setOnCheckedChangeListener { _, checked ->
            prefs.alphabeticalFastScroll = checked
        }

        // Wire the Sort-by-usage listener here (the switch was declared earlier; we register the
        // listener now because it must also refresh the fast-scroll gate, whose closure lives in
        // this scope).
        switchSortUsage.setOnCheckedChangeListener { _, checked ->
            prefs.sortByUsage = checked
            refreshAlphaFastScrollGate()
        }

        // Initial state for the gate — covers a fresh open of Settings.
        refreshAlphaFastScrollGate()

        // Default launcher row
        findViewById<View>(R.id.rowDefaultLauncher).setOnClickListener {
            requestDefaultLauncher()
        }
    }

    private fun homescreenViewLabel(mode: String): String = when (mode) {
        PreferencesManager.VIEW_LIST -> "Minimal List"
        else -> "Flow"
    }

    private fun setupNotifListener(switchNotif: MaterialSwitch) {
        val rowNotifHighlight = findViewById<View>(R.id.rowNotifHighlight)
        switchNotif.setOnCheckedChangeListener { _, checked ->
            if (checked && !isNotificationListenerEnabled()) {
                switchNotif.isChecked = false
                showNotificationDialog()
            } else {
                prefs.notificationColorEnabled = checked
                rowNotifHighlight.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showNotificationDialog() {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = "NOTIFICATION ACCESS"
            setTextColor(accent)
        }

        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = "Notification highlight reads your active notifications to tint app names on the home screen.\n\n" +
                    "On the next screen, find \"Slate\" and enable it."
            setTextColor(primary)
        }

        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.apply {
            text = "Slate only reads which apps have notifications. " +
                    "Message content is never accessed, stored, or sent anywhere."
            setTextColor(secondary)
        }

        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.awaitingNotificationPermission = true
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }

        dialog.show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, SlateNotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun requestDefaultLauncher() {
        if (isAlreadyDefaultLauncher()) {
            SlateListDialog(
                context = this,
                title = "Already the default launcher",
                items = listOf("Slate is your home. Thank you for using it.", "OK"),
                bgColor = prefs.backgroundColor
            ) { _, _ -> }.show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                requestRoleLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                )
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun isAlreadyDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )
        return info?.activityInfo?.packageName == packageName
    }

    // ── Quick toggles strip ───────────────────────────────────────

    private fun setupQuickStrip() {
        val switch = findViewById<MaterialSwitch>(R.id.switchQuickStrip)
        val rowPosition = findViewById<View>(R.id.rowQuickStripPosition)
        val positionValue = findViewById<TextView>(R.id.quickStripPositionValue)
        val rowChooseWidgets = findViewById<View>(R.id.rowChooseWidgets)
        val labelChooseValue = findViewById<TextView>(R.id.labelChooseWidgetsValue)
        val rowArrangeWidgets = findViewById<View>(R.id.rowArrangeWidgets)
        val rowDirectCall = findViewById<View>(R.id.rowDirectCall)
        val switchDirectCall = findViewById<MaterialSwitch>(R.id.switchDirectCall)
        val rowDirectCallTrigger = findViewById<View>(R.id.rowDirectCallTrigger)
        val directCallTriggerValue = findViewById<TextView>(R.id.directCallTriggerValue)
        val rowDivider = findViewById<View>(R.id.rowQuickStripDivider)
        val switchDivider = findViewById<MaterialSwitch>(R.id.switchQuickStripDivider)
        val previewHeader = findViewById<TextView>(R.id.widgetPreviewHeader)
        val previewLayout = findViewById<FlexboxLayout>(R.id.widgetPreview)
        val rowFont = findViewById<View>(R.id.rowWidgetFont)
        val fontValueLabel = findViewById<TextView>(R.id.widgetFontValue)
        val rowWeight = findViewById<View>(R.id.rowWidgetWeight)
        val weightValueLabel = findViewById<TextView>(R.id.widgetWeightValue)
        val rowAlignment = findViewById<View>(R.id.rowWidgetAlignment)
        val alignmentValueLabel = findViewById<TextView>(R.id.widgetAlignmentValue)
        val rowTextSize = findViewById<View>(R.id.rowWidgetTextSize)
        val sbTextSize = findViewById<SeekBar>(R.id.widgetTextSizeSeekBar)
        val lbTextSize = findViewById<TextView>(R.id.widgetTextSizeLabel)
        val rowLineGap = findViewById<View>(R.id.rowWidgetLineGap)
        val sbLineGap = findViewById<SeekBar>(R.id.widgetLineGapSeekBar)
        val lbLineGap = findViewById<TextView>(R.id.widgetLineGapLabel)
        val rowWordGap = findViewById<View>(R.id.rowWidgetWordGap)
        val sbWordGap = findViewById<SeekBar>(R.id.widgetWordGapSeekBar)
        val lbWordGap = findViewById<TextView>(R.id.widgetWordGapLabel)

        fun isLight() = isColorLight(parseColorSafe(prefs.backgroundColor))
        fun secondary() =
            if (isLight()) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        fun refreshChooseValueLabel() {
            val count = prefs.quickStripWidgets.size
            labelChooseValue.text = when (count) {
                0 -> "None selected"
                1 -> "1 widget enabled"
                else -> "$count widgets enabled"
            }
            labelChooseValue.setTextColor(secondary())
        }

        fun refreshPositionValue() {
            positionValue.text = if (prefs.quickStripPosition == "top") "Top" else "Bottom"
            positionValue.setTextColor(secondary())
        }

        fun applyVisibility() {
            val on = prefs.quickStripEnabled
            rowPosition.visibility = if (on) View.VISIBLE else View.GONE
            rowChooseWidgets.visibility = if (on) View.VISIBLE else View.GONE
            // Arrange row hides when there's nothing to arrange: 0 or 1 widget enabled.
            rowArrangeWidgets.visibility =
                if (on && prefs.quickStripWidgets.size >= 2) View.VISIBLE else View.GONE
            // Direct call sub-row mirrors the master. The Trigger sub-sub-row appears only when
            // BOTH master Quick toggles AND Direct call are on — showing the trigger picker
            // while Direct call is off would be meaningless.
            rowDirectCall.visibility = if (on) View.VISIBLE else View.GONE
            rowDirectCallTrigger.visibility =
                if (on && prefs.directCallEnabled) View.VISIBLE else View.GONE
            // Divider toggle mirrors the master gate — the divider can't exist without a strip.
            rowDivider.visibility = if (on) View.VISIBLE else View.GONE
            // Preview + typography controls (pickers and sliders) mirror the master gate — only
            // meaningful when the strip is going to render at all.
            previewHeader.visibility = if (on) View.VISIBLE else View.GONE
            previewLayout.visibility = if (on) View.VISIBLE else View.GONE
            rowFont.visibility       = if (on) View.VISIBLE else View.GONE
            rowWeight.visibility     = if (on) View.VISIBLE else View.GONE
            rowAlignment.visibility  = if (on) View.VISIBLE else View.GONE
            rowTextSize.visibility = if (on) View.VISIBLE else View.GONE
            rowLineGap.visibility  = if (on) View.VISIBLE else View.GONE
            rowWordGap.visibility  = if (on) View.VISIBLE else View.GONE
        }

        refreshChooseValueLabel()
        refreshPositionValue()
        applyVisibility()

        switch.setOnCheckedChangeListener(null)
        switch.isChecked = prefs.quickStripEnabled
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.quickStripEnabled = checked
            applyVisibility()
        }

        rowPosition.setOnClickListener {
            SlateListDialog(
                context = this,
                title = "Position",
                items = listOf("Top", "Bottom"),
                bgColor = prefs.backgroundColor
            ) { _, label ->
                prefs.quickStripPosition = label.lowercase()
                refreshPositionValue()
            }.show()
        }

        rowChooseWidgets.setOnClickListener {
            WidgetPickerDialog(
                context = this,
                prefs = prefs,
                onChanged = {
                    // Picker can change both selection and count → refresh dependent UI.
                    refreshChooseValueLabel()
                    applyVisibility()
                },
                onAddShortcut = { type -> launchContactPickerFor(type) }
            ).show()
        }

        rowArrangeWidgets.setOnClickListener {
            com.slate.launcher.widgets.WidgetArrangeDialog(
                context = this,
                prefs = prefs,
                onChanged = { /* order changes; counts and visibility don't */ }
            ).show()
        }

        // ── Direct call ────────────────────────────────────────────────
        fun directCallTriggerLabel(value: String) = when (value) {
            "longPress" -> "Long press"
            else -> "Tap"
        }
        fun refreshDirectCallTriggerValue() {
            directCallTriggerValue.text = directCallTriggerLabel(prefs.directCallTrigger)
            directCallTriggerValue.setTextColor(secondary())
        }

        switchDirectCall.isChecked = prefs.directCallEnabled
        refreshDirectCallTriggerValue()
        attachDirectCallListener(switchDirectCall)

        rowDirectCallTrigger.setOnClickListener {
            SlateListDialog(
                context = this,
                title = "Trigger",
                items = listOf("Tap", "Long press"),
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.directCallTrigger = if (index == 0) "tap" else "longPress"
                refreshDirectCallTriggerValue()
            }.show()
        }

        switchDivider.setOnCheckedChangeListener(null)
        switchDivider.isChecked = prefs.quickStripDividerEnabled
        switchDivider.setOnCheckedChangeListener { _, checked ->
            prefs.quickStripDividerEnabled = checked
            // Home re-renders on next onResume via applyChromeLayout(); no extra trigger needed.
        }

        // ── Live preview + picker rows ──────────────────────────────────
        // The preview is a small FlexboxLayout populated with fixed sample labels so the
        // rendering is deterministic for every user. It mirrors the home strip's styling via
        // [Typography.applyWidgetStyle] — single source of truth for "how a widget looks".
        fun refreshPreview() {
            val bg = parseColorSafe(prefs.backgroundColor)
            previewLayout.setBackgroundColor(bg)
            previewLayout.flexDirection = FlexDirection.ROW
            previewLayout.flexWrap = FlexWrap.WRAP
            previewLayout.alignItems = AlignItems.CENTER
            previewLayout.justifyContent = when (prefs.widgetTextAlignment) {
                "left" -> JustifyContent.FLEX_START
                "right" -> JustifyContent.FLEX_END
                else -> JustifyContent.CENTER
            }
            previewLayout.removeAllViews()
            val density = resources.displayMetrics.density
            val textColor = parseColorSafe(
                prefs.appTextColor,
                if (isColorLight(bg)) Color.BLACK else Color.WHITE
            )
            PREVIEW_SAMPLE_LABELS.forEach { sample ->
                previewLayout.addView(TextView(this).apply {
                    text = sample
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    isClickable = false
                    isFocusable = false
                    Typography.applyWidgetStyle(this, prefs, this@SettingsActivity, density)
                })
            }
        }

        // Resolve the persisted (family, weight, alignment) values to their human-readable row
        // labels. `widgetFontFamily=""` and `widgetFontWeight=0` are sentinels meaning "use theme
        // default" — both render as "Default" in the row value.
        fun widgetFontDisplayName(key: String): String = when {
            key.isEmpty() -> "Default"
            key.startsWith("/") -> File(key).nameWithoutExtension
            else -> FONTS.find { it.key == key }?.displayName ?: "Default"
        }
        fun widgetWeightDisplayName(weight: Int): String =
            if (weight == 0) "Default"
            else WEIGHTS.find { it.first == weight }?.second ?: "Default"

        fun refreshFontValueLabel() {
            fontValueLabel.text = widgetFontDisplayName(prefs.widgetFontFamily)
            fontValueLabel.setTextColor(secondary())
        }
        fun refreshWeightValueLabel() {
            weightValueLabel.text = widgetWeightDisplayName(prefs.widgetFontWeight)
            weightValueLabel.setTextColor(secondary())
        }
        fun refreshAlignmentValueLabel() {
            alignmentValueLabel.text = prefs.widgetTextAlignment.replaceFirstChar { it.uppercaseChar() }
            alignmentValueLabel.setTextColor(secondary())
        }

        refreshFontValueLabel()
        refreshWeightValueLabel()
        refreshAlignmentValueLabel()

        // Font picker — "Default" is index 0; subsequent entries map 1-to-1 onto FONTS.
        rowFont.setOnClickListener {
            val items = listOf("Default") + FONTS.map { it.displayName }
            SlateListDialog(
                context = this,
                title = "Font",
                items = items,
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.widgetFontFamily =
                    if (index == 0) "" else FONTS[index - 1].key
                refreshFontValueLabel()
                refreshPreview()
            }.show()
        }

        // Weight picker — same Default-prepend pattern as the font picker.
        rowWeight.setOnClickListener {
            val items = listOf("Default") + WEIGHTS.map { it.second }
            SlateListDialog(
                context = this,
                title = "Weight",
                items = items,
                bgColor = prefs.backgroundColor
            ) { index, _ ->
                prefs.widgetFontWeight =
                    if (index == 0) 0 else WEIGHTS[index - 1].first
                refreshWeightValueLabel()
                refreshPreview()
            }.show()
        }

        // Alignment picker — mirrors the apps' Alignment dialog (left/center/right).
        rowAlignment.setOnClickListener {
            SlateListDialog(
                context = this,
                title = "Alignment",
                items = listOf("Left", "Center", "Right"),
                bgColor = prefs.backgroundColor
            ) { _, label ->
                prefs.widgetTextAlignment = label.lowercase()
                refreshAlignmentValueLabel()
                refreshPreview()
            }.show()
        }

        // Widget typography sliders. Out-of-range stored values (e.g., from an older backup with
        // different range bounds) fall back to the default index — pref on disk stays untouched
        // until the user moves the slider. Labels use the same "{value}{unit}" idiom as the
        // existing typography sliders (sp for font, dp for padding).
        fun initSlider(
            seekBar: SeekBar,
            label: TextView,
            values: List<Int>,
            current: Int,
            default: Int,
            unit: String
        ) {
            seekBar.max = values.size - 1
            seekBar.progress = values.indexOf(current)
                .takeIf { it >= 0 } ?: values.indexOf(default).coerceAtLeast(0)
            label.text = "$current$unit"
        }

        initSlider(
            sbTextSize, lbTextSize, WIDGET_TEXT_SIZES,
            prefs.widgetTextSize, PreferencesManager.DEFAULT_WIDGET_TEXT_SIZE, "sp"
        )
        initSlider(
            sbLineGap, lbLineGap, WIDGET_LINE_GAPS,
            prefs.widgetLineGap, PreferencesManager.DEFAULT_WIDGET_LINE_GAP, "dp"
        )
        initSlider(
            sbWordGap, lbWordGap, WIDGET_WORD_GAPS,
            prefs.widgetWordGap, PreferencesManager.DEFAULT_WIDGET_WORD_GAP, "dp"
        )

        sbTextSize.setOnSeekBarChangeListener(seekBarListener { p ->
            val v = WIDGET_TEXT_SIZES[p]; prefs.widgetTextSize = v; lbTextSize.text = "${v}sp"
            refreshPreview()
        })
        sbLineGap.setOnSeekBarChangeListener(seekBarListener { p ->
            val v = WIDGET_LINE_GAPS[p]; prefs.widgetLineGap = v; lbLineGap.text = "${v}dp"
            refreshPreview()
        })
        sbWordGap.setOnSeekBarChangeListener(seekBarListener { p ->
            val v = WIDGET_WORD_GAPS[p]; prefs.widgetWordGap = v; lbWordGap.text = "${v}dp"
            refreshPreview()
        })

        // Initial preview population so the user sees a rendered strip the moment the section
        // becomes visible — no need to touch a control first.
        refreshPreview()
    }

    /**
     * Wire the Direct-call switch. Toggling ON requests CALL_PHONE if not already granted; the
     * launcher's callback owns the final pref / switch state. Detach-set-reattach pattern in
     * the callback prevents recursion on a silent revert.
     *
     * Toggling OFF writes the pref to false immediately — no permission interaction. Also
     * hides the Trigger sub-row.
     */
    private fun attachDirectCallListener(switchDirectCall: MaterialSwitch) {
        switchDirectCall.setOnCheckedChangeListener { _, checked ->
            val rowTrigger = findViewById<View>(R.id.rowDirectCallTrigger)
            if (!checked) {
                prefs.directCallEnabled = false
                rowTrigger.visibility = View.GONE
                return@setOnCheckedChangeListener
            }
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CALL_PHONE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                prefs.directCallEnabled = true
                rowTrigger.visibility = View.VISIBLE
            } else {
                // Don't write the pref yet — the launcher callback owns the final state. The
                // Trigger row stays hidden until the grant succeeds.
                requestCallPhoneLauncher.launch(android.Manifest.permission.CALL_PHONE)
            }
        }
    }

    /** Launch the system contact picker pre-filtered on Phone rows. */
    private fun launchContactPickerFor(type: ContactShortcut.Type) {
        pendingShortcutType = type
        val intent = Intent(
            Intent.ACTION_PICK,
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        )
        runCatching { pickContactLauncher.launch(intent) }
            .onFailure {
                pendingShortcutType = null
                Toast.makeText(this, "No contacts app available", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Resolve the URI returned by `Intent(ACTION_PICK, Phone.CONTENT_URI)` into a phone number and
     * display name, then persist a new [ContactShortcut] and auto-enable it in the strip. The URI
     * carries a temporary read grant, so this query works without `READ_CONTACTS`.
     */
    private fun onContactPicked(uri: android.net.Uri) {
        val type = pendingShortcutType ?: return
        pendingShortcutType = null
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
        )
        val (number, displayName, lookupKey) = runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@runCatching Triple<String?, String?, String?>(null, null, null)
                Triple(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2)
                )
            } ?: Triple<String?, String?, String?>(null, null, null)
        }.getOrDefault(Triple(null, null, null))

        if (number.isNullOrBlank() || displayName.isNullOrBlank() || lookupKey.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't read the selected contact", Toast.LENGTH_SHORT).show()
            return
        }

        val shortcut = ContactShortcut(
            type = type,
            // The contact lookup_key is stable across edits, merges, syncs; we use it as the
            // widget's identity rather than the volatile row _ID.
            lookupUri = lookupKey,
            displayName = displayName,
            number = number
        )
        ContactShortcutStore.add(prefs, shortcut)

        // Auto-enable the new shortcut in the strip so the user immediately sees it.
        val current = prefs.quickStripWidgets.toMutableList()
        if (!current.contains(shortcut.id)) {
            current.add(shortcut.id)
            prefs.quickStripWidgets = current
        }
        WidgetPickerDialog.refreshActive()
    }

    // ── Hidden apps security ──────────────────────────────────────

    private fun setupSecurity() {
        val pinManager = PinManager(prefs)
        val switchMaster = findViewById<MaterialSwitch>(R.id.switchHiddenAppsSecurity)
        val switchBio = findViewById<MaterialSwitch>(R.id.switchBiometric)
        val rowBio = findViewById<View>(R.id.rowBiometric)
        val rowChangePin = findViewById<View>(R.id.rowChangePin)
        val labelBioSub = findViewById<TextView>(R.id.labelBiometricSub)

        // Mutually-recursive listener setup so we can re-attach after a silent revert.
        var attachMaster: () -> Unit = {}
        var attachBio: () -> Unit = {}

        fun setMasterSilently(value: Boolean) {
            switchMaster.setOnCheckedChangeListener(null)
            switchMaster.isChecked = value
            attachMaster()
        }
        fun setBioSilently(value: Boolean) {
            switchBio.setOnCheckedChangeListener(null)
            switchBio.isChecked = value
            attachBio()
        }

        // applyVisibility runs after setBioSilently is declared because its reconcile branch
        // (below) invokes setBioSilently. Kotlin local functions cannot forward-reference
        // each other across blocks.
        fun applyVisibility() {
            val active = prefs.hiddenAppsSecurityEnabled && pinManager.hasPin()
            rowBio.visibility = if (active) View.VISIBLE else View.GONE
            rowChangePin.visibility = if (active) View.VISIBLE else View.GONE
            val bioAvailable = AuthGate.canUseBiometric(this)
            // Reconcile stale pref: if the user removed their biometric enrollment from
            // Android system Settings while we were elsewhere, `prefs.biometricEnabled` is
            // still true but `canUseBiometric` now returns false. Without this flip the
            // switch renders as a confusing checked-but-greyed-out state. Functionally
            // AuthGate.authenticate already falls back to PIN — this fixes the UI to tell
            // the truth.
            if (prefs.biometricEnabled && !bioAvailable) {
                prefs.biometricEnabled = false
                setBioSilently(false)
            }
            switchBio.isEnabled = bioAvailable
            labelBioSub.text = if (bioAvailable) {
                "Unlock with fingerprint or face; PIN remains as fallback"
            } else {
                "No biometric enrolled on this device"
            }
        }

        // Expose the reconcile entry-point for syncPermissionToggles. The lambda closes over
        // every local in this setupSecurity invocation; if the activity is recreated (e.g.,
        // after a backup restore), setupSecurity runs again and reassigns this field with a
        // fresh closure pointing at the new locals.
        biometricReconcile = { applyVisibility() }

        attachMaster = {
            switchMaster.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    PinFlow.setupNew(
                        activity = this,
                        prefs = prefs,
                        pinManager = pinManager,
                        onComplete = {
                            prefs.hiddenAppsSecurityEnabled = true
                            setMasterSilently(true)
                            applyVisibility()
                        },
                        onCancel = { setMasterSilently(false) }
                    )
                } else {
                    PinFlow.verifyExisting(
                        activity = this,
                        prefs = prefs,
                        pinManager = pinManager,
                        title = "Disable Lock",
                        onSuccess = {
                            prefs.hiddenAppsSecurityEnabled = false
                            prefs.biometricEnabled = false
                            pinManager.clear()
                            setBioSilently(false)
                            setMasterSilently(false)
                            applyVisibility()
                        },
                        onCancel = { setMasterSilently(true) }
                    )
                }
            }
        }

        attachBio = {
            switchBio.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!AuthGate.canUseBiometric(this)) {
                        Toast.makeText(this, "No biometric enrolled on this device", Toast.LENGTH_SHORT).show()
                        setBioSilently(false)
                        return@setOnCheckedChangeListener
                    }
                    // Require PIN before enabling biometric. Without this, anyone holding the
                    // unlocked phone could add their own biometric and gain ongoing access.
                    PinFlow.verifyExisting(
                        activity = this,
                        prefs = prefs,
                        pinManager = pinManager,
                        title = "Enable biometric",
                        onSuccess = {
                            AuthGate.verifyBiometric(
                                activity = this,
                                title = "Enable biometric",
                                subtitle = "Confirm biometric to enable",
                                onSuccess = {
                                    prefs.biometricEnabled = true
                                    setBioSilently(true)
                                },
                                onCancel = { setBioSilently(false) }
                            )
                        },
                        onCancel = { setBioSilently(false) }
                    )
                } else {
                    prefs.biometricEnabled = false
                }
            }
        }

        // Initial state — sync UI with prefs and attach listeners
        switchMaster.setOnCheckedChangeListener(null)
        switchMaster.isChecked = prefs.hiddenAppsSecurityEnabled && pinManager.hasPin()
        switchBio.setOnCheckedChangeListener(null)
        switchBio.isChecked = prefs.biometricEnabled
        applyVisibility()
        attachMaster()
        attachBio()

        rowChangePin.setOnClickListener {
            PinFlow.changePin(
                activity = this,
                prefs = prefs,
                pinManager = pinManager,
                onComplete = {
                    Toast.makeText(this, "PIN changed", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // ── Battery restriction banner ────────────────────────────────

    private fun isBackgroundRestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val notIgnoring = pm?.isIgnoringBatteryOptimizations(packageName) == false
        val explicitlyRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isBackgroundRestricted == true
        } else false
        return notIgnoring || explicitlyRestricted
    }

    private fun shouldShowBatteryBanner(): Boolean {
        if (prefs.batteryBannerDismissedPermanently) return false
        if (!prefs.doubleTapToLock && !prefs.notificationColorEnabled) return false
        return isBackgroundRestricted()
    }

    private fun setupBatteryBanner() {
        updateBatteryBanner()

        val btnUnrestrict = findViewById<android.widget.TextView>(R.id.btnUnrestrict)
        val btnDismiss = findViewById<android.widget.TextView>(R.id.btnBatteryDismiss)
        val banner = findViewById<View>(R.id.batteryBanner)

        btnUnrestrict?.setOnClickListener { requestBatteryExemption() }

        btnDismiss?.setOnClickListener {
            showBatteryDismissDialog(onDismissOnce = {
                banner?.visibility = View.GONE
            })
        }

        // Style "FIX THIS" button background
        val density = resources.displayMetrics.density
        btnUnrestrict?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4f * density
            setColor(Color.parseColor("#33FFFFFF"))
        }
    }

    private fun updateBatteryBanner() {
        val banner = findViewById<View>(R.id.batteryBanner) ?: return

        if (!shouldShowBatteryBanner()) {
            banner.visibility = View.GONE
            return
        }

        banner.visibility = View.VISIBLE

        val oemExtra = when {
            Build.MANUFACTURER.lowercase().let {
                it.contains("xiaomi") || it.contains("redmi") || it.contains("huawei") ||
                it.contains("honor") || it.contains("samsung") || it.contains("oppo") ||
                it.contains("vivo") || it.contains("oneplus")
            } -> "\n\nOn your device, you may also need to enable autostart or disable battery optimization in your device's battery settings."
            else -> ""
        }

        findViewById<android.widget.TextView>(R.id.batteryBannerMessage)?.text =
            "Notification highlight and screen lock may stop working.$oemExtra"
    }

    private fun requestBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            batteryExemptLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                batteryExemptLauncher.launch(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (_: Exception) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun showBatteryDismissDialog(onDismissOnce: () -> Unit) {
        val dialog = Dialog(this, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#CC5500") else Color.parseColor("#FF8C42")
        val density = resources.displayMetrics.density

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<android.widget.TextView>(R.id.dialogTitle)?.apply {
            text = "HIDE WARNING"
            setTextColor(accent)
        }

        dialog.findViewById<android.widget.TextView>(R.id.dialogBody)?.apply {
            text = "Would you like to permanently hide this battery restriction warning?"
            setTextColor(primary)
        }

        dialog.findViewById<android.widget.TextView>(R.id.dialogPrivacy)?.apply {
            text = "Features may still stop working if background activity remains restricted. " +
                    "You can always fix this manually in your device's battery settings."
            setTextColor(secondary)
        }

        dialog.findViewById<android.widget.TextView>(R.id.btnCancel)?.apply {
            text = "Dismiss once"
            setTextColor(secondary)
            setOnClickListener {
                dialog.dismiss()
                onDismissOnce()
            }
        }

        dialog.findViewById<android.widget.TextView>(R.id.btnContinue)?.apply {
            text = "Don't remind again"
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.batteryBannerDismissedPermanently = true
                updateBatteryBanner()
            }
        }

        dialog.show()
    }

    // ── About ─────────────────────────────────────────────────────

    private fun setupAbout() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "—" }
        findViewById<TextView>(R.id.labelAppVersion)?.apply {
            text = "v$versionName"
            setTextColor(secondary)
        }

        findViewById<View>(R.id.rowGithub)?.setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/roufsyed/Slate-Minimal-Launcher"))
            )
        }

        findViewById<View>(R.id.rowGuidedTour)?.setOnClickListener {
            // Manual re-run resets to step 0 and shows immediately.
            GuidedTourManager.show(this, prefs)
        }
    }
}
