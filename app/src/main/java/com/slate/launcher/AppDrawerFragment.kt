package com.slate.launcher

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import java.io.File
import kotlin.math.abs

class AppDrawerFragment : Fragment() {

    private lateinit var scrollView: ScrollView
    private lateinit var flowLayout: FlexboxLayout
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchClose: TextView
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: AppRepository

    private var isSearchOpen = false
    private var touchStartedOnApp = false
    private var scrollYOnDown = 0
    private lateinit var singleFingerDetector: GestureDetector
    private lateinit var multiFingerDetector: MultiFingerGestureDetector

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_app_drawer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesManager(requireContext())
        repository = AppRepository(requireContext(), prefs)

        scrollView = view.findViewById(R.id.scrollView)
        flowLayout = view.findViewById(R.id.appFlowLayout)
        searchContainer = view.findViewById(R.id.searchContainer)
        searchInput = view.findViewById(R.id.searchInput)
        searchClose = view.findViewById(R.id.searchClose)

        flowLayout.flexWrap = FlexWrap.WRAP
        flowLayout.justifyContent = JustifyContent.CENTER
        flowLayout.alignItems = AlignItems.CENTER

        setupSearch()

        // Single-finger: long press, double tap, directional fling
        singleFingerDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(e: MotionEvent): Boolean {
                    scrollYOnDown = scrollView.scrollY
                    return true // must return true for GestureDetector to track the sequence
                }

                override fun onLongPress(e: MotionEvent) {
                    if (!isSearchOpen && !touchStartedOnApp) {
                        showHomeLongPressDialog()
                    }
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (prefs.doubleTapToLock) { lockScreen(); return true }
                    return false
                }

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    val dx = e2.x - (e1?.x ?: e2.x)
                    val dy = e2.y - (e1?.y ?: e2.y)
                    val absDx = abs(dx)
                    val absDy = abs(dy)

                    // Require meaningful distance
                    if (absDx < 120f && absDy < 120f) return false
                    // Require meaningful velocity
                    if (abs(velocityX) < 500f && abs(velocityY) < 500f) return false
                    // Require mostly straight swipe — secondary axis < 65% of primary
                    val ratio = if (absDx > absDy) absDy / absDx else absDx / absDy
                    if (ratio > 0.65f) return false

                    // If content scrolled significantly during this touch, it was a list scroll
                    val scrollDelta = abs(scrollView.scrollY - scrollYOnDown)
                    val density = resources.displayMetrics.density
                    if (scrollDelta > density * 80) return false

                    val dir = if (absDx > absDy) {
                        if (dx > 0) MultiFingerGestureDetector.Direction.RIGHT
                        else MultiFingerGestureDetector.Direction.LEFT
                    } else {
                        if (dy > 0) MultiFingerGestureDetector.Direction.DOWN
                        else MultiFingerGestureDetector.Direction.UP
                    }

                    // Swipe down while search open → close search
                    if (dir == MultiFingerGestureDetector.Direction.DOWN && isSearchOpen) {
                        closeSearch(); return true
                    }
                    // Swipe down only triggers when already at top
                    if (dir == MultiFingerGestureDetector.Direction.DOWN && scrollView.scrollY != 0)
                        return false

