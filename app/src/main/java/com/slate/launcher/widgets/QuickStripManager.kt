package com.slate.launcher.widgets

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import com.slate.launcher.PreferencesManager
import com.slate.launcher.Typography
import com.slate.launcher.shortcuts.PinnedShortcutStore

/**
 * Renders the home-screen quick-toggles strip into a FlexboxLayout and manages each enabled
 * widget's observer lifecycle. The strip is a passive consumer of [PreferencesManager] - call
 * [bind] from the fragment whenever pref state may have changed (onResume, or after Settings
 * returns), and [start] / [stop] from the fragment's resume / pause.
 *
 * Observer scope: per-widget. With ~20 possible widgets and at most a handful enabled, the
 * overhead of N separate receivers is negligible vs. the simplicity of independent subscriptions.
 */
class QuickStripManager(
    private val container: FlexboxLayout,
    private val prefs: PreferencesManager,
    /**
     * Called for every touch event on the strip (both on individual widgets and on empty space
     * between them). Lets the host fragment forward the event into its single-finger gesture
     * detector so swipes that start on the strip fire the user's configured gestures instead of
     * dying on the chrome. May be null in non-fragment contexts (tests, previews).
     */
    private val touchForwarder: ((MotionEvent) -> Unit)? = null
) {

    private val context: Context = container.context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeWidgets = mutableListOf<QuickWidget>()
    private val subscriptions = mutableListOf<WidgetSubscription>()
    private var started: Boolean = false

    init {
        container.flexDirection = com.google.android.flexbox.FlexDirection.ROW
        container.flexWrap = FlexWrap.WRAP
        container.alignItems = AlignItems.CENTER
        // `justifyContent` is re-applied per bind() to honour the user's widgetTextAlignment
        // pref. Setting it here once would freeze it at construction and ignore pref changes.
        installContainerTouchForwarder()
    }

    /**
     * Forward touches that land on the strip container's blank space (no widget hit) into the
     * gesture detector. Returns true on every event to claim the touch sequence - a
     * non-clickable ViewGroup that returns false on ACTION_DOWN never receives subsequent
     * MOVE/UP events, which would break swipe-from-empty-strip-space gestures.
     */
    private fun installContainerTouchForwarder() {
        val forwarder = touchForwarder ?: return
        container.setOnTouchListener { _, event ->
            forwarder(event)
            true
        }
    }

    /** Resolve the configured widget set; build the views. Safe to call repeatedly. */
    fun bind() {
        // Tear down any prior observers before rebuilding so we don't leak them on rebind.
        stop()
        activeWidgets.clear()
        container.removeAllViews()

        if (!prefs.quickStripEnabled) {
            container.visibility = View.GONE
            return
        }

        reconcilePinnedShortcuts()

        // Re-apply alignment on every bind so a pref change between Settings and home flips
        // the row immediately on resume.
        container.justifyContent = when (prefs.widgetTextAlignment) {
            "left" -> JustifyContent.FLEX_START
            "right" -> JustifyContent.FLEX_END
            else -> JustifyContent.CENTER
        }

        prefs.quickStripWidgets.forEach { id ->
            val w = WidgetCatalog.byId(prefs, id) ?: return@forEach
            if (!w.isAvailable(context)) return@forEach
            activeWidgets.add(w)
            container.addView(createWidgetView(w))
        }

        container.visibility = if (activeWidgets.isEmpty()) View.GONE else View.VISIBLE
        refreshAll()
    }

    private fun reconcilePinnedShortcuts() {
        PinnedShortcutStore.reconcileInstalled(context, prefs)
    }

    /** Start observing each enabled widget. Call from the host fragment's onResume. */
    fun start() {
        if (started) return
        started = true
        activeWidgets.forEachIndexed { index, w ->
            subscriptions += w.startObserving(context) {
                // Marshal back to UI thread - some observers (e.g., TorchCallback) already fire on
                // main, but content observers and Telephony callbacks can land on a worker.
                mainHandler.post { refreshWidget(index) }
            }
        }
        // Re-paint after observers have a chance to deliver their initial state synchronously
        // (CameraManager.TorchCallback fires on register with the current torch state). The post
        // also catches any state that changed while the strip was paused.
        mainHandler.post { refreshAll() }
    }

    /** Unregister every observer. Call from the host fragment's onPause. */
    fun stop() {
        if (!started && subscriptions.isEmpty()) return
        subscriptions.forEach { runCatching { it.close() } }
        subscriptions.clear()
        started = false
    }

    /**
     * True iff the last [bind] resolved at least one configured-and-available widget into the
     * strip. Lets the host fragment compute the strip's "intended" visibility without inspecting
     * [container]'s actual visibility, which the fragment itself overrides under IME-up state.
     */
    fun hasActiveWidgets(): Boolean = activeWidgets.isNotEmpty()

    /**
     * Hit-test the strip for a touch in screen (raw) coordinates. Returns the configured
     * widget whose view's bounds contain the point, or null if the touch falls in blank strip
     * space, on a GONE strip, or outside the container entirely.
     *
     * Used by AppDrawerFragment to route long-press to a widget-specific action (currently
     * only the direct-call path) when the user has bound that gesture in Settings. Operates on
     * raw screen coords so the caller doesn't need to track the container's position.
     *
     * Relies on the same i↔child-i invariant as [refreshWidget]: each `addView(createWidgetView)`
     * in [bind] appends to `activeWidgets` and `container` in lockstep.
     */
    fun widgetForRawTouch(rawX: Float, rawY: Float): QuickWidget? {
        if (container.visibility != View.VISIBLE) return null
        val loc = IntArray(2)
        container.getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val localY = rawY - loc[1]
        if (localX < 0 || localY < 0 || localX > container.width || localY > container.height) {
            return null
        }
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (localX >= child.left && localX <= child.right &&
                localY >= child.top && localY <= child.bottom
            ) {
                return activeWidgets.getOrNull(i)
            }
        }
        return null
    }

    /**
     * Repaint every active widget's label from its current state. Cheap; safe to call from
     * the host fragment after un-hiding the strip to flush any state changes that occurred
     * while the row was [View.GONE] (e.g., a clock that missed a TIME_TICK while invisible).
     */
    fun refreshAll() {
        for (i in activeWidgets.indices) refreshWidget(i)
    }

    private fun refreshWidget(index: Int) {
        if (index !in activeWidgets.indices) return
        val widget = activeWidgets[index]
        val view = container.getChildAt(index) as? TextView ?: return
        val label = runCatching { widget.renderLabel(context) }
            .getOrElse { WidgetLabel(widget.id, active = false) }
        view.text = label.text
        view.alpha = if (label.active) 1f else INACTIVE_ALPHA
    }

    private fun createWidgetView(widget: QuickWidget): TextView {
        val density = container.resources.displayMetrics.density
        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val color = parseColorSafe(prefs.appTextColor, if (isLight) Color.BLACK else Color.WHITE)

        return TextView(context).apply {
            text = widget.id
            setTextColor(color)
            gravity = Gravity.CENTER
            // Typography (font size, padding, typeface override) - shared with the Settings
            // preview via [Typography.applyWidgetStyle] so the two renderings stay in lockstep.
            Typography.applyWidgetStyle(this, prefs, context, density)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching { widget.onTap(context) }
            }
            // Forward all touches to the host fragment's gesture detector. Returning false from
            // the touch listener lets the TextView's own onTouchEvent process click/long-click
            // normally (the View is `clickable=true`, so it consumes the sequence and we
            // continue receiving MOVE/UP events). Android's click semantics ignore events with
            // significant movement, so a swipe across this widget fires the gesture without
            // also triggering the click listener.
            touchForwarder?.let { fwd ->
                setOnTouchListener { _, event ->
                    fwd(event)
                    false
                }
            }
        }
    }

    companion object {
        private const val INACTIVE_ALPHA = 0.4f
    }
}
