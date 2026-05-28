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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import com.slate.launcher.widgets.CallShortcutWidget
import com.slate.launcher.widgets.QuickStripManager
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import kotlin.math.abs

class AppDrawerFragment : Fragment() {

    private lateinit var scrollView: ScrollView
    private lateinit var flowLayout: FlexboxLayout
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchClose: TextView
    private lateinit var fastScroll: AlphaFastScroll
    private lateinit var fastScrollBubble: TextView
    private lateinit var stripDivider: View
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: AppRepository
    private var quickStrip: QuickStripManager? = null

    private var isSearchOpen = false
    private var touchStartedOnApp = false
    private var scrollYOnDown = 0
    private var statusBarHeight = 0
    private var bottomInset = 0
    private var isImeVisible: Boolean = false
    private lateinit var singleFingerDetector: GestureDetector
    /** Null = main view; non-null = home is showing the contents of that folder. */
    private var currentFolderId: String? = null

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
        fastScroll = view.findViewById(R.id.fastScroll)
        fastScrollBubble = view.findViewById(R.id.fastScrollBubble)
        stripDivider = view.findViewById(R.id.stripDivider)

        // Forward touches that begin on the strip into the home gesture detector so swipes
        // starting on the chrome execute the user's configured 1-finger gestures (instead of
        // dying because the strip has no scroll/gesture handlers of its own). Clearing the
        // `touchStartedOnApp` flag on DOWN matches the blank-home-space semantics — long-press
        // on the strip then opens the home long-press menu, not an app menu. The lambda reads
        // `singleFingerDetector` lazily; the detector is initialised later in this same
        // onViewCreated but always before any touch fires.
        quickStrip = QuickStripManager(
            container = view.findViewById(R.id.quickStripContainer),
            prefs = prefs,
            touchForwarder = { event ->
                if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = false
                singleFingerDetector.onTouchEvent(event)
            }
        )

        setupSearch()
        setupFastScroll()

        // Single-finger: long press, double tap, directional fling
        singleFingerDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(e: MotionEvent): Boolean {
                    scrollYOnDown = scrollView.scrollY
                    return true // must return true for GestureDetector to track the sequence
                }

