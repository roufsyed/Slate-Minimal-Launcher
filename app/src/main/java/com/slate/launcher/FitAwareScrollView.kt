package com.slate.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * A [ScrollView] that refuses to actually scroll when its single child fits inside the
 * viewport. Solves a launcher-specific UX nit: with `fillViewport="true"` and an inner
 * `LinearLayout` that carries `paddingVertical`, the inner view can be slightly taller than
 * the viewport even when the user perceives the app list as fully visible - that residual
 * tens-of-dp scroll range reads as "scrollable for no reason".
 *
 * Implementation: when [canScroll] is false, [onInterceptTouchEvent] returns `false` (so
 * children - app TextViews - still claim their own clicks) and [onTouchEvent] returns
 * `true` WITHOUT calling `super`. The `true` return is critical and easy to get wrong:
 * if we returned `false`, Android would mark this View as not the touch target and would
 * stop dispatching subsequent ACTION_MOVE / ACTION_UP for the gesture - which would in
 * turn prevent the host fragment's [setOnTouchListener]-bound `GestureDetector` from
 * seeing UP. That breaks single-tap, double-tap, and causes the long-press timer to fire
 * on quick taps. By returning `true` we claim the gesture for dispatch purposes, but
 * because we skip `super.onTouchEvent` the ScrollView's scroll machinery never runs.
 *
 * The host fragment's `setOnTouchListener` (e.g., `AppDrawerFragment.kt:187-191`) runs
 * BEFORE this view's `onTouchEvent` per Android's standard dispatch order, so any gesture
 * detector wired through it keeps receiving every event.
 *
 * Programmatic scrolls (`smoothScrollTo`, `scrollTo`, accessibility scroll actions) don't
 * go through the touch pipeline and are unaffected. [canScrollVertically] is also
 * overridden so the no-scroll state is reported consistently to accessibility services.
 */
class FitAwareScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /**
     * True iff content is actually clipped at the bottom of the viewport - i.e., scrolling
     * would reveal content the user can't currently see.
     *
     * Reasoning: the child's apps occupy local-y `[child.paddingTop, child.height - child.paddingBottom]`
     * inside the child. At `scrollY=0` (the at-rest position), those map directly to
     * viewport-y coordinates because the child's top is flush with the ScrollView's
     * content-area top. The apps are clipped iff their bottom edge -
     * `child.height - child.paddingBottom` - exceeds the viewport.
     *
     * Why NOT subtract `paddingTop` too: the inner `paddingTop` simply insets apps inward
     * from the viewport's top edge. At `scrollY=0` the apps sit further down inside the
     * viewport, but they are NOT clipped at the top - the top of the viewport is at y=0 and
     * apps start at y=paddingTop ≥ 0. Subtracting both paddings would measure just the apps'
     * RAW height; that's wrong because it ignores the fact that the apps' BOTTOM edge sits at
     * `paddingTop + apps`, which can exceed the viewport even when `apps ≤ viewport`.
     *
     * When `fillViewport` stretches the child (natural height < viewport), `child.height`
     * becomes the viewport. `(viewport - paddingBottom) ≤ viewport` is always false-or-equal,
     * so `canScroll` correctly returns false.
     */
    private fun canScroll(): Boolean {
        val child = getChildAt(0) ?: return false
        val viewport = height - paddingTop - paddingBottom
        return (child.height - child.paddingBottom) > viewport
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        if (!canScroll()) false else super.onInterceptTouchEvent(ev)

    // Return `true` (not `false`) when content fits. See class KDoc for the full reasoning -
    // briefly: returning `false` would tell Android we don't want the gesture, Android would
    // stop delivering subsequent events to this View, the host's `setOnTouchListener` would
    // stop firing, and the gesture detector would miss every ACTION_UP - breaking tap and
    // double-tap, and causing the long-press timer to fire on quick taps.
    override fun onTouchEvent(ev: MotionEvent): Boolean =
        if (!canScroll()) true else super.onTouchEvent(ev)

    /**
     * Mirror the touch policy to programmatic / accessibility scrolling. When the visible
     * content fits, accessibility services (TalkBack scroll-forward / scroll-backward) and
     * nested-scroll parents should both observe "no scroll possible" - otherwise an a11y
     * scroll command would expose the otherwise-hidden padding strip, contradicting what the
     * user perceives.
     */
    override fun canScrollVertically(direction: Int): Boolean =
        canScroll() && super.canScrollVertically(direction)
}
