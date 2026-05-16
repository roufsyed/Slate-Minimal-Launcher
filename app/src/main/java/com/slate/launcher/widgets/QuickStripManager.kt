package com.slate.launcher.widgets

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import com.slate.launcher.PreferencesManager

/**
 * Renders the home-screen quick-toggles strip into a FlexboxLayout and manages each enabled
 * widget's observer lifecycle. The strip is a passive consumer of [PreferencesManager] — call
 * [bind] from the fragment whenever pref state may have changed (onResume, or after Settings
 * returns), and [start] / [stop] from the fragment's resume / pause.
 *
 * Observer scope: per-widget. With ~20 possible widgets and at most a handful enabled, the
 * overhead of N separate receivers is negligible vs. the simplicity of independent subscriptions.
 */
class QuickStripManager(
    private val container: FlexboxLayout,
    private val prefs: PreferencesManager
) {

    private val context: Context = container.context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeWidgets = mutableListOf<QuickWidget>()
    private val subscriptions = mutableListOf<WidgetSubscription>()
    private var started: Boolean = false

    init {
        container.flexDirection = com.google.android.flexbox.FlexDirection.ROW
        container.flexWrap = FlexWrap.WRAP
        container.justifyContent = JustifyContent.CENTER
        container.alignItems = AlignItems.CENTER
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

        prefs.quickStripWidgets.forEach { id ->
            val w = WidgetCatalog.byId(prefs, id) ?: return@forEach
            if (!w.isAvailable(context)) return@forEach
            activeWidgets.add(w)
            container.addView(createWidgetView(w))
        }

        container.visibility = if (activeWidgets.isEmpty()) View.GONE else View.VISIBLE
        refreshAll()
    }

    /** Start observing each enabled widget. Call from the host fragment's onResume. */
    fun start() {
        if (started) return
        started = true
        activeWidgets.forEachIndexed { index, w ->
            subscriptions += w.startObserving(context) {
                // Marshal back to UI thread — some observers (e.g., TorchCallback) already fire on
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

    private fun refreshAll() {
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
            textSize = 14f
            setTextColor(color)
            gravity = Gravity.CENTER
            val pad = (12 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(pad, vPad, pad, vPad)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching { widget.onTap(context) }
            }
        }
    }

    companion object {
        private const val INACTIVE_ALPHA = 0.4f
    }
}
