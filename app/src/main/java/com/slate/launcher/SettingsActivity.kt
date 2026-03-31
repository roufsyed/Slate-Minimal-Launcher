package com.slate.launcher

import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
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
import com.slate.launcher.MultiFingerGestureDetector.Direction
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var switchDoubleTap: MaterialSwitch
    private lateinit var createBackupLauncher: ActivityResultLauncher<String>
    private lateinit var openBackupLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var importFontLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>

    companion object {
        private const val REQUEST_DEVICE_ADMIN = 100
        private val MIN_SIZES     = (8..24).toList()
        private val MAX_SIZES     = (20..60).toList()
        private val LINE_SPACINGS = (0..24).toList()
        private val WORD_SPACINGS = (2..28).toList()

        private val GESTURE_SLOTS = listOf(
            Triple(1, Direction.UP,    "1 finger  ↑"),
            Triple(1, Direction.DOWN,  "1 finger  ↓"),
            Triple(1, Direction.LEFT,  "1 finger  ←"),
            Triple(1, Direction.RIGHT, "1 finger  →"),
            Triple(2, Direction.UP,    "2 fingers ↑"),
            Triple(2, Direction.DOWN,  "2 fingers ↓"),
            Triple(2, Direction.LEFT,  "2 fingers ←"),
            Triple(2, Direction.RIGHT, "2 fingers →"),
            Triple(3, Direction.UP,    "3 fingers ↑"),
            Triple(3, Direction.DOWN,  "3 fingers ↓"),
            Triple(3, Direction.LEFT,  "3 fingers ←"),
            Triple(3, Direction.RIGHT, "3 fingers →"),
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
        setupGestures()
        setupSearch()
        setupColors()
        setupBackup()
        setupGeneral()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Background ───────────────────────────────────────────────

    private fun applyBackgroundColor() {
        val color = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(color)
        val drawable = ColorDrawable(color)

        window.setBackgroundDrawable(drawable)
        findViewById<View>(android.R.id.content).setBackgroundColor(color)
        supportActionBar?.setBackgroundDrawable(drawable)
        applySystemBarColors(color)

        // Derive text colors from background brightness
        val primary   = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#AAAAAA")
        val accent    = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")

        val title = SpannableString("Settings").apply {
            setSpan(ForegroundColorSpan(primary), 0, length, 0)
        }
        supportActionBar?.title = title

        // Paint section labels with accent, everything else primary/secondary
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        applyTextColors(root, primary, secondary, accent)

        // Color section dividers
        val dividerColor = if (isLight) Color.parseColor("#22000000") else Color.parseColor("#22FFFFFF")
        listOf(R.id.divider1, R.id.divider1b, R.id.divider2, R.id.divider3, R.id.divider4, R.id.divider6).forEach { id ->
            findViewById<View>(id)?.setBackgroundColor(dividerColor)
        }

        // Style toggles to match theme
        applySwitchColors(
            switchDoubleTap,
            findViewById(R.id.switchSearch),
            findViewById(R.id.switchSearchOnHome),
            findViewById(R.id.switchHideStatusBar),
            findViewById(R.id.switchSortByUsage),
            findViewById(R.id.switchLockOrientation)
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
                // Section labels (all-caps bold) get accent color
                val isSectionLabel = view.isAllCaps && view.typeface?.isBold == true
                // Dimmed subtitles (alpha < 1) keep secondary color
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
        minSeekBar.progress = MIN_SIZES.indexOf(prefs.minFontSize).coerceAtLeast(0)
        minLabel.text = "${prefs.minFontSize}sp"

        maxSeekBar.max = MAX_SIZES.size - 1
        maxSeekBar.progress = MAX_SIZES.indexOf(prefs.maxFontSize).coerceAtLeast(0)
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
        lineSeekBar.progress = LINE_SPACINGS.indexOf(prefs.lineSpacing).coerceAtLeast(0)
        lineLabel.text = "${prefs.lineSpacing}dp"

        wordSeekBar.max = WORD_SPACINGS.size - 1
        wordSeekBar.progress = WORD_SPACINGS.indexOf(prefs.wordSpacing).coerceAtLeast(0)
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

        // Allow importFontLauncher to update the label when a font is imported
        _fontValueRef = fontValue

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

    // Weak reference so importFont() can update the label after async file pick
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

    // ── Gestures ─────────────────────────────────────────────────

    private fun setupGestures() {
        // Double tap toggle
        switchDoubleTap.isChecked = prefs.doubleTapToLock
        switchDoubleTap.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (isDeviceAdminActive()) prefs.doubleTapToLock = true
                else requestDeviceAdmin()
            } else {
                prefs.doubleTapToLock = false
            }
        }

        // Directional gesture rows
        val container = findViewById<LinearLayout>(R.id.gesturesContainer)
        val inflater = LayoutInflater.from(this)

        GESTURE_SLOTS.forEach { (fingers, dir, label) ->
            val row = inflater.inflate(R.layout.item_gesture_row, container, false)
            val labelView = row.findViewById<TextView>(R.id.gestureLabel)
            val actionView = row.findViewById<TextView>(R.id.gestureAction)

            labelView.text = label
            actionView.text = resolveGestureLabel(prefs.getGestureAction(fingers, dir))

            // Style based on background brightness
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

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, SlateDeviceAdminReceiver::class.java))
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(this@SettingsActivity, SlateDeviceAdminReceiver::class.java))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Allows Slate to lock the screen on double tap.")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEVICE_ADMIN) {
            val granted = isDeviceAdminActive()
            prefs.doubleTapToLock = granted
            switchDoubleTap.isChecked = granted
        }
    }

    // ── Search ───────────────────────────────────────────────────

    private fun setupSearch() {
        val switchEnable = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSearch)
        switchEnable.isChecked = prefs.searchEnabled
        switchEnable.setOnCheckedChangeListener { _, checked ->
            prefs.searchEnabled = checked
            // If search disabled, also disable "show on home"
            if (!checked) {
                prefs.showSearchBarOnHome = false
                findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSearchOnHome)
                    .isChecked = false
            }
        }

        val switchOnHome = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSearchOnHome)
        switchOnHome.isChecked = prefs.showSearchBarOnHome
        switchOnHome.setOnCheckedChangeListener { _, checked ->
            prefs.showSearchBarOnHome = checked
            // Enabling "show on home" implicitly enables search
            if (checked && !prefs.searchEnabled) {
                prefs.searchEnabled = true
                switchEnable.isChecked = true
            }
        }
    }

    // ── Colors ───────────────────────────────────────────────────

    private fun setupColors() {
        val bgInput = findViewById<EditText>(R.id.bgColorInput)
        val bgSwatch = findViewById<View>(R.id.bgColorSwatch)
        val textInput = findViewById<EditText>(R.id.textColorInput)
        val textSwatch = findViewById<View>(R.id.textColorSwatch)

        bgInput.setText(prefs.backgroundColor)
        bgSwatch.background = ColorDrawable(parseColorSafe(prefs.backgroundColor))
        textInput.setText(prefs.appTextColor)
        textSwatch.background = ColorDrawable(parseColorSafe(prefs.appTextColor, Color.GRAY))

        bgInput.addTextChangedListener(hexWatcher { hex ->
            prefs.backgroundColor = hex
            bgSwatch.background = ColorDrawable(parseColorSafe(hex))
            applyBackgroundColor()
        })
        textInput.addTextChangedListener(hexWatcher { hex ->
            prefs.appTextColor = hex
            textSwatch.background = ColorDrawable(parseColorSafe(hex, Color.GRAY))
        })

        bgSwatch.setOnClickListener {
            ColorPickerDialog(
                context = this,
                title = "Background",
                initialColor = prefs.backgroundColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.backgroundColor = hex
                bgInput.setText(hex)
                bgSwatch.background = ColorDrawable(parseColorSafe(hex))
                applyBackgroundColor()
                // Refresh text picker swatch border too
                setupColors()
            }.show()
        }

        textSwatch.setOnClickListener {
            ColorPickerDialog(
                context = this,
                title = "App Text",
                initialColor = prefs.appTextColor,
                bgColor = prefs.backgroundColor
            ) { hex ->
                prefs.appTextColor = hex
                textInput.setText(hex)
                textSwatch.background = ColorDrawable(parseColorSafe(hex, Color.GRAY))
            }.show()
        }
    }

    private fun hexWatcher(onValid: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val hex = s?.toString() ?: return
            if (hex.matches(Regex("^#[0-9A-Fa-f]{6}$"))) onValid(hex)
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
            // tint the arrow
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
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            BackupManager(prefs).fromJson(json)
            Toast.makeText(this, "Settings restored", Toast.LENGTH_SHORT).show()
            // Rebuild the UI with restored values
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── General ───────────────────────────────────────────────────

    private fun setupGeneral() {
        // Status bar toggle
        val switchStatusBar = findViewById<MaterialSwitch>(R.id.switchHideStatusBar)
        switchStatusBar.isChecked = prefs.hideStatusBar
        switchStatusBar.setOnCheckedChangeListener { _, checked ->
            prefs.hideStatusBar = checked
        }

        // Sort by usage toggle
        val switchSortUsage = findViewById<MaterialSwitch>(R.id.switchSortByUsage)
        switchSortUsage.isChecked = prefs.sortByUsage
        switchSortUsage.setOnCheckedChangeListener { _, checked ->
            prefs.sortByUsage = checked
        }

        // Lock orientation toggle
        val switchLockOrientation = findViewById<MaterialSwitch>(R.id.switchLockOrientation)
        switchLockOrientation.isChecked = prefs.lockOrientation
        switchLockOrientation.setOnCheckedChangeListener { _, checked ->
            prefs.lockOrientation = checked
            requestedOrientation = if (checked)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Default launcher row
        findViewById<View>(R.id.rowDefaultLauncher).setOnClickListener {
            requestDefaultLauncher()
        }
    }

    private fun requestDefaultLauncher() {
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
}
