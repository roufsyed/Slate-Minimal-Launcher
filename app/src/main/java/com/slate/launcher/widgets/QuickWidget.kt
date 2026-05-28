package com.slate.launcher.widgets

import android.content.Context

/**
 * A widget rendered in the home-screen quick-toggles strip. Widgets are stateless singletons —
 * the actual state is read live from system APIs in [renderLabel]. Implementations override
 * [startObserving] when they need to react to state changes (broadcasts, callbacks, content
 * observers); the returned [WidgetSubscription] is closed when the strip leaves the foreground.
 */
abstract class QuickWidget {

    /** Stable identifier persisted in user prefs. Must not change across releases. */
    abstract val id: String

    /** Human-readable name shown in the widget picker in Settings. */
    abstract val displayName: String

    /** When true, enabling the widget triggers a special-access flow (e.g., DND policy access). */
    open val requiresSpecialAccess: Boolean = false

    /**
     * Optional user-facing caveat shown as a sub-label under the widget's display name in the
     * Widget Picker. Use for short, plain-language hints that explain a behaviour the user might
     * otherwise see as a bug — e.g., "Not all phones report this. Shows '—' when unavailable."
     * for OEM-inconsistent system readings. Independent of [requiresSpecialAccess]: a widget can
     * have both a permission requirement AND a behaviour caveat, surfaced as separate sub-labels.
     */
    open val pickerNote: String? = null

    /** Skip the widget if the device lacks the hardware/API (e.g., NFC on a phone with no NFC). */
    open fun isAvailable(context: Context): Boolean = true

    /** Compute the current label and active state. Called on the UI thread. */
    abstract fun renderLabel(context: Context): WidgetLabel

    /** Direct toggle or deep-link intent — called on the UI thread. */
    open fun onTap(context: Context) {}

    /**
     * Optional observer. Implementations register a broadcast/callback and invoke [onChanged]
     * (which is already main-thread-safe — the manager posts to the UI thread itself) whenever
     * state may have changed. The returned subscription is closed on stop.
     */
    open fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription =
        NoOpSubscription

    companion object {
        val NoOpSubscription: WidgetSubscription = object : WidgetSubscription {
            override fun close() {}
        }
    }
}

/** What the strip should display for a widget. `active=false` renders dim. */
data class WidgetLabel(val text: String, val active: Boolean = true)

/** Closeable observer handle returned by [QuickWidget.startObserving]. */
interface WidgetSubscription {
    fun close()
}