                    return executeGestureAction(1, dir)
                }
            }
        )

        // Multi-finger: 2 and 3 finger directional swipes
        multiFingerDetector = MultiFingerGestureDetector { fingers, dir ->
            executeGestureAction(fingers, dir)
        }

        scrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = false
            val multiConsumed = multiFingerDetector.onTouchEvent(event)
            if (event.pointerCount == 1) {
                singleFingerDetector.onTouchEvent(event)
            }
            multiConsumed || event.pointerCount > 1
        }

        // Back press closes search if open
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isSearchOpen) closeSearch()
                    // Launcher never exits
                }
            }
        )

        // When keyboard closes, hide the search bar unless the user wants it always visible
        ViewCompat.setOnApplyWindowInsetsListener(requireView()) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (!imeVisible && isSearchOpen && !prefs.showSearchBarOnHome) {
                dismissSearchBar()
            }
            ViewCompat.onApplyWindowInsets(v, insets)
        }
    }

    override fun onResume() {
        super.onResume()
        SlateNotificationService.onChange = {
            activity?.runOnUiThread { buildAppList() }
        }
        val bg = parseColorSafe(prefs.backgroundColor)
        scrollView.setBackgroundColor(bg)
        requireView().setBackgroundColor(bg)
        applySearchColors()
        if (prefs.showSearchBarOnHome && prefs.searchEnabled) {
            isSearchOpen = true
            searchContainer.visibility = View.VISIBLE
        } else if (!prefs.showSearchBarOnHome && !isSearchOpen) {
            searchContainer.visibility = View.GONE
        }
        buildAppList()
    }

    override fun onPause() {
        super.onPause()
        SlateNotificationService.onChange = null
    }

    // ── Search ────────────────────────────────────────────────────

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = searchInput.text.toString()
                val match = repository.getAllApps()
                    .firstOrNull { it.name.contains(query, ignoreCase = true) }
                if (match != null) launchApp(match)
                true
            } else false
        }

        searchClose.setOnClickListener { closeSearch() }
    }

    private fun applySearchColors() {
        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = com.slate.launcher.MainActivity.Companion.isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#888888")

        searchContainer.setBackgroundColor(bg)
        searchInput.setTextColor(primary)
        searchInput.setHintTextColor(secondary)
        searchClose.setTextColor(secondary)
    }

    fun openSearch() {
        isSearchOpen = true
        applySearchColors()
        searchContainer.visibility = View.VISIBLE
        searchInput.requestFocus()
        searchInput.setText("")
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        searchInput.postDelayed({
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }, 80)
    }

    private fun closeSearch() {
        isSearchOpen = false
        searchContainer.visibility = View.GONE
        searchInput.setText("")
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        buildAppList()
    }

    private fun dismissSearchBar() {
        isSearchOpen = false
        searchContainer.visibility = View.GONE
        searchInput.setText("")
        buildAppList()
    }

    private fun filterApps(query: String) {
        flowLayout.justifyContent = when {
            prefs.sortByUsage && prefs.sortAlignRight -> JustifyContent.FLEX_END
            prefs.sortByUsage -> JustifyContent.FLEX_START
            else -> JustifyContent.CENTER
        }
        flowLayout.removeAllViews()
        val all = repository.getAllApps()
        val apps = if (query.isEmpty()) all
                   else all.filter { it.name.contains(query, ignoreCase = true) }

        val maxUsage = all.maxOfOrNull { prefs.getUsageCount(it.packageName) }
            ?.takeIf { it > 0 } ?: 1
        val density = resources.displayMetrics.density
        val defaultTextColor = parseColorSafe(prefs.appTextColor, Color.GRAY)
        val notifEnabled = prefs.notificationColorEnabled
        val notifColor = parseColorSafe(prefs.notificationHighlightColor)
        val typeface = buildTypeface()

        apps.forEach { app ->
            val usage = prefs.getUsageCount(app.packageName)
            val appColor = prefs.getAppTextColor(app.packageName)
            val hasNotif = notifEnabled && app.packageName in SlateNotificationService.activePackages
            val textColor = when {
                hasNotif -> notifColor
                appColor != null -> parseColorSafe(appColor)
                else -> defaultTextColor
            }
            val tv = TextView(requireContext()).apply {
                text = app.name
                textSize = computeFontSize(usage, maxUsage)
                setTextColor(textColor)
                this.typeface = typeface
                val hPad = (prefs.wordSpacing * density).toInt()
                val vPad = (prefs.lineSpacing * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                setOnClickListener { launchApp(app) }
                setOnLongClickListener { showAppMenu(app, this); true }
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = true
                    multiFingerDetector.onTouchEvent(event)
                    singleFingerDetector.onTouchEvent(event)
                    false
                }
            }
            flowLayout.addView(tv)
        }
    }

    // ── Gesture execution ─────────────────────────────────────────

    private fun executeGestureAction(
        fingers: Int,
        dir: MultiFingerGestureDetector.Direction
    ): Boolean {
        return when (val action = prefs.getGestureAction(fingers, dir)) {
            is GestureAction.None              -> false
            is GestureAction.Search            -> { if (prefs.searchEnabled) { openSearch(); true } else false }
            is GestureAction.OpenNotifications -> { expandNotificationsPanel(); true }
            is GestureAction.LockScreen        -> { lockScreen(); true }
            is GestureAction.OpenSettings      -> {
                startActivity(Intent(requireContext(), SettingsActivity::class.java)); true
            }
            is GestureAction.ToggleWifi        -> { toggleWifi(); true }
            is GestureAction.ToggleBluetooth   -> { toggleBluetooth(); true }
            is GestureAction.ToggleLocation    -> {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            }
            is GestureAction.OpenCamera        -> {
                val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(intent); true } catch (_: Exception) { false }
            }
            is GestureAction.OpenApp           -> {
                val intent = requireContext().packageManager
                    .getLaunchIntentForPackage(action.packageName)
                if (intent != null) { startActivity(intent); true } else false
            }
        }
    }

    // ── App list ──────────────────────────────────────────────────

    private fun buildAppList() {
        flowLayout.justifyContent = when {
            prefs.sortByUsage && prefs.sortAlignRight -> JustifyContent.FLEX_END
            prefs.sortByUsage -> JustifyContent.FLEX_START
            else -> JustifyContent.CENTER
        }
        flowLayout.removeAllViews()
        val apps = repository.getAllApps()
        val maxUsage = apps.maxOfOrNull { prefs.getUsageCount(it.packageName) }
            ?.takeIf { it > 0 } ?: 1
        val density = resources.displayMetrics.density
        val defaultTextColor = parseColorSafe(prefs.appTextColor, Color.GRAY)
        val notifEnabled = prefs.notificationColorEnabled
        val notifColor = parseColorSafe(prefs.notificationHighlightColor)
        val typeface = buildTypeface()

        apps.forEach { app ->
            val usage = prefs.getUsageCount(app.packageName)
            val appColor = prefs.getAppTextColor(app.packageName)
            val hasNotif = notifEnabled && app.packageName in SlateNotificationService.activePackages
            val textColor = when {
                hasNotif -> notifColor
                appColor != null -> parseColorSafe(appColor)
                else -> defaultTextColor
            }
            val tv = TextView(requireContext()).apply {
                text = app.name
                textSize = computeFontSize(usage, maxUsage)
                setTextColor(textColor)
                this.typeface = typeface
                val hPad = (prefs.wordSpacing * density).toInt()
                val vPad = (prefs.lineSpacing * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                setOnClickListener { launchApp(app) }
                setOnLongClickListener { showAppMenu(app, this); true }
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = true
                    multiFingerDetector.onTouchEvent(event)
                    singleFingerDetector.onTouchEvent(event)
                    false
                }
            }
            flowLayout.addView(tv)
        }
    }

    private fun buildTypeface(): Typeface {
        val family = prefs.fontFamily
        val weight = prefs.fontWeight

        val base: Typeface = when {
            family.startsWith("/") -> {
                // Imported font file in internal storage
                try { Typeface.createFromFile(File(family)) }
                catch (_: Exception) { Typeface.DEFAULT }
            }
            family.startsWith("gf:") -> {
                // Google downloadable font via R.font.*
                val resId = googleFontResId(family.removePrefix("gf:"))
                if (resId != 0) {
                    try { ResourcesCompat.getFont(requireContext(), resId) ?: Typeface.DEFAULT }
                    catch (_: Exception) { Typeface.DEFAULT }
                } else Typeface.DEFAULT
            }
            else -> Typeface.create(family, Typeface.NORMAL)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight, false)
        } else {
            Typeface.create(base, if (weight >= 700) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun googleFontResId(name: String): Int = when (name) {
        "tex_gyre_adventor_bold" -> R.font.tex_gyre_adventor_bold
        "roboto"                 -> R.font.roboto
        "noto_sans"              -> R.font.noto_sans
        "coming_soon"            -> R.font.coming_soon
        "cutive_mono"            -> R.font.cutive_mono
        else                     -> 0
    }

    private fun computeFontSize(usage: Int, maxUsage: Int): Float {
        val ratio = usage.toFloat() / maxUsage
        return prefs.minFontSize + ratio * (prefs.maxFontSize - prefs.minFontSize)
    }

    private fun launchApp(app: AppInfo) {
        prefs.incrementUsage(app.packageName)
        // Optimistically clear notification highlight so it reverts immediately on return
        SlateNotificationService.activePackages.remove(app.packageName)
        if (isSearchOpen) closeSearch()
        val intent = requireContext().packageManager
            .getLaunchIntentForPackage(app.packageName) ?: return
        startActivity(intent)
    }

    private fun showAppMenu(app: AppInfo, anchor: View) {
        SlateListDialog(
            context = requireContext(),
            title = app.name,
            items = listOf("Custom color", "Hide", "App Info", "Uninstall"),
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            when (index) {
                0 -> showAppColorPicker(app)
                1 -> { prefs.hideApp(app.packageName); buildAppList() }
                2 -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", app.packageName, null)
                    }
                )
                3 -> startActivity(
                    Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.fromParts("package", app.packageName, null)
                    }
                )
            }
        }.show()
    }

    private fun showAppColorPicker(app: AppInfo) {
        val current = prefs.getAppTextColor(app.packageName) ?: prefs.appTextColor
        ColorPickerDialog(
            context = requireContext(),
            title = app.name,
            initialColor = current,
            bgColor = prefs.backgroundColor,
            showReset = prefs.getAppTextColor(app.packageName) != null,
            onReset = {
                prefs.clearAppTextColor(app.packageName)
                buildAppList()
            }
        ) { hex ->
            prefs.setAppTextColor(app.packageName, hex)
            buildAppList()
        }.show()
    }

    // ── Home long-press dialog ────────────────────────────────────

    private fun showHomeLongPressDialog() {
        SlateListDialog(
            context = requireContext(),
            title = "",
            items = listOf("Customize", "Hidden Apps"),
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            when (index) {
                0 -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
                1 -> showHiddenAppsDialog()
            }
        }.show()
    }

    private fun showHiddenAppsDialog() {
        val pm = requireContext().packageManager
        val hidden = prefs.hiddenApps.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString() to pkg
            } catch (_: Exception) { null }
        }.sortedBy { it.first.lowercase() }

        if (hidden.isEmpty()) {
            SlateListDialog(
                context = requireContext(),
                title = "Hidden Apps",
                items = listOf("No hidden apps"),
                bgColor = prefs.backgroundColor
            ) { _, _ -> }.show()
            return
        }

        SlateListDialog(
            context = requireContext(),
            title = "Hidden Apps — tap to unhide",
            items = hidden.map { it.first },
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            prefs.unhideApp(hidden[index].second)
            buildAppList()
        }.show()
    }

    // ── System actions ────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val wm = requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled = !wm.isWifiEnabled
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun toggleBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No Bluetooth panel in Settings.Panel; open Bluetooth settings page
            startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter?.isEnabled == true) adapter.disable() else adapter?.enable()
        }
    }

    private fun lockScreen() {
        val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE)
                as DevicePolicyManager
        val admin = ComponentName(requireContext(), SlateDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        } else {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Allows Slate to lock the screen on double tap."
                    )
                }
            )
        }
    }

    private fun expandNotificationsPanel() {
        try {
            val service = requireContext().getSystemService("statusbar") ?: return
            val manager = Class.forName("android.app.StatusBarManager")
            manager.getMethod("expandNotificationsPanel").invoke(service)
        } catch (_: Exception) {}
    }
}