                override fun onLongPress(e: MotionEvent) {
                    // Direct-call-on-long-press: when the user has bound long-press as their
                    // direct-call trigger AND the touch hit-tests to a CallShortcutWidget,
                    // fire the call here and consume the gesture. We deliberately do NOT also
                    // show the home menu — that would race with the freshly-fired call intent
                    // and surprise the user with a customisation dialog they didn't ask for.
                    if (prefs.directCallEnabled && prefs.directCallTrigger == "longPress") {
                        val widget = quickStrip?.widgetForRawTouch(e.rawX, e.rawY)
                        if (widget is CallShortcutWidget) {
                            widget.onLongPressDirect(requireContext())
                            return
                        }
                    }

                    // Block only when user explicitly opened search (keyboard up);
                    // always-visible search bar should not block customization long press.
                    val searchBlocksLongPress = isSearchOpen && !prefs.showSearchBarOnHome
                    if (!touchStartedOnApp && !searchBlocksLongPress) {
                        showHomeLongPressDialog()
                    }
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!prefs.doubleTapToLock) return false
                    // Suppress the screen-lock when the second tap landed on interactive content
                    // rather than blank home space. Two cases:
                    //   1. A strip widget — `touchStartedOnApp` is false here (the touchForwarder
                    //      clears it), so we explicitly hit-test the strip. Without this guard,
                    //      rapid widget toggling (e.g., torch on → torch off within 300 ms)
                    //      would accidentally lock the user's phone.
                    //   2. An app / folder / back-out row — `touchStartedOnApp` is true (set by
                    //      the row's own setOnTouchListener). In practice the first tap launches
                    //      the app and the second tap goes to the launched app, so this branch
                    //      rarely fires — but the check is defensive symmetry with onLongPress.
                    if (touchStartedOnApp) return false
                    if (quickStrip?.widgetForRawTouch(e.rawX, e.rawY) != null) return false
                    lockScreen()
                    return true
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
                    else if (currentFolderId != null) exitFolder()
                    // Launcher never exits
                }
            }
        )

        // When keyboard closes, hide the search bar unless the user wants it always visible
        ViewCompat.setOnApplyWindowInsetsListener(requireView()) { v, insets ->
            // Refresh the field before any branch below so that subsequent applyChromeLayout()
            // calls (status-bar branch, bottom-inset branch) read fresh IME state when computing
            // strip suppression. In practice every IME show/hide transitions bottomInset (because
            // bottomInset = max(imeBottom, navBottom)), so the existing bottom-inset trigger
            // covers every real device; the field write here keeps that trigger's view consistent.
            isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (!isImeVisible && isSearchOpen && !prefs.showSearchBarOnHome) {
                dismissSearchBar()
            }
            // Combine the status-bar inset with the display-cutout inset (camera punch hole,
            // notch). `getInsets(typeA or typeB)` returns the per-edge UNION/max — so when the
            // status bar is visible it already covers the cutout (no change); when the status
            // bar is hidden via `prefs.hideStatusBar`, the cutout inset takes over and the
            // top-edge chrome (e.g., the quick-toggles strip at the top position) is padded
            // below the punch hole instead of being drawn under it.
            val newStatusBarHeight = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            if (newStatusBarHeight != statusBarHeight) {
                statusBarHeight = newStatusBarHeight
                applyChromeLayout()
            }
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // Same cutout-union treatment at the bottom — handles the (rare) bottom display
            // cutout. The nav-bar inset is what this evaluates to on almost every device.
            val navBottom = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            ).bottom
            val newBottomInset = maxOf(imeBottom, navBottom)
            if (newBottomInset != bottomInset) {
                bottomInset = newBottomInset
                applyChromeLayout()
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
        } else {
            // Defensive fallthrough: covers the contradictory state
            // `showSearchBarOnHome=true && searchEnabled=false` that could land via backup
            // restore or a future pref-write bug. Without this, applyChromeLayout would route
            // the inset to a still-VISIBLE-but-blank search container.
            isSearchOpen = false
            searchContainer.visibility = View.GONE
        }
        // Always land on the main list when returning to home — folder state is a transient
        // navigation, not a persisted view.
        currentFolderId = null
        buildAppList()
        quickStrip?.let {
            it.bind()
            it.start()
        }
        // Chrome layout reads the final visibilities of both search and strip so insets route
        // to whichever element is actually at each screen edge. Must run AFTER quickStrip.bind()
        // (which sets the strip's visibility per `prefs.quickStripEnabled` + widget count) and
        // AFTER the search-visibility branch above.
        applyChromeLayout()
        reconcileDoubleTapPref()
    }

    /**
     * Reconcile `prefs.doubleTapToLock` against the live accessibility-service state. Settings
     * already does this on its own onResume; without the equivalent here, a stale `true` pref
     * (e.g., restored from a backup on a permissionless device, or accessibility revoked from
     * Android Settings while the launcher was in the background) would silently no-op every
     * double-tap until the user happens to open Slate Settings. After this runs, repeated
     * double-taps either work (service genuinely enabled) or do nothing AND a Settings open
     * shows the toggle truthfully OFF.
     *
     * The 500ms re-check mirrors Settings — protects against an OEM where the secure-setting
     * flip lags the actual service binding. Skipping the work entirely when the pref is
     * already false or the service is already enabled keeps the common path free of any
     * scheduled handler.
     */
    private fun reconcileDoubleTapPref() {
        if (!prefs.doubleTapToLock) return
        if (SlateAccessibilityService.isEnabled(requireContext())) return
        view?.postDelayed({
            if (isAdded &&
                prefs.doubleTapToLock &&
                !SlateAccessibilityService.isEnabled(requireContext())
            ) {
                prefs.doubleTapToLock = false
            }
        }, 500)
    }

    override fun onPause() {
        super.onPause()
        SlateNotificationService.onChange = null
        fastScrollBubble.animate().cancel()
        quickStrip?.stop()
    }

    override fun onDestroyView() {
        // Drop the QuickStripManager's reference to the (now-defunct) FlexboxLayout, and make
        // sure any straggling observers are unregistered. onPause should always have fired first,
        // but defensive cleanup costs nothing.
        quickStrip?.stop()
        quickStrip = null
        super.onDestroyView()
    }

    // ── Search ────────────────────────────────────────────────────

    /**
     * Lay out the home-screen chrome (search bar + quick-toggles strip) per the user's two
     * position prefs and route the system insets (status bar at the top edge, IME / navigation
     * bar at the bottom edge) to whichever element is actually visible at each edge.
     *
     * Order rule when both share an edge: the strip sits at the absolute screen edge and the
     * search bar sits just inside it. The strip is "ambient status"; the search bar is
     * intermittent input. Anchoring the strip keeps the visible chrome geometrically stable as
     * the search bar appears and disappears.
     *
     * Inset routing rule: whichever child is at an edge owns that edge's inset padding. When
     * the FrameLayout (containing the ScrollView) is at an edge, the scrollView itself gets the
     * padding so its content doesn't slide under the status / navigation bar.
     */
    private fun applyChromeLayout() {
        val root = requireView() as android.widget.LinearLayout
        val frameLayout = scrollView.parent as View
        val stripContainer = root.findViewById<View>(R.id.quickStripContainer)
        val searchAtBottom = prefs.searchBarPosition == "bottom"
        val stripAtBottom = prefs.quickStripPosition == "bottom"

        // Strip-visibility override: hide the quick-strip while the soft keyboard is up.
        // Without this, adjustResize shrinks the window and the strip — pinned to the bottom
        // edge of the root LinearLayout via the weight=1 FrameLayout above it — rides up onto
        // the keyboard's leading edge. The strip's "intended" visibility (configured + enabled
        // widgets exist) is owned by QuickStripManager.bind(); applyChromeLayout layers this
        // contextual GONE on top. Inset routing below already filters by visibility, so a GONE
        // strip transparently hands the bottomInset off to the next visible child (FrameLayout).
        val stripIntended =
            quickStrip?.hasActiveWidgets() == true && prefs.quickStripEnabled
        val stripEffective = stripIntended && !isImeVisible
        val newStripVisibility = if (stripEffective) View.VISIBLE else View.GONE
        val stripWasHidden = stripContainer.visibility != View.VISIBLE
        if (stripContainer.visibility != newStripVisibility) {
            stripContainer.visibility = newStripVisibility
        }
        // After restoring from hidden, repaint widget labels so e.g. a clock that missed the
        // last TIME_TICK while invisible doesn't show stale text for a frame. Observers stay
        // alive while the strip is GONE (start/stop is bound to onResume/onPause, not visibility),
        // so this is a defensive immediate repaint, not a re-subscribe.
        if (stripEffective && stripWasHidden) {
            quickStrip?.refreshAll()
        }

        // Single source of truth for "is the strip showing": the container's actual visibility,
        // which QuickStripManager.bind() reconciles against both the master switch AND per-widget
        // device availability (e.g., torch widget pruned on a no-camera device). Reading the
        // pref directly would diverge in the "all configured widgets unavailable" case.
        val dividerVisible =
            prefs.quickStripDividerEnabled && stripContainer.visibility == View.VISIBLE
        stripDivider.visibility = if (dividerVisible) View.VISIBLE else View.GONE
        if (dividerVisible) {
            val bg = parseColorSafe(prefs.backgroundColor)
            stripDivider.setBackgroundColor(
                if (isColorLight(bg)) Color.parseColor("#DDDDDD") else Color.parseColor("#333333")
            )
        }

        // The divider always rides immediately adjacent to the strip on its INNER edge — below
        // it when the strip is at top, above it when the strip is at bottom. The strip itself
        // remains the absolute screen-edge child, so inset routing (below) is unchanged for the
        // strip/search/frame siblings; the divider never becomes the edge child.
        val orderedChildren: List<View> = when {
            !searchAtBottom && stripAtBottom  ->
                listOf(searchContainer, frameLayout, stripDivider, stripContainer)
            !searchAtBottom && !stripAtBottom ->
                // Both at top: strip is the absolute edge, divider just inside it, then search.
                listOf(stripContainer, stripDivider, searchContainer, frameLayout)
            searchAtBottom && !stripAtBottom  ->
                listOf(stripContainer, stripDivider, frameLayout, searchContainer)
            else                              ->
                // Both at bottom: strip is the absolute edge, divider just inside it.
                listOf(frameLayout, searchContainer, stripDivider, stripContainer)
        }

        // Re-arrange only if the order actually changed, to avoid superfluous removeAllViews on
        // every onResume / inset callback.
        val currentOrder = (0 until root.childCount).map { root.getChildAt(it) }
        if (currentOrder != orderedChildren) {
            root.removeAllViews()
            orderedChildren.forEach { root.addView(it) }
        }

        // Route insets to whichever child is *visibly* at each edge. A GONE strip / GONE search
        // bar / GONE divider must not absorb the inset — the next visible child gets it instead.
        val visibleChildren = orderedChildren.filter { it.visibility != View.GONE }
        val topEdge = visibleChildren.firstOrNull()
        val bottomEdge = visibleChildren.lastOrNull()

        val density = resources.displayMetrics.density
        val searchHPad = (24 * density).toInt()
        val searchVPadTop = (20 * density).toInt()
        val searchVPadBottom = (12 * density).toInt()
        val stripHPad = (20 * density).toInt()
        val stripVPad = (8 * density).toInt()

        searchContainer.setPadding(
            searchHPad,
            searchVPadTop + (if (topEdge === searchContainer) statusBarHeight else 0),
            searchHPad,
            searchVPadBottom + (if (bottomEdge === searchContainer) bottomInset else 0)
        )
        // When the divider is visible, drop the strip's INNER-edge vertical padding to 0 so the
        // hairline visually hugs the strip rather than floating 8dp away from it. Inner edge =
        // the side facing the app list: TOP when the strip is at the bottom of the screen,
        // BOTTOM when the strip is at the top. The OUTER edge (where the status-bar / nav-bar
        // inset lives) is preserved either way.
        val stripTopPad = if (dividerVisible && stripAtBottom) 0 else stripVPad
        val stripBottomPad = if (dividerVisible && !stripAtBottom) 0 else stripVPad
        stripContainer.setPadding(
            stripHPad,
            stripTopPad + (if (topEdge === stripContainer) statusBarHeight else 0),
            stripHPad,
            stripBottomPad + (if (bottomEdge === stripContainer) bottomInset else 0)
        )
        scrollView.setPadding(
            0,
            if (topEdge === frameLayout) statusBarHeight else 0,
            0,
            if (bottomEdge === frameLayout) bottomInset else 0
        )
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
        // Search-bar visibility changed → the bottom-edge child of the visible-children list
        // may have flipped, so the inset-routing pass inside applyChromeLayout must re-run.
        // The IME-close inset event will also trigger this incidentally, but only when the
        // keyboard was actually up — code paths that call closeSearch without ever having
        // shown the keyboard would otherwise leave routing stale.
        applyChromeLayout()
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
        applyChromeLayout()
    }

    private fun filterApps(query: String) {
        val all = repository.getAllApps(forceAlphabetical = useFastScroll())
        // Starting to type drops folder context entirely — the user is now searching globally,
        // and clearing the query should restore the main list rather than a half-remembered
        // folder view. This makes "type → clear" a predictable round-trip.
        if (query.isNotEmpty() && currentFolderId != null) {
            currentFolderId = null
        }
        if (query.isEmpty()) {
            buildAppList()
            return
        }
        // Active filter: search GLOBAL apps. Also surface matching folder names so the user can
        // jump into a folder by name.
        val matchedApps = all.filter { it.name.contains(query, ignoreCase = true) }
        val matchedFolders = FolderStore.all(prefs)
            .filter { it.name.contains(query, ignoreCase = true) }
        // Compute visibleCount per matched folder so the Count style renders the same number
        // search results show as the main view would.
        val visiblePackages = all.mapTo(HashSet()) { it.packageName }
        val items: List<HomeItem> = matchedFolders.map { folder ->
            HomeItem.FolderItem(folder, folder.packages.count { it in visiblePackages })
        } + matchedApps.map { HomeItem.AppItem(it) }
        renderItems(items, all)
        fastScroll.visibility = View.GONE
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
        val items = repository.getHomeItems(folderId = currentFolderId)
        // For Flow-mode size scaling we want maxUsage relative to all installed apps, not just
        // the items currently in view (so size is stable across folder-enter/exit and matches
        // pre-folders behaviour).
        val allAppsForUsage = repository.getAllApps()
        renderItems(items, allAppsForUsage)
        // Fast scroll only makes sense for the flat main list; hide it inside folders.
        if (currentFolderId == null) configureFastScroll(allAppsForUsage)
        else fastScroll.visibility = View.GONE
    }

    /** Dispatch to the appropriate renderer based on the current view-mode pref. */
    private fun renderItems(items: List<HomeItem>, allAppsForUsage: List<AppInfo>) {
        flowLayout.removeAllViews()
        if (prefs.homescreenView == PreferencesManager.VIEW_LIST) {
            renderListMode(items)
        } else {
            val maxUsage = allAppsForUsage
                .maxOfOrNull { prefs.getUsageCount(it.packageName) }
                ?.takeIf { it > 0 } ?: 1
            renderFlowMode(items, maxUsage)
        }
    }

    /** Original word-cloud rendering: row wrap, font size scales with usage. */
    private fun renderFlowMode(items: List<HomeItem>, maxUsage: Int) {
        flowLayout.flexDirection = FlexDirection.ROW
        flowLayout.flexWrap = FlexWrap.WRAP
        flowLayout.alignItems = AlignItems.CENTER
        flowLayout.justifyContent = when (prefs.textAlignment) {
            "left" -> JustifyContent.FLEX_START
            "right" -> JustifyContent.FLEX_END
            else -> JustifyContent.CENTER
        }

        val density = resources.displayMetrics.density
        val defaultTextColor = parseColorSafe(prefs.appTextColor, Color.GRAY)
        val notifEnabled = prefs.notificationColorEnabled
        val notifColor = parseColorSafe(prefs.notificationHighlightColor)
        val typeface = buildTypeface()
        val hPad = (prefs.wordSpacing * density).toInt()
        val vPad = (prefs.lineSpacing * density).toInt()

        items.forEach { item ->
            val tv = buildItemView(
                item = item,
                size = sizeForItem(item, maxUsage),
                defaultTextColor = defaultTextColor,
                notifEnabled = notifEnabled,
                notifColor = notifColor,
                typeface = typeface,
                hPad = hPad, vPad = vPad,
                gravity = Gravity.CENTER
            )
            flowLayout.addView(tv)
        }
    }

    /** Minimal list rendering: one item per line at a uniform size (= maxFontSize). */
    private fun renderListMode(items: List<HomeItem>) {
        flowLayout.flexDirection = FlexDirection.COLUMN
        flowLayout.flexWrap = FlexWrap.NOWRAP
        // Wrap-to-content alignment (NOT STRETCH) so each TextView's touch area covers only the
        // text + padding, leaving the rest of the row as true blank space that propagates the
        // long-press to the ScrollView → home long-press dialog (Customize / Hidden Apps / FAQ).
        flowLayout.alignItems = when (prefs.textAlignment) {
            "left" -> AlignItems.FLEX_START
            "right" -> AlignItems.FLEX_END
            else -> AlignItems.CENTER
        }
        flowLayout.justifyContent = JustifyContent.FLEX_START

        val density = resources.displayMetrics.density
        val defaultTextColor = parseColorSafe(prefs.appTextColor, Color.GRAY)
        val notifEnabled = prefs.notificationColorEnabled
        val notifColor = parseColorSafe(prefs.notificationHighlightColor)
        val typeface = buildTypeface()
        val fontSize = prefs.maxFontSize.toFloat()
        val hPad = (prefs.wordSpacing * density).toInt()
        val vPad = (prefs.lineSpacing * density).toInt()

        items.forEach { item ->
            val tv = buildItemView(
                item = item,
                size = fontSize,
                defaultTextColor = defaultTextColor,
                notifEnabled = notifEnabled,
                notifColor = notifColor,
                typeface = typeface,
                hPad = hPad, vPad = vPad,
                gravity = Gravity.CENTER_VERTICAL
            )
            flowLayout.addView(tv)
        }
    }

    /** Resolve the font size for an item in Flow mode (folders weighted by aggregate usage). */
    private fun sizeForItem(item: HomeItem, maxUsage: Int): Float = when (item) {
        is HomeItem.AppItem -> computeFontSize(prefs.getUsageCount(item.info.packageName), maxUsage)
        is HomeItem.FolderItem ->
            computeFontSize(item.folder.packages.sumOf { prefs.getUsageCount(it) }, maxUsage)
        // "‹ back" is an affordance, not a data row — render at the minimum size so it doesn't
        // dominate the paragraph.
        HomeItem.BackOut -> prefs.minFontSize.toFloat()
    }

    /** Dispatcher that produces a TextView for any [HomeItem]. */
    private fun buildItemView(
        item: HomeItem,
        size: Float,
        defaultTextColor: Int,
        notifEnabled: Boolean,
        notifColor: Int,
        typeface: Typeface,
        hPad: Int,
        vPad: Int,
        gravity: Int
    ): TextView = when (item) {
        is HomeItem.AppItem -> createAppTextView(
            app = item.info,
            size = size,
            color = colorForApp(item.info, defaultTextColor, notifEnabled, notifColor),
            typeface = typeface,
            hPad = hPad, vPad = vPad, gravity = gravity
        )
        is HomeItem.FolderItem -> createFolderTextView(
            folder = item.folder,
            visibleCount = item.visibleCount,
            size = size,
            defaultColor = defaultTextColor,
            typeface = typeface,
            hPad = hPad, vPad = vPad, gravity = gravity
        )
        HomeItem.BackOut -> createBackOutTextView(
            size = size,
            color = defaultTextColor,
            typeface = typeface,
            hPad = hPad, vPad = vPad, gravity = gravity
        )
    }

    private fun createFolderTextView(
        folder: Folder,
        visibleCount: Int,
        size: Float,
        defaultColor: Int,
        typeface: Typeface,
        hPad: Int,
        vPad: Int,
        gravity: Int
    ): TextView = TextView(requireContext()).apply {
        // Marker style is user-selectable; folderLabel composes the final string. The NBSP in
        // the chevron form keeps the marker glued to the name when Flow wraps mid-paragraph.
        text = folderLabel(folder, visibleCount)
        textSize = size
        val color = folder.color?.let { parseColorSafe(it, defaultColor) } ?: defaultColor
        setTextColor(color)
        this.typeface = typeface
        this.gravity = gravity
        setPadding(hPad, vPad, hPad, vPad)
        setOnClickListener { enterFolder(folder.id) }
        setOnLongClickListener { showFolderMenu(folder, this); true }
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = true
            singleFingerDetector.onTouchEvent(event)
            false
        }
    }

    /**
     * Compose the folder label per [PreferencesManager.folderStyle]. Any unrecognised stored
     * value (e.g., from a future style we later remove) falls through to the chevron default
     * rather than rendering an empty marker — never breaks the layout. NBSP (U+00A0) keeps the
     * chevron glued to the name when Flow wraps mid-paragraph.
     */
    private fun folderLabel(folder: Folder, visibleCount: Int): String =
        when (prefs.folderStyle) {
            PreferencesManager.FOLDER_STYLE_SLASH    -> "${folder.name}/"
            PreferencesManager.FOLDER_STYLE_BULLET   -> "• ${folder.name}"
            PreferencesManager.FOLDER_STYLE_BRACKETS -> "[${folder.name}]"
            PreferencesManager.FOLDER_STYLE_COUNT    -> "${folder.name} ($visibleCount)"
            PreferencesManager.FOLDER_STYLE_PLAIN    -> folder.name
            else                                     -> "${folder.name} ›"
        }

    private fun createBackOutTextView(
        size: Float,
        color: Int,
        typeface: Typeface,
        hPad: Int,
        vPad: Int,
        gravity: Int
    ): TextView = TextView(requireContext()).apply {
        text = "‹ back"
        textSize = size
        setTextColor(color)
        this.typeface = typeface
        this.gravity = gravity
        alpha = 0.7f
        setPadding(hPad, vPad, hPad, vPad)
        setOnClickListener { exitFolder() }
        // No long-press menu — back is purely an affordance.
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = true
            singleFingerDetector.onTouchEvent(event)
            false
        }
    }

    private fun enterFolder(folderId: String) {
        currentFolderId = folderId
        // Close transient search overlay; for an always-visible search bar, just clear the text
        // so the user doesn't see a stale query above the folder contents.
        if (isSearchOpen && !prefs.showSearchBarOnHome) {
            closeSearch()
        } else if (searchInput.text.isNotEmpty()) {
            // Detach the watcher briefly so clearing text doesn't re-trigger filterApps and
            // bounce us back out of the folder we just entered.
            searchInput.setText("")
        }
        buildAppList()
        scrollView.scrollTo(0, 0)
    }

    private fun exitFolder() {
        currentFolderId = null
        buildAppList()
        scrollView.scrollTo(0, 0)
    }

    private fun colorForApp(
        app: AppInfo,
        defaultTextColor: Int,
        notifEnabled: Boolean,
        notifColor: Int
    ): Int {
        val hasNotif = notifEnabled && app.packageName in SlateNotificationService.activePackages
        if (hasNotif) return notifColor
        val appColor = prefs.getAppTextColor(app.packageName)
        return if (appColor != null) parseColorSafe(appColor) else defaultTextColor
    }

    private fun createAppTextView(
        app: AppInfo,
        size: Float,
        color: Int,
        typeface: Typeface,
        hPad: Int,
        vPad: Int,
        gravity: Int
    ): TextView = TextView(requireContext()).apply {
        text = app.name
        textSize = size
        setTextColor(color)
        this.typeface = typeface
        this.gravity = gravity
        setPadding(hPad, vPad, hPad, vPad)
        setOnClickListener { launchApp(app) }
        setOnLongClickListener { showAppMenu(app, this); true }
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = true
            singleFingerDetector.onTouchEvent(event)
            false
        }
    }

    // Fast scroll only operates over an alphabetical list, so it's mutually exclusive with
    // Sort by usage. We preserve `prefs.alphabeticalFastScroll` even when Sort by usage is on
    // (so the toggle re-lights at the user's previous position when they switch sort modes),
    // but this getter is the single source of truth for the render path — returning false here
    // suppresses both the fast-scroll widget and the forced-alphabetical override in
    // AppRepository, so Sort by usage takes effect immediately when the user enables it.
    private fun useFastScroll(): Boolean =
        prefs.homescreenView == PreferencesManager.VIEW_LIST &&
        prefs.alphabeticalFastScroll &&
        !prefs.sortByUsage

    /**
     * Resolve the apps-list typeface. `fontFamily` defaults to a non-empty Google Font key, so
     * [Typography.buildTypeface] never returns null here — the `?: Typeface.DEFAULT` fallback
     * is defensive only (would only trip if a future code path wrote both sentinels to the apps'
     * pref).
     */
    private fun buildTypeface(): Typeface =
        Typography.buildTypeface(requireContext(), prefs.fontFamily, prefs.fontWeight)
            ?: Typeface.DEFAULT

    private fun computeFontSize(usage: Int, maxUsage: Int): Float {
        // Folders' aggregate usage can exceed any single app's maxUsage; clamp so neither folders
        // nor unusually-pinned items ever render above the user's chosen maxFontSize.
        val ratio = (usage.toFloat() / maxUsage).coerceIn(0f, 1f)
        return prefs.minFontSize + ratio * (prefs.maxFontSize - prefs.minFontSize)
    }

    // ── Fast scroll ───────────────────────────────────────────────

    private fun setupFastScroll() {
        fastScroll.onLetterTouched = { letter ->
            fastScrollBubble.text = letter.toString()
            scrollToLetter(letter)
        }
        fastScroll.onTouchStateChanged = { active ->
            if (active) {
                fastScrollBubble.animate().cancel()
                fastScrollBubble.alpha = 1f
                fastScrollBubble.visibility = View.VISIBLE
            } else {
                fastScrollBubble.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction { fastScrollBubble.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun configureFastScroll(apps: List<AppInfo>) {
        if (!useFastScroll()) {
            fastScroll.visibility = View.GONE
            return
        }
        val letters = apps
            .mapNotNull { it.name.firstOrNull()?.uppercaseChar() }
            .filter { it in 'A'..'Z' }
            .distinct()
            .sorted()
        if (letters.size < 3) {
            fastScroll.visibility = View.GONE
            return
        }
        val color = parseColorSafe(prefs.appTextColor, Color.GRAY)
        fastScroll.textColor = color
        fastScrollBubble.setTextColor(color)
        fastScroll.setLetters(letters)
        fastScroll.visibility = View.VISIBLE
    }

    /** Scroll the ScrollView so the first app whose name starts with [letter] is at the top. */
    private fun scrollToLetter(letter: Char) {
        // Children are added after layout has completed when buildAppList runs in onResume;
        // post ensures we read measured positions.
        flowLayout.post {
            for (i in 0 until flowLayout.childCount) {
                val child = flowLayout.getChildAt(i) as? TextView ?: continue
                val first = child.text?.firstOrNull()?.uppercaseChar() ?: continue
                if (first == letter) {
                    scrollView.smoothScrollTo(0, yOffsetInScrollView(child))
                    return@post
                }
            }
        }
    }

    /** Walks up parents from [child] until [scrollView], summing top offsets. */
    private fun yOffsetInScrollView(child: View): Int {
        var y = 0
        var v: View = child
        while (v !== scrollView) {
            y += v.top
            val parentView = v.parent as? View ?: break
            v = parentView
        }
        return y
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
        val isPinned = prefs.isPinned(app.packageName)
        val pinLabel = if (isPinned) "Unpin" else "Pin to top"
        val containingFolder = FolderStore.folderContaining(prefs, app.packageName)
        // Build the menu dynamically so folder entries appear only where relevant. Dispatching
        // on the chosen label avoids fragile index-based branching as items shift.
        val items = buildList {
            add(pinLabel)
            add("App Info")
            add("Hide")
            add("Uninstall")
            if (containingFolder != null) {
                add("Move to another folder")
                add("Remove from folder")
            } else {
                add("Move to folder")
            }
            add("Custom color")
            add("Rename")
        }
        SlateListDialog(
            context = requireContext(),
            title = app.name,
            items = items,
            bgColor = prefs.backgroundColor
        ) { _, label ->
            when (label) {
                "Pin to top" -> {
                    // Remove from folder FIRST so the "pinned ⊥ in-folder" invariant holds at
                    // every persistence intermediate, never just at the end of the sequence.
                    FolderStore.removeAppFromFolder(prefs, app.packageName)
                    prefs.pinApp(app.packageName)
                    buildAppList()
                }
                "Unpin" -> { prefs.unpinApp(app.packageName); buildAppList() }
                "App Info" -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", app.packageName, null)
                    }
                )
                "Hide" -> { prefs.hideApp(app.packageName); buildAppList() }
                "Uninstall" -> startActivity(
                    Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.fromParts("package", app.packageName, null)
                    }
                )
                "Move to folder", "Move to another folder" -> showMoveToFolderDialog(app)
                "Remove from folder" -> {
                    FolderStore.removeAppFromFolder(prefs, app.packageName)
                    // If we were inside the now-empty folder, exitFolder navigates back; otherwise
                    // a plain rebuild is enough.
                    if (currentFolderId != null && FolderStore.find(prefs, currentFolderId!!) == null) {
                        exitFolder()
                    } else {
                        buildAppList()
                    }
                }
                "Custom color" -> showAppColorPicker(app)
                "Rename" -> showRenameDialog(app)
            }
        }.show()
    }

    /** Sub-menu listing existing folders + a "+ New folder" entry. */
    private fun showMoveToFolderDialog(app: AppInfo) {
        val existing = FolderStore.all(prefs)
        val items = existing.map { it.name } + "+ New folder"
        SlateListDialog(
            context = requireContext(),
            title = "Move to folder",
            items = items,
            bgColor = prefs.backgroundColor
        ) { index, _ ->
            if (index < existing.size) {
                FolderStore.addAppToFolder(prefs, existing[index].id, app.packageName)
                buildAppList()
            } else {
                showCreateFolderDialog { newName ->
                    val folder = FolderStore.createEmpty(prefs, newName)
                    FolderStore.addAppToFolder(prefs, folder.id, app.packageName)
                    buildAppList()
                }
            }
        }.show()
    }

    /** Long-press on a folder label — Rename / Delete / Custom color. */
    private fun showFolderMenu(folder: Folder, anchor: View) {
        SlateListDialog(
            context = requireContext(),
            title = folder.name,
            items = listOf("Rename", "Custom color", "Delete folder"),
            bgColor = prefs.backgroundColor
        ) { _, label ->
            when (label) {
                "Rename" -> showRenameFolderDialog(folder)
                "Custom color" -> showFolderColorPicker(folder)
                "Delete folder" -> showDeleteFolderConfirm(folder)
            }
        }.show()
    }

    /** Reusable text-input dialog used by folder creation and folder rename. */
    private fun showFolderNameDialog(
        title: String,
        initial: String = "",
        confirmLabel: String = "Save",
        onConfirm: (String) -> Unit
    ) {
        val ctx = requireContext()
        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#888888")
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
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(accent)
            setPadding(hPad, vPad, hPad, vPad)
        })

        val inputFill = if (isLight) Color.parseColor("#EBEBEB") else Color.parseColor("#1E1E1E")
        val inputStroke = if (isLight) Color.parseColor("#CCCCCC") else Color.parseColor("#4A4A4A")
        val input = android.widget.EditText(ctx).apply {
            setText(initial)
            textSize = 17f
            setTextColor(primary)
            setHintTextColor(secondary)
            hint = "Folder name"
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
                it.marginStart = hPad; it.marginEnd = hPad
                it.topMargin = (12 * density).toInt(); it.bottomMargin = (12 * density).toInt()
            }
            selectAll()
        }
        root.addView(input)

        val dialog = Dialog(ctx, R.style.SlateDialogTheme)
        val bHPad = (20 * density).toInt()
        val bVPad = (15 * density).toInt()
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(hPad, 0, hPad, (16 * density).toInt())
        }
        buttonRow.addView(TextView(ctx).apply {
            text = "Cancel"
            textSize = 15f
            setTextColor(secondary)
            setPadding(bHPad, bVPad, bHPad, bVPad)
            setOnClickListener { dialog.dismiss() }
        })
        buttonRow.addView(TextView(ctx).apply {
            text = confirmLabel
            textSize = 15f
            setTextColor(accent)
            setPadding(bHPad, bVPad, bHPad, bVPad)
            setOnClickListener {
                val typed = input.text.toString().trim()
                if (typed.isEmpty()) {
                    Toast.makeText(ctx, "Name can't be empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onConfirm(typed)
            }
        })
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        dialog.window?.setLayout(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        // Tell the window manager to bring the IME up alongside the dialog. The postDelayed
        // showSoftInput is a belt-and-suspenders fallback for OEMs that ignore the soft-input
        // mode hint.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        input.requestFocus()
        input.postDelayed({
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun showCreateFolderDialog(onCreated: (String) -> Unit) {
        showFolderNameDialog(title = "New folder", confirmLabel = "Create", onConfirm = onCreated)
    }

    private fun showRenameFolderDialog(folder: Folder) {
        showFolderNameDialog(
            title = "Rename folder",
            initial = folder.name,
            confirmLabel = "Save"
        ) { newName ->
            FolderStore.rename(prefs, folder.id, newName)
            buildAppList()
        }
    }

    private fun showFolderColorPicker(folder: Folder) {
        ColorPickerDialog(
            context = requireContext(),
            title = "Folder color",
            initialColor = folder.color ?: prefs.appTextColor,
            bgColor = prefs.backgroundColor
        ) { hex ->
            FolderStore.setColor(prefs, folder.id, hex)
            buildAppList()
        }.show()
    }

    private fun showDeleteFolderConfirm(folder: Folder) {
        // Reuses the accessibility-info dialog layout (title / body / two buttons) so the
        // confirm is unambiguous and doesn't render the body as a tappable list row.
        val dialog = Dialog(requireContext(), R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )
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

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup
            ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }
        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = "DELETE FOLDER"
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = "Delete \"${folder.name}\"? Its apps will return to the main list."
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.visibility = View.GONE
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = "Delete"
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                FolderStore.delete(prefs, folder.id)
                if (currentFolderId == folder.id) exitFolder() else buildAppList()
            }
        }
        dialog.show()
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
                1 -> AuthGate.authenticate(
                    activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                    prefs = prefs,
                    pinManager = PinManager(prefs),
                    title = "Hidden Apps",
                    onSuccess = { showHiddenAppsDialog() }
                )
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
                "• EXPAND_STATUS_BAR — swipe-down notification panel gesture\n• ACCESS_WIFI_STATE / CHANGE_WIFI_STATE — Wi-Fi toggle gesture (Android 10+: opens system panel)\n• BLUETOOTH / BLUETOOTH_ADMIN — Bluetooth toggle on Android 11 and below\n• QUERY_ALL_PACKAGES — required to list all installed apps (Android 11+)\n• REQUEST_DELETE_PACKAGES — initiates the system uninstall flow when you choose to uninstall an app\n• REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — used only when you tap \"Fix this\" on the battery restriction warning in Settings, to request that the system exempt Slate from battery optimization so background features keep working\n• USE_BIOMETRIC — declared by the AndroidX Biometric library; only requested when you opt into biometric unlock for hidden apps. Biometric data is processed by the OS and never reaches Slate.",

            "How does the hidden apps lock work?" to
                "Turning on \"Lock hidden apps\" in Settings → Security asks you to set a 4–8 digit PIN. After that, opening the Hidden Apps dialog from the home long-press menu requires PIN (or biometric, if you opt in).\n\nYour PIN is never stored in plain text. Slate stores a salted PBKDF2-HMAC-SHA256 hash with 120,000 iterations and a per-device random 16-byte salt. The hash is a one-way verifier — even with the file, an attacker would have to brute-force the PIN.\n\nBiometric is optional. When enabled, Slate uses Android's BiometricPrompt to show the standard fingerprint/face dialog. Biometric data stays inside the OS and Slate only sees a success/fail signal.\n\nAfter 5 wrong PIN attempts you're locked out for 30 seconds; 10 wrong for 5 minutes; 15 wrong for 15 minutes. There is no PIN recovery — clearing app data is the only reset.",

            "How do folders work?" to
                "Long-press any app and choose \"Move to folder\" to add it to an existing folder, or pick \"+ New folder\" to create one on the spot. Folders appear on the home screen with a marker (chevron, bullet, brackets, slash, count, or plain — pick your style in Settings → Typography → Folder style). Tap to expand inline — the home list is replaced by the folder's apps with a leading ‹ back row. Tap back (or press the system back gesture) to return.\n\nEach app lives in at most one folder. Apps inside a folder are hidden from the main list to reduce clutter — search still finds them globally, and the folder name itself also appears in search results.\n\nLong-press a folder label to rename, set a custom color, or delete. Deleting a folder returns its apps to the main list; the apps themselves are never removed. Pinning an app automatically removes it from any folder it was in. If you uninstall an app, it disappears from its folder; empty folders are pruned automatically.",

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

        // Forward-reference the dialog so the long-press → confirm path can dismiss it on
        // successful unhide. The lambda only fires after the user interacts, by which time
        // `parent` is set; the nullable type is a Kotlin formality for the self-reference.
        var parent: SlateListDialog? = null
        parent = SlateListDialog(
            context = requireContext(),
            title = "Hidden Apps — tap to open, hold to unhide",
            items = hidden.map { it.first },
            bgColor = prefs.backgroundColor,
            onItemLongPress = { index, _ ->
                showUnhideConfirm(
                    name = hidden[index].first,
                    pkg = hidden[index].second
                ) {
                    parent?.dismiss()
                    buildAppList()
                }
            }
        ) { index, _ ->
            launchHiddenApp(hidden[index].second)
            // SlateListDialog auto-dismisses after the tap callback.
        }
        parent.show()
    }

    /**
     * Launch a package from the Hidden Apps dialog. Mirrors [launchApp] but accepts a raw
     * package — the Hidden Apps dialog tracks (displayName, pkg) pairs rather than full
     * [AppInfo] objects. The null-intent branch is defensive: the list is filtered for
     * installed apps at open time, so this only trips if an uninstall raced with the tap.
     */
    private fun launchHiddenApp(pkg: String) {
        prefs.incrementUsage(pkg)
        SlateNotificationService.activePackages.remove(pkg)
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(requireContext(), "App not installed", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    /**
     * Confirmation dialog before unhiding an app — guards against a misclick on the Hidden
     * Apps long-press. Reuses the accessibility-info dialog layout (title / body / two
     * buttons), the same template as [showDeleteFolderConfirm].
     */
    private fun showUnhideConfirm(name: String, pkg: String, onConfirmed: () -> Unit) {
        val dialog = Dialog(requireContext(), R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_accessibility_info)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )
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

        val root = dialog.findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup
            ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }
        dialog.findViewById<TextView>(R.id.dialogTitle)?.apply {
            text = "UNHIDE APP"
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
            text = "Unhide \"$name\"? It will return to your main app list."
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.dialogPrivacy)?.visibility = View.GONE
        dialog.findViewById<TextView>(R.id.btnCancel)?.apply {
            setTextColor(secondary)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<TextView>(R.id.btnContinue)?.apply {
            text = "Unhide"
            setTextColor(accent)
            setOnClickListener {
                dialog.dismiss()
                prefs.unhideApp(pkg)
                onConfirmed()
            }
        }
        dialog.show()
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
