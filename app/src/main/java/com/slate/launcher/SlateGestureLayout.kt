package com.slate.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * Root layout for the home screen.
 *
 * `dispatchTouchEvent` is called for every touch event that passes through
 * this ViewGroup, including ACTION_POINTER_DOWN events that are destined for
 * a child view.  This guarantees the multi-finger detector always receives a
 * complete event stream, regardless of which child claimed the initial touch.
 */
class SlateGestureLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var multiFingerDetector: MultiFingerGestureDetector? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val gestureConsumed = multiFingerDetector?.onTouchEvent(ev) ?: false
        if (gestureConsumed) {
            // Cancel any in-progress child touch so no accidental app launches occur
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
