package com.slate.launcher

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects directional swipes for 2 and 3 fingers.
 * Single-finger swipes are handled by [android.view.GestureDetector].
 */
class MultiFingerGestureDetector(
    private val minDistance: Float = 80f,
    private val onGesture: (fingers: Int, direction: Direction) -> Boolean
) {
    enum class Direction { UP, DOWN, LEFT, RIGHT }

    private val startX = HashMap<Int, Float>()
    private val startY = HashMap<Int, Float>()
    private var maxPointers = 0

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX.clear()
                startY.clear()
                maxPointers = 1
                startX[event.getPointerId(0)] = event.x
                startY[event.getPointerId(0)] = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                maxPointers = maxOf(maxPointers, event.pointerCount)
                startX[id] = event.getX(idx)
                startY[id] = event.getY(idx)
            }
            MotionEvent.ACTION_UP -> {
                if (maxPointers >= 2) {
                    val id = event.getPointerId(0)
                    val dx = event.x - (startX[id] ?: return false)
                    val dy = event.y - (startY[id] ?: return false)
                    val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist >= minDistance) {
                        val direction = if (abs(dx) > abs(dy)) {
                            if (dx > 0) Direction.RIGHT else Direction.LEFT
                        } else {
                            if (dy > 0) Direction.DOWN else Direction.UP
                        }
                        return onGesture(maxPointers.coerceAtMost(3), direction)
                    }
                }
            }
        }
        return false
    }
}
