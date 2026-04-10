package com.slate.launcher

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
import com.google.android.material.materialswitch.MaterialSwitch
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var switchDoubleTap: MaterialSwitch
    private lateinit var createBackupLauncher: ActivityResultLauncher<String>
    private lateinit var openBackupLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var importFontLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>
    private var awaitingAccessibilityPermission = false
    private var awaitingNotificationPermission = false

    companion object {
        private val MIN_SIZES     = (8..24).toList()
        private val MAX_SIZES     = (20..60).toList()
        private val LINE_SPACINGS = (0..24).toList()
        private val WORD_SPACINGS = (2..28).toList()

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
        setupAbout()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onResume() {
        super.onResume()
        updateDefaultLauncherRow()
        syncPermissionToggles()
    }

    private fun syncPermissionToggles() {
        // Double tap to lock: if permission was revoked externally, turn off
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        if (prefs.doubleTapToLock && !accessibilityEnabled) {
            prefs.doubleTapToLock = false
            switchDoubleTap.setOnCheckedChangeListener(null)
            switchDoubleTap.isChecked = false
            setupDoubleTapListener()
        } else if (awaitingAccessibilityPermission && accessibilityEnabled) {
            // Only auto-enable when returning from the permission grant flow
            prefs.doubleTapToLock = true
            switchDoubleTap.setOnCheckedChangeListener(null)
            switchDoubleTap.isChecked = true
            setupDoubleTapListener()
        }
        awaitingAccessibilityPermission = false

        // Notification highlight: if permission was revoked externally, turn off
        val notifEnabled = isNotificationListenerEnabled()
        val switchNotif = findViewById<MaterialSwitch>(R.id.switchNotifColor) ?: return
        if (prefs.notificationColorEnabled && !notifEnabled) {
            prefs.notificationColorEnabled = false
            switchNotif.setOnCheckedChangeListener(null)
            switchNotif.isChecked = false
            setupNotifListener(switchNotif)
            findViewById<View>(R.id.rowNotifHighlight).visibility = View.GONE
        } else if (awaitingNotificationPermission && notifEnabled) {
            // Only auto-enable when returning from the permission grant flow
            prefs.notificationColorEnabled = true
            switchNotif.setOnCheckedChangeListener(null)
            switchNotif.isChecked = true
            setupNotifListener(switchNotif)
            findViewById<View>(R.id.rowNotifHighlight).visibility = View.VISIBLE
        }
        awaitingNotificationPermission = false
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
               R.id.divider3, R.id.divider4, R.id.divider5).forEach { id ->
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
            findViewById(R.id.switchSyncToLockscreen)
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

        minSeekBar.max = MIN_SIZES.size - 1
        minSeekBar.progress = MIN_SIZES.indexOf(prefs.minFontSize)
            .takeIf { it >= 0 } ?: MIN_SIZES.indexOf(PreferencesManager.DEFAULT_MIN_FONT_SIZE).coerceAtLeast(0)
        minLabel.text = "${prefs.minFontSize}sp"

        maxSeekBar.max = MAX_SIZES.size - 1
        maxSeekBar.progress = MAX_SIZES.indexOf(prefs.maxFontSize)
            .takeIf { it >= 0 } ?: MAX_SIZES.indexOf(PreferencesManager.DEFAULT_MAX_FONT_SIZE).coerceAtLeast(0)
        maxLabel.text = "${prefs.maxFontSize}sp"

        minSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            prefs.minFontSize = MIN_SIZES[p]; minLabel.text = "${MIN_SIZES[p]}sp"
        })
        maxSeekBar.setOnSeekBarChangeListener(seekBarListener { p ->
            prefs.maxFontSize = MAX_SIZES[p]; maxLabel.text = "${MAX_SIZES[p]}sp"
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
                prefs.backgroundColor = hex
                updateBgSwatch(hex)
                applyBackgroundColor()
                syncLockscreenIfNeeded(parseColorSafe(hex))
                setupColors()   // re-run to refresh border colors for new theme
            }.show()
        }

        fun openTextPicker() {
            ColorPickerDialog(
                context = this,
                title = "App Text",
                initialColor = prefs.appTextColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.appTextColor = hex
                updateTextSwatch(hex)
            }.show()
        }

        findViewById<View>(R.id.rowBgColor).setOnClickListener { openBgPicker() }
        findViewById<View>(R.id.rowTextColor).setOnClickListener { openTextPicker() }

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
                awaitingAccessibilityPermission = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        dialog.show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val cn = ComponentName(this, SlateAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(cn.flattenToString())
    }

    // ── Search ───────────────────────────────────────────────────

    private fun setupSearch() {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")

        val switchEnable = findViewById<MaterialSwitch>(R.id.switchSearch)
        switchEnable.isChecked = prefs.searchEnabled
        switchEnable.setOnCheckedChangeListener { _, checked ->
            prefs.searchEnabled = checked
            if (!checked) {
                prefs.showSearchBarOnHome = false
                findViewById<MaterialSwitch>(R.id.switchSearchOnHome).isChecked = false
                findViewById<View>(R.id.rowSearchBarPosition).visibility = View.GONE
            }
        }

        val rowPosition = findViewById<View>(R.id.rowSearchBarPosition)
        val positionValue = findViewById<TextView>(R.id.searchBarPositionValue)
        positionValue.setTextColor(secondary)
        positionValue.text = prefs.searchBarPosition.replaceFirstChar { it.uppercaseChar() }
        rowPosition.visibility = if (prefs.showSearchBarOnHome) View.VISIBLE else View.GONE

        val switchOnHome = findViewById<MaterialSwitch>(R.id.switchSearchOnHome)
        switchOnHome.isChecked = prefs.showSearchBarOnHome
        switchOnHome.setOnCheckedChangeListener { _, checked ->
            prefs.showSearchBarOnHome = checked
            rowPosition.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked && !prefs.searchEnabled) {
                prefs.searchEnabled = true
                switchEnable.isChecked = true
            }
        }

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

        // Sort by usage
        val switchSortUsage = findViewById<MaterialSwitch>(R.id.switchSortByUsage)
        switchSortUsage.isChecked = prefs.sortByUsage
        switchSortUsage.setOnCheckedChangeListener { _, checked ->
            prefs.sortByUsage = checked
        }

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

        // Default launcher row
        findViewById<View>(R.id.rowDefaultLauncher).setOnClickListener {
            requestDefaultLauncher()
        }
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
                awaitingNotificationPermission = true
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
                items = listOf("Slate is your home. Thank you for using it."),
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
    }
}
