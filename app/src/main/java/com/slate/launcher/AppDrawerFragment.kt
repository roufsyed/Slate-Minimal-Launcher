package com.slate.launcher

import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
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
import com.slate.launcher.shortcuts.PinnedShortcut
import com.slate.launcher.shortcuts.PinnedShortcutStore
import com.slate.launcher.shortcuts.ShortcutDestination
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
    /**
     * Reference to the currently-showing FAQ detail dialog (if any) so the fragment can dismiss
     * it in onDestroyView and avoid a WindowLeaked exception when the activity is recreated
     * (e.g. on configuration change) while the dialog is open. Mirrors the pattern used by
     * [PrivacyPolicyDialog.activeDialog].
     */
    private var activeFaqDetailDialog: Dialog? = null
    private lateinit var singleFingerDetector: GestureDetector

    /**
     * Main-thread handler used to debounce the contact-search query off the keystroke storm.
     * Established async primitive in this codebase (QuickStripManager, SystemWidgets,
     * GuidedTourManager all use the same Handler+postDelayed pattern). Cleared in
     * onDestroyView so a late-fire after view teardown can't crash.
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingContactQuery: Runnable? = null
    /** Pending work-grouping re-check. See [scheduleWorkGroupingRecheck]. */
    private var workGroupingRecheck: Runnable? = null
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
        // `touchStartedOnApp` flag on DOWN matches the blank-home-space semantics - long-press
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
                    // show the home menu - that would race with the freshly-fired call intent
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
                    //   1. A strip widget - `touchStartedOnApp` is false here (the touchForwarder
                    //      clears it), so we explicitly hit-test the strip. Without this guard,
                    //      rapid widget toggling (e.g., torch on → torch off within 300 ms)
                    //      would accidentally lock the user's phone.
                    //   2. An app / folder / back-out row - `touchStartedOnApp` is true (set by
                    //      the row's own setOnTouchListener). In practice the first tap launches
                    //      the app and the second tap goes to the launched app, so this branch
                    //      rarely fires - but the check is defensive symmetry with onLongPress.
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
                    // Require mostly straight swipe - secondary axis < 65% of primary
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
            // notch). `getInsets(typeA or typeB)` returns the per-edge UNION/max - so when the
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
            // Same cutout-union treatment at the bottom - handles the (rare) bottom display
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
        registerProfileReceiver()
        scheduleWorkGroupingRecheck(
            WorkGrouping.maybeGroupWorkAppsOnce(
                requireContext(), prefs, repository.workAppsForGrouping()
            )
        )
        // A broadcast missed while Slate was not resumed costs nothing: all the state logic
        // lives in the rebuild path and onResume rebuilds anyway. This only keeps a resumed
        // launcher live while the user pauses work apps from the shade.
        repository.invalidateWorkCache()
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
        // Always land on the main list when returning to home - folder state is a transient
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
        // Contact-search reconcile: if READ_CONTACTS was revoked from system Settings while
        // the launcher was in the background, flip the pref off silently so subsequent
        // searches take the apps-only path until the user re-opts in.
        reconcileContactSearchPref()
        // Tier 2 (expensive, per-shortcut IPC) health check for pinned shortcuts - throttled to
        // once per 60s internally, runs on a background thread, only rebuilds the visible UI if
        // something actually changed (label refreshed, a shortcut went stale, or one was dropped).
        PinnedShortcutStore.performHealthCheckIfDue(requireContext(), prefs) {
            if (isAdded) {
                buildAppList()
                quickStrip?.bind()
            }
        }
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
     * The 500ms re-check mirrors Settings - protects against an OEM where the secure-setting
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
        profileReceiver?.let { runCatching { requireContext().unregisterReceiver(it) } }
        profileReceiver = null
        fastScrollBubble.animate().cancel()
        quickStrip?.stop()
    }

    override fun onDestroyView() {
        // Drop the QuickStripManager's reference to the (now-defunct) FlexboxLayout, and make
        // sure any straggling observers are unregistered. onPause should always have fired first,
        // but defensive cleanup costs nothing.
        quickStrip?.stop()
        quickStrip = null
        // Tear down the FAQ detail dialog if it survived to view-destroy - without this, a
        // configuration change while it was open would leak the window (WindowLeaked).
        activeFaqDetailDialog?.let { runCatching { it.dismiss() } }
        activeFaqDetailDialog = null
        // Clear any pending debounced contact query so a late-fire post-teardown can't run
        // requireContext() / requireView() against a destroyed view tree.
        mainHandler.removeCallbacksAndMessages(null)
        pendingContactQuery = null
        workGroupingRecheck = null
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
        // Without this, adjustResize shrinks the window and the strip - pinned to the bottom
        // edge of the root LinearLayout via the weight=1 FrameLayout above it - rides up onto
        // the keyboard's leading edge. The strip's "intended" visibility (configured + enabled
        // widgets exist) is owned by QuickStripManager.bind(); applyChromeLayout layers this
        // contextual GONE on top. Inset routing below already filters by visibility, so a GONE
        // strip transparently hands the bottomInset off to the next visible child (FrameLayout).
        val stripIntended =
            quickStrip?.hasActiveWidgets() == true && prefs.quickStripEnabled
        // Hide-conditions: (1) IME visible - the post-keyboard-up safety net (also covers the
        // persistent-search-bar mode where a tap-into-search bypasses openSearch); (2) search
        // open AND not in persistent-search-bar mode - the pre-IME branch that lets openSearch
        // collapse the strip into the same layout pass as the search-container reveal, BEFORE
        // adjustResize starts animating, so the IME slides up against a static layout instead
        // of one mid-flip. The !showSearchBarOnHome guard preserves persistent-bar behaviour
        // where isSearchOpen is permanently true (set in onResume) but the strip should still
        // be visible alongside the always-on bar at rest.
        val searchHidesStrip = isSearchOpen && !prefs.showSearchBarOnHome
        val stripEffective = stripIntended && !isImeVisible && !searchHidesStrip
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

        // The divider always rides immediately adjacent to the strip on its INNER edge - below
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
        // bar / GONE divider must not absorb the inset - the next visible child gets it instead.
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
        // Commit the chrome relayout SYNCHRONOUSLY here - strip-hide, search-container-visible,
        // and any status-bar / nav-bar inset routing change all collapse into a single measure
        // pass before the postDelayed showSoftInput fires the IME animation. Without this, the
        // strip's GONE flip was happening mid-IME-animation (via the inset-listener path),
        // which collapsed the strip's allocated layout space while the window was still
        // resizing - a one-frame jolt the user perceived as choppy.
        applyChromeLayout()
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
        // keyboard was actually up - code paths that call closeSearch without ever having
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
        // Starting to type drops folder context entirely - the user is now searching globally,
        // and clearing the query should restore the main list rather than a half-remembered
        // folder view. This makes "type → clear" a predictable round-trip.
        if (query.isNotEmpty() && currentFolderId != null) {
            currentFolderId = null
        }
        // Cancel any pending contact query whenever the query changes - keystrokes thrash the
        // debounce; an empty query clears it entirely.
        pendingContactQuery?.let { mainHandler.removeCallbacks(it) }
        pendingContactQuery = null
        if (query.isEmpty()) {
            buildAppList()
            return
        }
        // Active filter: search GLOBAL apps. Also surface matching folder names so the user can
        // jump into a folder by name.
        // Matching stays on the plain name, so "gma" still finds both Gmails. Typing three or
        // more characters of a profile's marker additionally lists that profile's apps; shorter
        // prefixes would make a single letter surface every work app.
        val matchedApps = all.filter {
            it.name.contains(query, ignoreCase = true) ||
                (query.length >= 3 &&
                    it.profile?.label?.startsWith(query, ignoreCase = true) == true)
        }
        val matchedFolders = FolderStore.all(prefs)
            .filter { it.name.contains(query, ignoreCase = true) }
        // Compute visibleCount per matched folder so the Count style renders the same number
        // search results show as the main view would.
        val visibleKeys = all.mapTo(HashSet()) { it.key }
        // Pinned shortcuts don't come from AppRepository.getAllApps() (a plain AppInfo list), so
        // they need their own match set here - adding a ShortcutItem case to the HomeItem
        // `when` blocks does NOT make them searchable on its own.
        val matchedShortcuts = PinnedShortcutStore.all(prefs)
            .filter { ShortcutDestination.APP_LIST in it.destinations && it.pinnedLabel.contains(query, ignoreCase = true) }
            .map { HomeItem.ShortcutItem(it) }
        val baseItems: List<HomeItem> = matchedFolders.map { folder ->
            HomeItem.FolderItem(folder, folder.packages.count { it in visibleKeys })
        } + matchedApps.map { HomeItem.AppItem(it) } + matchedShortcuts
        // Render apps + folders synchronously - Slate's primary purpose is apps, that path
        // can't wait on ContentProvider I/O.
        renderItems(baseItems, all)
        fastScroll.visibility = View.GONE

        // Contact search runs on a 250ms debounce so the keystroke storm doesn't hammer
        // ContactsContract once per character. Gated on the user opt-in toggle AND the live
        // READ_CONTACTS grant - the latter catches the case where the user revoked the
        // permission via system Settings between the toggle write and now. The debounce
        // window matches AOSP's Filter.MESSAGE_REQUEST_DELAY precedent (300ms).
        if (!prefs.contactSearchEnabled) return
        val hasContacts = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) return

        val capturedQuery = query
        val runnable = Runnable {
            // The view may be torn down between the postDelayed and the fire; bail if so to
            // avoid requireContext() / requireView() blowing up.
            if (!isAdded || view == null) return@Runnable
            val contacts = queryContacts(capturedQuery)
            // Re-render the merged list. Apps + folders first (already visible), contacts at
            // the tail - contacts are the bonus, apps are the launcher's primary purpose.
            if (contacts.isNotEmpty()) {
                renderItems(baseItems + contacts, all)
            }
        }
        pendingContactQuery = runnable
        mainHandler.postDelayed(runnable, 250L)
    }

    /**
     * Query the system Contacts provider for rows whose display name or normalised number
     * matches [query] as a substring. Limited to 10 rows. Returns one [HomeItem.ContactItem]
     * per phone-number row (so a contact with three numbers contributes three results, each
     * disambiguated by [HomeItem.ContactItem.typeLabel]).
     *
     * Queries `Phone.CONTENT_URI` directly because it includes ONLY contacts that have at
     * least one phone number - contacts with email only are silently excluded, which matches
     * the tap behaviour (we dial via `ACTION_DIAL`, so no-phone contacts have nothing to do).
     *
     * Number-side matching uses `NORMALIZED_NUMBER` (digits-only canonical form) with the
     * digit-only filter on the query string, so a user typing `5551234` matches stored
     * `(555) 123-4567` despite the punctuation difference.
     *
     * Type label disambiguation: a contact with multiple matched numbers gets each of its
     * rows labelled with the localised Phone.TYPE label (mobile / work / home / custom). A
     * contact with a single matched number renders with `typeLabel = null` so the row reads
     * as just the bare display name - phone numbers are NEVER shown on the search surface.
     *
     * On `SecurityException` (permission revoked between the gate check and the cursor open):
     * returns empty + posts a reconcile to flip the pref off cleanly.
     */
    private fun queryContacts(query: String): List<HomeItem.ContactItem> {
        val ctx = context ?: return emptyList()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ? " +
                "OR ${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ?"
        val digitsOnly = query.filter { it.isDigit() }
        val selectionArgs = arrayOf(
            "%$query%",
            if (digitsOnly.isEmpty()) "____no_digits____" else "%$digitsOnly%",
        )
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} " +
                "COLLATE NOCASE ASC LIMIT 10"

        // Intermediate row carrying everything we need to decide whether a contact is multi-
        // number (and thus needs a type-label suffix) without re-querying the provider. The
        // [rawContactId] is used in a follow-up batch query against RawContacts to resolve
        // the account source (google / whatsapp / sim / etc.) for each row, since duplicates
        // typically arise from the same person existing under multiple account sources.
        data class Raw(
            val contactId: Long,
            val rawContactId: Long,
            val name: String,
            val number: String,
            val type: Int,
            val customLabel: String?,
            val lookupUri: Uri,
        )

        val raws = runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val out = mutableListOf<Raw>()
                val nameIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY
                )
                val numberIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val lookupIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
                )
                val contactIdIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                )
                val rawContactIdIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID
                )
                val typeIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.TYPE
                )
                val labelIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.LABEL
                )
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                    val number = if (numberIdx >= 0) cursor.getString(numberIdx) ?: "" else ""
                    if (name.isBlank() || number.isBlank()) continue
                    val lookupKey = if (lookupIdx >= 0) cursor.getString(lookupIdx) ?: "" else ""
                    val contactId = if (contactIdIdx >= 0) cursor.getLong(contactIdIdx) else 0L
                    val rawContactId =
                        if (rawContactIdIdx >= 0) cursor.getLong(rawContactIdIdx) else 0L
                    val type = if (typeIdx >= 0) cursor.getInt(typeIdx) else 0
                    val custom = if (labelIdx >= 0) cursor.getString(labelIdx) else null
                    val lookupUri = if (lookupKey.isNotEmpty() && contactId > 0L) {
                        ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
                    } else Uri.EMPTY
                    out.add(Raw(contactId, rawContactId, name, number, type, custom, lookupUri))
                }
                out.toList()
            } ?: emptyList()
        }.getOrElse { err ->
            // SecurityException means the OS revoked the grant between our pre-check and the
            // cursor open. Reconcile silently and return empty so apps still render.
            if (err is SecurityException) {
                mainHandler.post { reconcileContactSearchPref() }
            }
            emptyList()
        }

        if (raws.isEmpty()) return emptyList()

        // Resolve account source per raw contact. Account info lives on RawContacts, not on
        // the Phone (Data) table - we batch one extra query keyed on the raw_contact_ids
        // we collected above. The result is a small map (≤ 10 entries) used to filter the
        // result set to Google-sourced contacts only (see below) and folded into each row's
        // [accountSource] for the accessibility label.
        val sourceByRaw: Map<Long, String?> = run {
            val ids = raws.map { it.rawContactId }.filter { it > 0L }.distinct()
            if (ids.isEmpty()) emptyMap()
            else runCatching {
                val placeholders = ids.joinToString(",") { "?" }
                val out = HashMap<Long, String?>(ids.size)
                ctx.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.RawContacts._ID,
                        ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ),
                    "${ContactsContract.RawContacts._ID} IN ($placeholders)",
                    ids.map { it.toString() }.toTypedArray(),
                    null
                )?.use { c ->
                    val idIdx = c.getColumnIndex(ContactsContract.RawContacts._ID)
                    val typeIdx = c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    while (c.moveToNext()) {
                        val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                        val accType = if (typeIdx >= 0) c.getString(typeIdx) else null
                        out[id] = friendlyAccountSource(accType)
                    }
                }
                out
            }.getOrDefault(emptyMap())
        }

        // Pref-driven source filter. When the user has opted into "Google contacts only"
        // (default OFF), drop all non-Google raws so duplicates from WhatsApp / Telegram /
        // SIM / OEM-local sources are hidden. When the toggle is OFF (the default), every
        // matched source comes through; the per-row source suffix below disambiguates the
        // rare cross-source duplicates.
        val filtered = if (prefs.googleContactsOnly) {
            raws.filter { sourceByRaw[it.rawContactId] == "google" }
        } else raws
        if (filtered.isEmpty()) return emptyList()

        // Count matched-number rows per contact: a contact with >1 row gets a per-row type
        // label to disambiguate; a contact with exactly one row renders as just its name.
        // We intentionally do NOT expose the contact's source account on the rendered row -
        // every result reads the same shape. Source-level filtering still happens above via
        // `prefs.googleContactsOnly`, but the source itself is silent on the surface.
        val rowsPerContact = filtered.groupingBy { it.contactId }.eachCount()
        val resources = ctx.resources
        return filtered.map { row ->
            val label = if ((rowsPerContact[row.contactId] ?: 1) > 1) {
                ContactsContract.CommonDataKinds.Phone
                    .getTypeLabel(resources, row.type, row.customLabel)
                    ?.toString()
                    ?.lowercase()
            } else null
            HomeItem.ContactItem(
                displayName = row.name,
                number = row.number,
                typeLabel = label,
                lookupUri = row.lookupUri,
            )
        }
    }

    /**
     * Map a raw [account_type] reverse-DNS string to a short, lowercase, user-readable source
     * label. Covers the common cases (Google, WhatsApp, Telegram, Signal, OEM phone/SIM
     * stores) explicitly; falls back to the last `.`-separated segment for anything else
     * (e.g., `com.linkedin.android` → `linkedin`). Returns null when the account_type itself
     * is null or blank - usually means a local raw contact with no sync account.
     */
    private fun friendlyAccountSource(accountType: String?): String? {
        if (accountType.isNullOrBlank()) return "phone"
        return when {
            accountType == "com.google" -> "google"
            accountType == "com.whatsapp" -> "whatsapp"
            accountType == "com.whatsapp.w4b" -> "whatsapp business"
            accountType == "org.telegram.messenger" -> "telegram"
            accountType == "org.thoughtcrime.securesms" -> "signal"
            accountType == "com.viber.voip.account" -> "viber"
            accountType == "com.skype.contacts.sync" -> "skype"
            accountType.startsWith("vnd.sec.contact") -> "phone"
            accountType.contains("sim", ignoreCase = true) -> "sim"
            accountType.contains("xiaomi", ignoreCase = true) -> "xiaomi"
            accountType.contains("huawei", ignoreCase = true) -> "huawei"
            accountType.contains("oneplus", ignoreCase = true) -> "oneplus"
            accountType.contains("oppo", ignoreCase = true) -> "oppo"
            else -> accountType.substringAfterLast('.').lowercase()
        }
    }

    /**
     * Open the system dialer prepopulated with [number]. Never `ACTION_CALL` - that would
     * direct-dial on tap, the worst possible UX failure mode for a launcher. The user taps
     * the call button in the dialer to actually place the call.
     */
    private fun dialContact(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(requireContext(), "No dialer installed", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Reconcile the contact-search pref against the live READ_CONTACTS grant. Called from
     * onResume and from inside [queryContacts] on a mid-flight SecurityException. Silent -
     * matches the pattern of [reconcileDoubleTapPref] for the accessibility-driven lock.
     */
    private fun reconcileContactSearchPref() {
        if (!prefs.contactSearchEnabled) return
        val ctx = context ?: return
        val granted = ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) prefs.contactSearchEnabled = false
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
                val serial = AppKey.serialOf(action.key)
                val pkg = AppKey.packageOf(action.key)
                if (serial == null) {
                    val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) { startActivity(intent); true } else false
                } else if (!prefs.showWorkApps) {
                    // Work apps are switched off, so "off" has to mean off at every surface -
                    // including a gesture the user bound while they were on.
                    false
                } else {
                    val handle = WorkProfiles.handleForSerial(requireContext(), serial)
                    val launcher = launcherApps()
                    val component = if (handle == null || launcher == null) null else
                        runCatching {
                            launcher.getActivityList(pkg, handle).firstOrNull()?.componentName
                        }.getOrNull()
                    if (component == null || handle == null) false else
                        runCatching {
                            launcher?.startMainActivity(component, handle, null, null)
                        }.isSuccess
                }
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

    /**
     * The work marker style for THIS render pass.
     *
     * Normally [PreferencesManager.workMarkerStyle] verbatim. Collapses to
     * [PreferencesManager.WORK_MARKER_NONE] when the user has asked for it and every app in view
     * belongs to one and the same work profile - inside such a folder the marker repeats on
     * every row while distinguishing nothing, the one case where dropping it costs no
     * information.
     *
     * Keyed on "all one profile", deliberately NOT on "is this the auto-created Work folder".
     * That folder can be renamed, deleted, or hand-filled with personal apps, and the user can
     * build an all-work folder of their own, so the structural question is both unanswerable and
     * the wrong one. What matters is whether the marker still tells the user anything here.
     *
     * So a folder mixing personal and work Gmail keeps its markers, and so does one holding two
     * different work profiles - those are exactly the cases where the marker carries the
     * distinction it exists for.
     */
    private fun markerStyleFor(items: List<HomeItem>): String {
        val style = prefs.workMarkerStyle
        if (currentFolderId == null) return style
        if (style == PreferencesManager.WORK_MARKER_NONE) return style
        if (!prefs.suppressWorkMarkerInFolder) return style
        val serials = items.filterIsInstance<HomeItem.AppItem>().map { it.info.profile?.serial }
        // One elvis for two cases that both keep the style: an empty folder, where no row can
        // show a marker anyway, and a personal first row, which means the folder is either mixed
        // or entirely personal and already markerless.
        val first = serials.firstOrNull() ?: return style
        return if (serials.all { it == first }) PreferencesManager.WORK_MARKER_NONE else style
    }

    /** Dispatch to the appropriate renderer based on the current view-mode pref. */
    private fun renderItems(items: List<HomeItem>, allAppsForUsage: List<AppInfo>) {
        flowLayout.removeAllViews()
        if (prefs.homescreenView == PreferencesManager.VIEW_LIST) {
            renderListMode(items)
        } else {
            val maxUsage = allAppsForUsage
                .maxOfOrNull { prefs.getUsageCount(it.key) }
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
        // Which set to highlight from depends on a pref, so resolve it once per pass rather
        // than re-reading it for every row.
        val notifKeys =
            SlateNotificationService.highlightedKeys(prefs.ignoreSilentNotifications)
        val markerStyle = markerStyleFor(items)
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
                notifKeys = notifKeys,
                markerStyle = markerStyle,
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
        // Which set to highlight from depends on a pref, so resolve it once per pass rather
        // than re-reading it for every row.
        val notifKeys =
            SlateNotificationService.highlightedKeys(prefs.ignoreSilentNotifications)
        val markerStyle = markerStyleFor(items)
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
                notifKeys = notifKeys,
                markerStyle = markerStyle,
                typeface = typeface,
                hPad = hPad, vPad = vPad,
                gravity = Gravity.CENTER_VERTICAL
            )
            flowLayout.addView(tv)
        }
    }

    /** Resolve the font size for an item in Flow mode (folders weighted by aggregate usage). */
    private fun sizeForItem(item: HomeItem, maxUsage: Int): Float = when (item) {
        is HomeItem.AppItem -> computeFontSize(prefs.getUsageCount(item.info.key), maxUsage)
        is HomeItem.FolderItem ->
            computeFontSize(item.folder.packages.sumOf { prefs.getUsageCount(it) }, maxUsage)
        // Contact results carry no usage signal - render at the minimum (least-prominent)
        // size so they don't dominate the paragraph alongside frequently-used apps.
        is HomeItem.ContactItem -> prefs.minFontSize.toFloat()
        // Shortcuts carry no usage signal of their own for v1 - same minimum-weight precedent.
        is HomeItem.ShortcutItem -> prefs.minFontSize.toFloat()
        // "‹ back" is an affordance, not a data row - render at the minimum size so it doesn't
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
        notifKeys: Set<String>,
        markerStyle: String,
        typeface: Typeface,
        hPad: Int,
        vPad: Int,
        gravity: Int
    ): TextView = when (item) {
        is HomeItem.AppItem -> createAppTextView(
            app = item.info,
            size = size,
            color = colorForApp(
                item.info, defaultTextColor, notifEnabled, notifColor, notifKeys
            ),
            markerStyle = markerStyle,
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
        is HomeItem.ContactItem -> createContactTextView(
            contact = item,
            size = size,
            color = defaultTextColor,
            typeface = typeface,
            hPad = hPad, vPad = vPad, gravity = gravity
        )
        is HomeItem.ShortcutItem -> createShortcutTextView(
            shortcut = item.shortcut,
            size = size,
            defaultTextColor = defaultTextColor,
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

    /**
     * Render a contact search result. Format is `Name` for single-number contacts and
     * `Name (type)` for multi-number contacts where the type label disambiguates the row
     * (mobile / work / home / etc.). The phone number is never visible on the search
     * surface - tapping opens the dialer pre-populated with the number, which is where
     * the user sees and confirms it.
     *
     * Single-number contact rows render identically to apps named the same thing. This
     * is a deliberate UX trade-off: the user opted into contact search, accepts that
     * `Calendar` (the contact) and `Calendar` (the app) look alike, and a mis-tap is a
     * one-back-press recovery.
     */
    private fun createContactTextView(
        contact: HomeItem.ContactItem,
        size: Float,
        color: Int,
        typeface: Typeface,
        hPad: Int,
        vPad: Int,
        gravity: Int,
    ): TextView = TextView(requireContext()).apply {
        // Visible text: name + optional (type) for multi-number contacts. Every contact row
        // reads the same shape - source accounts (Google / WhatsApp / SIM / etc.) are
        // intentionally never surfaced. Source-level filtering happens silently via
        // `prefs.googleContactsOnly`. The contentDescription mirrors the visible text so
        // TalkBack sees the same level of detail.
        text = buildString {
            append(contact.displayName)
            contact.typeLabel?.takeIf { it.isNotBlank() }
                ?.let { append(" (").append(it).append(')') }
        }
        contentDescription = buildString {
            append("Contact: ")
            append(contact.displayName)
            contact.typeLabel?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
            append(", double tap to dial")
        }
        textSize = size
        setTextColor(color)
        this.typeface = typeface
        this.gravity = gravity
        setPadding(hPad, vPad, hPad, vPad)
        setOnClickListener { dialContact(contact.number) }
        // Forward touches to the host gesture detector so swipes that start on a contact row
        // still fire home gestures rather than dying on the chrome.
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = true
            singleFingerDetector.onTouchEvent(event)
            false
        }
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
     * rather than rendering an empty marker - never breaks the layout. NBSP (U+00A0) keeps the
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
        // No long-press menu - back is purely an affordance.
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
        notifColor: Int,
        notifKeys: Set<String>
    ): Int {
        // notifKeys is package-keyed until Stage 1 re-keys SlateNotificationService.
        val hasNotif = notifEnabled && app.key in notifKeys
        if (hasNotif) return notifColor
        val appColor = prefs.getAppTextColor(app.key)
        return if (appColor != null) parseColorSafe(appColor) else defaultTextColor
    }

    private fun createAppTextView(
        app: AppInfo,
        size: Float,
        color: Int,
        markerStyle: String,
        typeface: Typeface,
        hPad: Int,
        vPad: Int,
        gravity: Int
    ): TextView = TextView(requireContext()).apply {
        // displayLabel composes the profile marker at render time; AppInfo.name never carries
        // it, so sorting, search and fast scroll all still see the plain app name.
        //
        // [markerStyle] arrives resolved for the whole pass rather than being read per row.
        // It has to: markerStyleFor inspects every app in view to decide, so a per-row read
        // would be O(n^2) over the folder. That splits it from folderLabel, which still reads
        // prefs.folderStyle per row - the two look like sibling features but only one of them
        // depends on its neighbours.
        text = app.displayLabel(markerStyle)
        textSize = size
        setTextColor(color)
        // A paused profile's apps still enumerate, so they must read as present-but-inactive
        // rather than vanishing. Same 0.5 alpha the stale-shortcut rows use.
        alpha = if (app.profile?.quiet == true) 0.5f else 1f
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

    /**
     * Render a pinned external-app shortcut. Text-only, structurally identical to
     * [createAppTextView] - the only icon anywhere in this feature lives in the transient
     * picker dialog, never on a permanent row. The trailing arrow distinguishes the row from a
     * real app, since a shortcut's tap path ([launchShortcut]) has different, less-recoverable
     * failure modes than an ordinary app launch.
     */
    private fun createShortcutTextView(
        shortcut: PinnedShortcut,
        size: Float,
        defaultTextColor: Int,
        typeface: Typeface,
        hPad: Int,
        vPad: Int,
        gravity: Int
    ): TextView = TextView(requireContext()).apply {
        text = "${shortcut.pinnedLabel} ↗"
        textSize = size
        setTextColor(defaultTextColor)
        alpha = if (PinnedShortcutStore.isLikelyStale(shortcut)) 0.5f else 1f
        this.typeface = typeface
        this.gravity = gravity
        setPadding(hPad, vPad, hPad, vPad)
        setOnClickListener { launchShortcut(shortcut) }
        setOnLongClickListener { showShortcutMenu(shortcut, this); true }
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) touchStartedOnApp = true
            singleFingerDetector.onTouchEvent(event)
            false
        }
    }

    private var profileReceiver: BroadcastReceiver? = null

    /**
     * Work-profile lifecycle. These MUST be registered at runtime: every one of these actions is
     * documented as "only sent to registered receivers, not to manifest receivers", so a
     * <receiver> in the manifest would silently never fire - the kind of bug that reaches users
     * on a project with no automated tests.
     *
     * The generic ACTION_PROFILE_* set (API 34/35) is deliberately NOT also registered: a
     * managed profile fires both families, and a listener watching both double-handles.
     */
    /**
     * Re-asks [WorkGrouping.maybeGroupWorkAppsOnce] after the delay it requested, so a profile
     * still being provisioned groups on its own rather than waiting for the user to leave the
     * launcher and come back. [delayMs] null means nothing is pending and any armed re-check is
     * dropped.
     *
     * Deliberately NOT cancelled in onPause. Firing while paused is the entire point: the window
     * elapses in the user's pocket and the folder is already there next time they look. Cleared in
     * onDestroyView along with every other mainHandler callback, and the runnable re-checks
     * isAdded because the view can be torn down between the post and the fire.
     */
    private fun scheduleWorkGroupingRecheck(delayMs: Long?) {
        workGroupingRecheck?.let { mainHandler.removeCallbacks(it) }
        workGroupingRecheck = null
        if (delayMs == null) return
        val task = Runnable {
            if (!isAdded || view == null) return@Runnable
            scheduleWorkGroupingRecheck(
                WorkGrouping.maybeGroupWorkAppsOnce(
                    requireContext(), prefs, repository.workAppsForGrouping()
                )
            )
            // Unconditional rebuild. A profile that asked us to wait is one whose app set is still
            // arriving, so the list is stale whether or not this particular pass grouped anything.
            buildAppList()
        }
        workGroupingRecheck = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun registerProfileReceiver() {
        if (profileReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                repository.invalidateWorkCache()
                scheduleWorkGroupingRecheck(
                    WorkGrouping.maybeGroupWorkAppsOnce(
                        requireContext(), prefs, repository.workAppsForGrouping()
                    )
                )
                buildAppList()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        ContextCompat.registerReceiver(
            requireContext(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        profileReceiver = receiver
    }

    private fun launcherApps() = PinnedShortcutStore.launcherApps(requireContext())

    private fun launchShortcut(shortcut: PinnedShortcut) {
        if (isSearchOpen) closeSearch()
        val ok = PinnedShortcutStore.startShortcut(launcherApps(), shortcut)
        if (!ok) {
            Toast.makeText(requireContext(), "This shortcut is no longer available", Toast.LENGTH_SHORT).show()
            PinnedShortcutStore.refreshOne(prefs, launcherApps(), shortcut)
            buildAppList()
        }
    }

    private fun showShortcutMenu(shortcut: PinnedShortcut, anchor: View) {
        val sourceLabel = appLabelFor(shortcut.sourcePackage) ?: shortcut.sourcePackage
        val items = listOf("Remove", "Refresh", "Open $sourceLabel")
        SlateListDialog(
            context = requireContext(),
            title = shortcut.pinnedLabel,
            items = items,
            bgColor = prefs.backgroundColor
        ) { _, label ->
            when (label) {
                "Remove" -> {
                    // This row only ever renders the APP_LIST destination - unpin just that one,
                    // leaving an independent widget-strip pin (if any) untouched.
                    PinnedShortcutStore.remove(
                        prefs, launcherApps(), shortcut.sourcePackage, shortcut.shortcutId,
                        ShortcutDestination.APP_LIST
                    )
                    buildAppList()
                }
                "Refresh" -> {
                    PinnedShortcutStore.refreshOne(prefs, launcherApps(), shortcut)
                    buildAppList()
                }
                else -> {
                    // The remaining item is always "Open $sourceLabel".
                    val intent = requireContext().packageManager
                        .getLaunchIntentForPackage(shortcut.sourcePackage)
                    if (intent != null) {
                        runCatching { startActivity(intent) }
                    } else {
                        Toast.makeText(requireContext(), "App not installed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.show()
    }

    /**
     * Platform label for a key. Cross-profile lookups need LauncherApps, because
     * PackageManager resolves only within the calling user. Unresolvable still returns null so
     * the Hidden Apps dialog's mapNotNull drops the row exactly as it does today.
     */
    private fun appLabelFor(key: String): String? = runCatching {
        val pkg = AppKey.packageOf(key)
        val serial = AppKey.serialOf(key)
        if (serial == null) {
            val pm = requireContext().packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } else {
            val handle = WorkProfiles.handleForSerial(requireContext(), serial) ?: return@runCatching null
            launcherApps()?.getApplicationInfo(pkg, 0, handle)
                ?.let { requireContext().packageManager.getApplicationLabel(it).toString() }
        }
    }.getOrNull()

    // Fast scroll only operates over an alphabetical list, so it's mutually exclusive with
    // Sort by usage. We preserve `prefs.alphabeticalFastScroll` even when Sort by usage is on
    // (so the toggle re-lights at the user's previous position when they switch sort modes),
    // but this getter is the single source of truth for the render path - returning false here
    // suppresses both the fast-scroll widget and the forced-alphabetical override in
    // AppRepository, so Sort by usage takes effect immediately when the user enables it.
    private fun useFastScroll(): Boolean =
        prefs.homescreenView == PreferencesManager.VIEW_LIST &&
        prefs.alphabeticalFastScroll &&
        !prefs.sortByUsage

    /**
     * Resolve the apps-list typeface. `fontFamily` defaults to a non-empty Google Font key, so
     * [Typography.buildTypeface] never returns null here - the `?: Typeface.DEFAULT` fallback
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
        prefs.incrementUsage(app.key)
        // Optimistically clear notification highlight so it reverts immediately on return
        SlateNotificationService.clearHighlight(app.key)
        if (isSearchOpen) closeSearch()

        // A work app cannot be launched by Intent: getLaunchIntentForPackage resolves in the
        // calling user, so it would find the personal copy or nothing at all. Quiet mode needs
        // no handling here - the system intercepts startMainActivity for a paused profile and
        // puts up its own "turn on work apps" prompt, then replays the launch.
        val profile = app.profile
        if (profile != null) {
            val launcher = launcherApps()
            runCatching {
                requireNotNull(launcher).startMainActivity(
                    ComponentName(app.packageName, app.activityName),
                    profile.handle,
                    null,
                    null
                )
            }.onFailure {
                Toast.makeText(requireContext(), "App not installed", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val intent = requireContext().packageManager
            .getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            Toast.makeText(requireContext(), "App not installed", Toast.LENGTH_SHORT).show()
            return
        }
        // startActivity can still throw ActivityNotFoundException (app uninstalled between
        // list-build and tap) or SecurityException (rare cross-user / work-profile edges).
        // Match launchHiddenApp's defensive pattern so neither path crashes the launcher.
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(requireContext(), "App not installed", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showAppMenu(app: AppInfo, anchor: View) {
        val isPinned = prefs.isPinned(app.key)
        val pinLabel = if (isPinned) "Unpin" else "Pin to top"
        val containingFolder = FolderStore.folderContaining(prefs, app.key)
        // Build the menu dynamically so folder entries appear only where relevant. Dispatching
        // on the chosen label avoids fragile index-based branching as items shift.
        val items = buildList {
            add(pinLabel)
            add("App Info")
            add("Hide")
            // ACTION_DELETE carries no user, so for a work app it would silently target the
            // personal copy - the one destructive cross-profile intent with no way to aim it.
            // App Info still exposes the system's own uninstall where policy allows it.
            if (app.profile == null) add("Uninstall")
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
            // prefs.workMarkerStyle, NOT the render pass's resolved style: the marker stays on
            // this title even inside a folder where the rows have dropped it. Deliberate. The
            // list suppresses a marker that repeats uselessly on every row; a dialog shows one
            // app, so here it is the only thing confirming WHICH Gmail is about to be renamed or
            // hidden - and it is what explains the missing Uninstall entry just below.
            title = app.displayLabel(prefs.workMarkerStyle),
            items = items,
            bgColor = prefs.backgroundColor
        ) { _, label ->
            when (label) {
                "Pin to top" -> {
                    // Remove from folder FIRST so the "pinned ⊥ in-folder" invariant holds at
                    // every persistence intermediate, never just at the end of the sequence.
                    FolderStore.removeAppFromFolder(prefs, app.key)
                    prefs.pinApp(app.key)
                    buildAppList()
                }
                "Unpin" -> { prefs.unpinApp(app.key); buildAppList() }
                "App Info" -> {
                    val profile = app.profile
                    if (profile == null) {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", app.packageName, null)
                            }
                        )
                    } else {
                        // The settings intent resolves in the calling user, so a work app needs
                        // the LauncherApps equivalent to reach the right profile's page.
                        runCatching {
                            launcherApps()?.startAppDetailsActivity(
                                ComponentName(app.packageName, app.activityName),
                                profile.handle,
                                null,
                                null
                            )
                        }
                    }
                }
                "Hide" -> {
                    prefs.hideApp(app.key)
                    val removedShortcuts = PinnedShortcutStore.removeForPackage(prefs, launcherApps(), app.packageName)
                    if (removedShortcuts.isNotEmpty()) quickStrip?.bind()
                    buildAppList()
                    if (removedShortcuts.isNotEmpty()) {
                        showShortcutsRemovedForHiddenAppDialog(app.name, removedShortcuts.size)
                    }
                }
                "Uninstall" -> startActivity(
                    Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.fromParts("package", app.packageName, null)
                    }
                )
                "Move to folder", "Move to another folder" -> showMoveToFolderDialog(app)
                "Remove from folder" -> {
                    val pruned = FolderStore.removeAppFromFolder(prefs, app.key)
                    // Removing the last app deletes the folder, and if it was a work folder
                    // that also ends automatic grouping for good. Silent permanence is the one
                    // thing worth a toast here.
                    if (pruned?.profileSerial != null) {
                        Toast.makeText(
                            requireContext(),
                            "\"${pruned.name}\" removed. Slate won't group these apps again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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

    /**
     * Shown after hiding an app that had one or more pinned shortcuts. Hiding removes those
     * shortcuts outright (both destinations) rather than merely suppressing them, since a
     * shortcut into an app the user just chose not to see would be a confusing loose end.
     */
    private fun showShortcutsRemovedForHiddenAppDialog(appName: String, count: Int) {
        val plural = if (count == 1) "shortcut" else "shortcuts"
        SlateListDialog(
            context = requireContext(),
            title = "Shortcuts removed",
            items = listOf(
                "Hiding $appName also removed $count pinned $plural from it - a hidden app's " +
                    "shortcuts wouldn't be reachable from here either.",
                "OK"
            ),
            bgColor = prefs.backgroundColor
        ) { _, _ -> }.show()
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
                FolderStore.addAppToFolder(prefs, existing[index].id, app.key)
                buildAppList()
            } else {
                showCreateFolderDialog { newName ->
                    val folder = FolderStore.createEmpty(prefs, newName)
                    FolderStore.addAppToFolder(prefs, folder.id, app.key)
                    buildAppList()
                }
            }
        }.show()
    }

    /** Long-press on a folder label - Pin / Rename / Delete / Custom color. */
    private fun showFolderMenu(folder: Folder, anchor: View) {
        // Pin sits first and its label toggles, matching showAppMenu. Unlike pinning an app,
        // this touches nothing but the pin set: a folder is a container, so the "pinned apps
        // can't live in folders" invariant has nothing to resolve here.
        val pinLabel = if (prefs.isFolderPinned(folder.id)) "Unpin" else "Pin to top"
        SlateListDialog(
            context = requireContext(),
            title = folder.name,
            items = listOf(pinLabel, "Rename", "Custom color", "Delete folder"),
            bgColor = prefs.backgroundColor
        ) { _, label ->
            when (label) {
                "Pin to top" -> { prefs.pinFolder(folder.id); buildAppList() }
                "Unpin" -> { prefs.unpinFolder(folder.id); buildAppList() }
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
            text = if (folder.profileSerial != null) {
                "Delete \"${folder.name}\"? Its apps will return to the main list, and Slate " +
                    "won't group this profile's apps again unless you use " +
                    "Settings > Group work apps."
            } else {
                "Delete \"${folder.name}\"? Its apps will return to the main list."
            }
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

        // Text input - filled background so it reads as an editable field
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

        val hasCustomName = prefs.getAppCustomName(app.key) != null
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
                    prefs.clearAppCustomName(app.key)
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
                    prefs.setAppCustomName(app.key, newName)
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
        val current = prefs.getAppTextColor(app.key) ?: prefs.appTextColor
        ColorPickerDialog(
            context = requireContext(),
            title = app.name,
            initialColor = current,
            bgColor = prefs.backgroundColor,
            showReset = prefs.getAppTextColor(app.key) != null,
            onReset = {
                prefs.clearAppTextColor(app.key)
                buildAppList()
            }
        ) { hex ->
            prefs.setAppTextColor(app.key, hex)
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
                "Notification access is optional and used only for the notification highlight feature - it changes the color of an app's name when it has a pending notification.\n\nSlate only checks which packages have active notifications. Notification content (titles, messages, senders) is never read or stored.",

            "Does Slate collect any data?" to
                "No. Slate is 100% offline and collects zero data.\n\nThere is no analytics, no crash reporting, no tracking, and no network requests of any kind. All settings, usage counts, and customizations are stored locally on your device using Android's SharedPreferences and never leave it.",

            "Does Slate read my contacts?" to
                "Only if you turn on \"Search contacts\" in Settings → Search. With that off (the default), Slate has no contacts permission at all. With it on, Slate reads your contact list each time you type a search query - to find matches alongside your apps. Nothing is stored, indexed, or sent anywhere. Quitting and relaunching the launcher starts with no contact data in memory.\n\nWork-profile contacts are not visible (Android isolates them from third-party launchers). Contacts without a phone number are skipped, since tapping a contact opens the dialer.\n\nIf the same person appears multiple times - they often do, because the same contact can exist under more than one source (Google, WhatsApp, Telegram, SIM, OEM contacts, etc.) - turn on \"Google contacts only\" in the same settings page to filter to your Google address book and skip the duplicates.",

            "What other permissions does Slate use?" to
                "• EXPAND_STATUS_BAR - swipe-down notification panel gesture\n• ACCESS_WIFI_STATE / CHANGE_WIFI_STATE - Wi-Fi toggle gesture (Android 10+: opens system panel)\n• BLUETOOTH / BLUETOOTH_ADMIN - Bluetooth toggle on Android 11 and below\n• QUERY_ALL_PACKAGES - required to list all installed apps (Android 11+)\n• REQUEST_DELETE_PACKAGES - initiates the system uninstall flow when you choose to uninstall an app\n• REQUEST_IGNORE_BATTERY_OPTIMIZATIONS - used only when you tap \"Fix this\" on the battery restriction warning in Settings, to request that the system exempt Slate from battery optimization so background features keep working\n• USE_BIOMETRIC - declared by the AndroidX Biometric library; only requested when you opt into biometric unlock for hidden apps. Biometric data is processed by the OS and never reaches Slate.",

            "How does the hidden apps lock work?" to
                "Turning on \"Lock hidden apps\" in Settings → Security asks you to set a 4–8 digit PIN. After that, opening the Hidden Apps dialog from the home long-press menu requires PIN (or biometric, if you opt in).\n\nYour PIN is never stored in plain text. Slate stores a salted PBKDF2-HMAC-SHA256 hash with 120,000 iterations and a per-device random 16-byte salt. The hash is a one-way verifier - even with the file, an attacker would have to brute-force the PIN.\n\nBiometric is optional. When enabled, Slate uses Android's BiometricPrompt to show the standard fingerprint/face dialog. Biometric data stays inside the OS and Slate only sees a success/fail signal.\n\nAfter 5 wrong PIN attempts you're locked out for 30 seconds; 10 wrong for 5 minutes; 15 wrong for 15 minutes. There is no PIN recovery - clearing app data is the only reset. When restoring a backup that includes hidden apps, you'll be asked for the backup's PIN. If you don't know it, the rest of your settings still restore and your current PIN and hidden apps stay as they were.",

            "Do hidden apps appear in the Recents (Overview) screen?" to
                "When you open a hidden app from Slate, it's launched in a way that keeps it off the Android Recents / Overview screen - so someone glancing at Recents won't see what hidden app you opened.\n\nOne caveat Android can't avoid: if the app already had a task in Recents from before (because you opened it from another launcher, or because it uses Android's \"single task\" mode like Chrome on some devices), Slate can't remove that existing entry. Swipe it away from Recents once, and from then on Slate's launches stay invisible.",

            "How do folders work?" to
                "Long-press any app and choose \"Move to folder\" to add it to an existing folder, or pick \"+ New folder\" to create one on the spot. Folders appear on the home screen with a marker (chevron, bullet, brackets, slash, count, or plain - pick your style in Settings → Typography → Folder style). Tap to expand inline - the home list is replaced by the folder's apps with a leading ‹ back row. Tap back (or press the system back gesture) to return.\n\nEach app lives in at most one folder. Apps inside a folder are hidden from the main list to reduce clutter - search still finds them globally, and the folder name itself also appears in search results.\n\nLong-press a folder label to rename, set a custom color, or delete. Deleting a folder returns its apps to the main list; the apps themselves are never removed. Pinning an app automatically removes it from any folder it was in. If you uninstall an app, it disappears from its folder; empty folders are pruned automatically.",

            "Why are widgets shown as text, not icons?" to
                "Slate is text-only by design - apps are listed by name, and the widget strip follows the same rule. A label like \"Wi-Fi\" reads as a word rather than a symbol you recognise on autopilot, so opening or toggling something stays a small deliberate choice instead of a reflex.\n\nEach widget shows its name with the current value when there is one to show (Battery: 67%, Volume: 60%, Time: 14:32) or just the name for simple on/off toggles (Wi-Fi, Bluetooth). Active widgets render at full opacity; inactive ones are dimmed to 40% so you can see at a glance whether something is on without needing icons or colour.",

            "Why doesn't the Wi-Fi widget show my network name?" to
                "On Android 10 and above, an app can only read the connected Wi-Fi network's name (SSID) if you grant it a sensitive runtime permission - on most devices that's the precise location permission (ACCESS_FINE_LOCATION) - and have location services turned on.\n\nSlate is offline-only and never asks for a permission it doesn't strictly need for a feature, so the widget shows just \"Wi-Fi\" with active/inactive dimming instead. Tapping it opens the system Wi-Fi panel, which lists the connected network natively without needing Slate to ask for anything.",

            "Why can't Slate toggle Wi-Fi or Bluetooth directly?" to
                "Android removed direct toggle access for these from third-party apps:\n\n• Wi-Fi: since Android 10, apps cannot switch Wi-Fi on or off programmatically. Tapping the widget opens the inline system Wi-Fi panel as a bottom-sheet overlay - one tap to flip Wi-Fi on or off without leaving the launcher view.\n\n• Bluetooth: since Android 12, toggling Bluetooth requires the runtime BLUETOOTH_CONNECT permission, which also grants access to the names and addresses of every paired device and the ability to connect to them - far more than just on/off.\n\n• Mobile data, Airplane mode, NFC: toggling these requires signature-level permissions that Android only grants to system apps.\n\nSlate could ask for BLUETOOTH_CONNECT to get a one-tap Bluetooth toggle, but it would mean holding a permission that no other feature needs. Deep-linking into the system panels is the trade-off - one extra tap, no unnecessary access to your device.",

            "Is Slate open source?" to
                "Yes. Slate is open source under the MIT licence.\n\nSource code: github.com/roufsyed/Slate-Minimal-Launcher",

            // Unconditional, deliberately. This was once shown only on devices with a work
            // profile, but the Settings rows are always visible, so hiding their explanation
            // from the very people most likely to wonder what they do had it backwards.
            "How do work apps work?" to
                "Apps in your work profile appear alongside your personal apps, each " +
                "carrying a marker, for example \"Gmail [Work]\". Settings → Work " +
                "profile → Work app marker turns that into a symbol, or removes it.\n\n" +
                "The first time Slate sees a work profile it gathers those apps into a " +
                "folder for you, once. For a profile you have had a while the folder " +
                "appears straight away. For one that was only just set up, Slate waits " +
                "about a minute, because a new work profile installs its apps gradually " +
                "and Slate would otherwise group only the first one or two.\n\n" +
                "After that the folder is an ordinary folder. Rename it, recolour it, " +
                "pin it, move apps out, or put personal apps in - all of it sticks, and " +
                "Slate never rearranges it again. A work app you install later appears " +
                "in the main list like any other new app; use Settings → Work profile " +
                "→ Group work apps to file it away.\n\n" +
                "Deleting the folder is permanent - the apps return to the main list and " +
                "Slate won't group them again unless you ask. Hidden work apps are never " +
                "grouped. Work apps you've paused in Android appear dimmed; tapping one " +
                "lets Android offer to turn them back on.\n\n" +
                "Uninstall isn't offered for a work app, because Android only lets your " +
                "organisation remove those. App Info still opens the system page."
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

        // Defensive: drop any prior detail dialog before opening a new one.
        activeFaqDetailDialog?.let { runCatching { it.dismiss() } }

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

        // Back arrow row - stays pinned at the top of the dialog; doesn't scroll with the
        // answer body so the user can always return to the FAQ list mid-read.
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

        // Question - also pinned. Stays visible above the answer so the user keeps the
        // context of what they tapped while reading a long answer.
        container.addView(TextView(ctx).apply {
            text = question
            textSize = 15f
            setTextColor(accentColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (14 * density).toInt() }
        })

        // Answer is the only scrollable region. Convention follows WidgetArrangeDialog /
        // WidgetPickerDialog / PrivacyPolicyDialog - dialog window is sized to a fixed
        // fraction of the screen (below) and an internal ScrollView with weight=1 absorbs
        // any overflow from the body. Previously this region was a bare TextView inside a
        // WRAP_CONTENT dialog, so long answers (e.g. "How does the hidden apps lock work?")
        // were clipped at the screen edge with no way to read past the cut-off.
        val answerScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(TextView(ctx).apply {
                text = answer
                textSize = 15f
                setTextColor(primaryColor)
                setLineSpacing(4f * density, 1f)
                // Detect bare URLs in the answer body (e.g. the GitHub link in the
                // open-source FAQ) and turn them into tappable links. Linkify auto-installs
                // LinkMovementMethod, so taps open the URL externally without enabling text
                // selection on the rest of the body.
                setLinkTextColor(accentColor)
                android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            })
        }
        container.addView(answerScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0
        ).apply { weight = 1f })

        // MATCH_PARENT on the container so its children's weight=1 has a bounded parent to
        // distribute against - without this the LinearLayout would only be as tall as its
        // natural content and weight=1 would have no effect.
        dialog.setContentView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.85).toInt(),
            (ctx.resources.displayMetrics.heightPixels * 0.80).toInt()
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            if (activeFaqDetailDialog === dialog) activeFaqDetailDialog = null
        }
        activeFaqDetailDialog = dialog
        dialog.show()
    }

    private fun showHiddenAppsDialog() {
        // hiddenApps is key-space. The label lookup is OS-facing so it takes the bare package;
        // the pair's second element stays a key, because unhideApp() below needs a key.
        val hidden = prefs.hiddenApps.mapNotNull { key ->
            appLabelFor(key)?.let { it to key }
        }
            .sortedBy { it.first.lowercase() }

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
            title = "Hidden Apps - tap to open, hold to unhide",
            items = hidden.map { it.first },
            bgColor = prefs.backgroundColor,
            onItemLongPress = { index, _ ->
                showUnhideConfirm(
                    name = hidden[index].first,
                    key = hidden[index].second
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
     * package - the Hidden Apps dialog tracks (displayName, pkg) pairs rather than full
     * [AppInfo] objects. The null-intent branch is defensive: the list is filtered for
     * installed apps at open time, so this only trips if an uninstall raced with the tap.
     */
    private fun launchHiddenApp(key: String) {
        // Usage count is deliberately NOT incremented for hidden launches. Hidden apps are
        // filtered out of AppRepository.getAllApps() (in the shared enumerator), so the count
        // has no effect on the main list, search, or sort-by-usage. Its only remaining consumer is the folder
        // font-size weighting in sizeForItem() - which would visibly grow the containing
        // folder's font on every hidden launch and leak activity to anyone glancing at the
        // home screen.
        SlateNotificationService.clearHighlight(key)
        val intent = requireContext().packageManager
            .getLaunchIntentForPackage(AppKey.packageOf(key))
        if (intent == null) {
            Toast.makeText(requireContext(), "App not installed", Toast.LENGTH_SHORT).show()
            return
        }
        // Privacy: keep hidden-app launches off the system Recents / Overview screen so a
        // coworker glancing at Recents can't see what hidden app was opened. The flag applies
        // at task-creation time; if the target app uses launchMode="singleTask" and already
        // has a live task in Recents from before, that existing task is reused and stays
        // visible - public APIs don't let a third-party launcher remove another app's task.
        // The FAQ explains the one-time-swipe mitigation to the user.
        //
        // The flag costs more than visibility: from Android 9 the system trims an excluded task
        // once it falls behind home, and trimming FINISHES its activities, so switching away
        // destroys the app and anything typed into it. keepHiddenAppsInRecents lets a user who
        // needs that state preserved opt out. Default false, and gated behind an explicit
        // consent dialog in Settings, so the privacy behaviour is unchanged unless chosen.
        if (!prefs.keepHiddenAppsInRecents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(requireContext(), "App not installed", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Confirmation dialog before unhiding an app - guards against a misclick on the Hidden
     * Apps long-press. Reuses the accessibility-info dialog layout (title / body / two
     * buttons), the same template as [showDeleteFolderConfirm].
     */
    private fun showUnhideConfirm(name: String, key: String, onConfirmed: () -> Unit) {
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
                prefs.unhideApp(key)
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
