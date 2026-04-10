package com.slate.launcher

import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import com.slate.launcher.MainActivity.Companion.isColorLight
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
    private var statusBarHeight = 0
    private var bottomInset = 0
    private lateinit var singleFingerDetector: GestureDetector

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
                    // Block only when user explicitly opened search (keyboard up);
                    // always-visible search bar should not block customization long press.
                    val searchBlocksLongPress = isSearchOpen && !prefs.showSearchBarOnHome
                    if (!touchStartedOnApp && !searchBlocksLongPress) {
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
                        if (dx > 0) Direction.RIGHT
                        else Direction.LEFT
                    } else {
                        if (dy > 0) Direction.DOWN
                        else Direction.UP
                    }

                    // Swipe down while search open → close search
                    if (dir == Direction.DOWN && isSearchOpen) {
                        closeSearch(); return true
                    }
                    // Swipe down only triggers when already at top
                    if (dir == Direction.DOWN && scrollView.scrollY != 0)
                        return false

                    return executeGestureAction(1, dir)
                }
            }
        )

        scrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = false
            singleFingerDetector.onTouchEvent(event)
            false
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
            val newStatusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (newStatusBarHeight != statusBarHeight) {
                statusBarHeight = newStatusBarHeight
                applySearchBarPosition()
            }
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val newBottomInset = maxOf(imeBottom, navBottom)
            if (newBottomInset != bottomInset) {
                bottomInset = newBottomInset
                applySearchBarPosition()
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
        applySearchBarPosition()
        applySearchColors()
        if (prefs.showSearchBarOnHome && prefs.searchEnabled) {
            isSearchOpen = true
            searchContainer.visibility = View.VISIBLE
        } else if (!prefs.showSearchBarOnHome) {
            isSearchOpen = false
            searchContainer.visibility = View.GONE
        }
        buildAppList()
    }

    override fun onPause() {
        super.onPause()
        SlateNotificationService.onChange = null
    }

    // ── Search ────────────────────────────────────────────────────

    private fun applySearchBarPosition() {
        val root = requireView() as android.widget.LinearLayout
        val atBottom = prefs.searchBarPosition == "bottom"
        val currentIndex = root.indexOfChild(searchContainer)
        val targetIndex = if (atBottom) root.childCount - 1 else 0
        if (currentIndex != targetIndex) {
            root.removeView(searchContainer)
            root.addView(searchContainer, if (atBottom) root.childCount else 0)
        }
        val density = resources.displayMetrics.density
        if (atBottom) {
            searchContainer.setPadding(
                (24 * density).toInt(),
                (20 * density).toInt(),
                (24 * density).toInt(),
                (12 * density).toInt() + bottomInset
            )
            scrollView.setPadding(0, 0, 0, 0)
        } else {
            searchContainer.setPadding(
                (24 * density).toInt(),
                (20 * density).toInt() + statusBarHeight,
                (24 * density).toInt(),
                (12 * density).toInt()
            )
            scrollView.setPadding(0, 0, 0, bottomInset)
        }
    }

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
        searchInput.setText("")
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        if (prefs.showSearchBarOnHome) {
            // Keep bar visible; just clear the filter
            buildAppList()
        } else {
            isSearchOpen = false
            searchContainer.visibility = View.GONE
            buildAppList()
        }
    }

    private fun dismissSearchBar() {
        searchInput.setText("")
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        if (prefs.showSearchBarOnHome && prefs.searchEnabled) {
            buildAppList()
        } else {
            isSearchOpen = false
            searchContainer.visibility = View.GONE
            buildAppList()
        }
    }

    private fun filterApps(query: String) {
        flowLayout.justifyContent = when {
            prefs.textAlignment == "left" -> JustifyContent.FLEX_START
            prefs.textAlignment == "right" -> JustifyContent.FLEX_END
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
        dir: Direction
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
            prefs.textAlignment == "left" -> JustifyContent.FLEX_START
            prefs.textAlignment == "right" -> JustifyContent.FLEX_END
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
                try { Typeface.createFromFile(File(family)) ?: Typeface.DEFAULT }
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
            items = listOf("App Info", "Hide", "Uninstall", "Custom color", "Rename"),
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            when (index) {
                0 -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", app.packageName, null)
                    }
                )
                1 -> { prefs.hideApp(app.packageName); buildAppList() }
                2 -> startActivity(
                    Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.fromParts("package", app.packageName, null)
                    }
                )
                3 -> showAppColorPicker(app)
                4 -> showRenameDialog(app)
            }
        }.show()
    }

    private fun showRenameDialog(app: AppInfo) {
        val ctx = requireContext()
        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#888888")
        val dividerColor = if (isLight) Color.parseColor("#DDDDDD") else Color.parseColor("#333333")
        val ripple = if (isLight) Color.parseColor("#15000000") else Color.parseColor("#20FFFFFF")
        val density = ctx.resources.displayMetrics.density
        val hPad = (24 * density).toInt()
        val vPad = (14 * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bg)
                cornerRadius = 12f * density
            }
        }

        // Title
        root.addView(TextView(ctx).apply {
            text = "Rename ${app.name}"
            textSize = 15f
            setTextColor(accent)
            setPadding(hPad, vPad, hPad, vPad)
        })

        fun divider() = View(ctx).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.marginStart = hPad; it.marginEnd = hPad }
        }

        root.addView(divider())

        // Text input — filled background so it reads as an editable field
        val inputFill = if (isLight) Color.parseColor("#EBEBEB") else Color.parseColor("#1E1E1E")
        val inputStroke = if (isLight) Color.parseColor("#CCCCCC") else Color.parseColor("#4A4A4A")
        val input = android.widget.EditText(ctx).apply {
            setText(app.name)
            textSize = 17f
            setTextColor(primary)
            setHintTextColor(secondary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(inputFill)
                setStroke((1f * density).toInt(), inputStroke)
                cornerRadius = 8f * density
            }
            val inputHPad = (14 * density).toInt()
            val inputVPad = (12 * density).toInt()
            setPadding(inputHPad, inputVPad, inputHPad, inputVPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.marginStart = hPad
                it.marginEnd = hPad
                it.topMargin = (12 * density).toInt()
                it.bottomMargin = (12 * density).toInt()
            }
            selectAll()
        }
        root.addView(input)

        val dialog = Dialog(ctx, R.style.SlateDialogTheme)

        val hasCustomName = prefs.getAppCustomName(app.packageName) != null
        val saveBg   = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val resetBg  = if (isLight) Color.parseColor("#DEDEDE") else Color.parseColor("#2A2A2A")

        val bVPad = (15 * density).toInt()
        val bHPad = (20 * density).toInt()

        fun pillButton(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit) =
            TextView(ctx).apply {
                text = label
                textSize = 15f
                setTextColor(textColor)
                gravity = android.view.Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(bgColor)
                    cornerRadius = 100f * density
                }
                setPadding(bHPad, bVPad, bHPad, bVPad)
                setOnClickListener { onClick(); dialog.dismiss() }
            }

        // Horizontal button row
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.marginStart  = hPad
                it.marginEnd    = hPad
                it.topMargin    = (10 * density).toInt()
                it.bottomMargin = (20 * density).toInt()
            }
        }

        if (hasCustomName) {
            buttonRow.addView(
                pillButton("Reset to Default", resetBg, secondary) {
                    prefs.clearAppCustomName(app.packageName)
                    buildAppList()
                }.also {
                    it.layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { lp ->
                        lp.marginEnd = (12 * density).toInt()
                    }
                }
            )
        }

        buttonRow.addView(
            pillButton("Save", saveBg, Color.WHITE) {
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.setAppCustomName(app.packageName, newName)
                    buildAppList()
                }
            }.also {
                it.layoutParams = if (hasCustomName)
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                else
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        )

        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        input.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.postDelayed({ imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 80)
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
            items = listOf("Customize", "Hidden Apps", "FAQ"),
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            when (index) {
                0 -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
                1 -> showHiddenAppsDialog()
                2 -> showFaqDialog()
            }
        }.show()
    }

    private fun showFaqDialog() {
        val faqs = listOf(
            "Why does Slate need Accessibility permission?" to
                "Accessibility is used only for the \"double tap to lock screen\" feature. It calls a single system API (GLOBAL_ACTION_LOCK_SCREEN) to lock the device while keeping biometric unlock available.\n\nSlate cannot read screen content, monitor app usage, or collect any data via this permission.",

            "Why does Slate need Notification access?" to
                "Notification access is optional and used only for the notification highlight feature — it changes the color of an app's name when it has a pending notification.\n\nSlate only checks which packages have active notifications. Notification content (titles, messages, senders) is never read or stored.",

            "Does Slate collect any data?" to
                "No. Slate is 100% offline and collects zero data.\n\nThere is no analytics, no crash reporting, no tracking, and no network requests of any kind. All settings, usage counts, and customizations are stored locally on your device using Android's SharedPreferences and never leave it.",

            "What other permissions does Slate use?" to
                "• EXPAND_STATUS_BAR — swipe-down notification panel gesture\n• ACCESS_WIFI_STATE / CHANGE_WIFI_STATE — Wi-Fi toggle gesture (Android 10+: opens system panel)\n• BLUETOOTH / BLUETOOTH_ADMIN — Bluetooth toggle on Android 11 and below\n• QUERY_ALL_PACKAGES — required to list all installed apps (Android 11+)\n• REQUEST_DELETE_PACKAGES — initiates the system uninstall flow when you choose to uninstall an app",

            "Is Slate open source?" to
                "Yes. Slate is open source under the MIT licence.\n\nSource code: github.com/roufsyed/Slate-Minimal-Launcher"
        )
        SlateListDialog(
            context = requireContext(),
            title = "FAQ",
            items = faqs.map { it.first },
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            showFaqDetail(faqs[index].first, faqs[index].second)
        }.show()
    }

    private fun showFaqDetail(question: String, answer: String) {
        val ctx = requireContext()
        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primaryColor = if (isLight) Color.BLACK else Color.WHITE
        val accentColor = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = ctx.resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bg)
                cornerRadius = 12f * density
            }
            setPadding(pad, pad, pad, pad)
        }

        val dialog = Dialog(ctx, R.style.SlateDialogTheme)

        // Back arrow row
        val mutedColor = if (isLight) Color.parseColor("#666666") else Color.parseColor("#888888")
        container.addView(TextView(ctx).apply {
            text = "← FAQ"
            textSize = 13f
            setTextColor(mutedColor)
            setPadding((4 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * density).toInt() }
            setOnClickListener {
                dialog.dismiss()
                showFaqDialog()
            }
        })

        container.addView(TextView(ctx).apply {
            text = question
            textSize = 15f
            setTextColor(accentColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (14 * density).toInt() }
        })

        container.addView(TextView(ctx).apply {
            text = answer
            textSize = 15f
            setTextColor(primaryColor)
            setLineSpacing(4f * density, 1f)
        })

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
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
        SlateAccessibilityService.lockScreen()
    }

    private fun expandNotificationsPanel() {
        try {
            val service = requireContext().getSystemService("statusbar") ?: return
            val manager = Class.forName("android.app.StatusBarManager")
            manager.getMethod("expandNotificationsPanel").invoke(service)
        } catch (_: Exception) {}
    }
}
