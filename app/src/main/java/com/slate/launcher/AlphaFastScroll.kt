package com.slate.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Vertical alphabet strip for fast-scrolling a list of apps. Renders only the letters that
 * actually appear in the current dataset. Drag-to-track with haptic on letter change.
 */
class AlphaFastScroll @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics
        )
        color = Color.GRAY
    }

    private var letters: CharArray = CharArray(0)
    private var lastIndex: Int = -1

    var textColor: Int
        get() = paint.color
        set(value) {
            if (paint.color != value) {
                paint.color = value
                invalidate()
            }
        }

    /** Fired on DOWN/MOVE when the active letter changes. */
    var onLetterTouched: ((Char) -> Unit)? = null

    /** Fired on DOWN (true) and UP/CANCEL (false). */
    var onTouchStateChanged: ((Boolean) -> Unit)? = null

    init {
        contentDescription = "Alphabet fast scroll"
        isHapticFeedbackEnabled = true
    }

    fun setLetters(letters: List<Char>) {
        this.letters = letters.toCharArray()
        lastIndex = -1
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics
        ).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(0, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return
        val usableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (usableHeight <= 0f) return
        val cellHeight = usableHeight / letters.size
        val cx = width / 2f
        val baselineOffset = (paint.descent() + paint.ascent()) / 2f
        for (i in letters.indices) {
            val cy = paddingTop + (i + 0.5f) * cellHeight - baselineOffset
            canvas.drawText(letters, i, 1, cx, cy, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onTouchStateChanged?.invoke(true)
                dispatchLetter(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dispatchLetter(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onTouchStateChanged?.invoke(false)
                lastIndex = -1
                return true
            }
        }
        return false
    }

    private fun dispatchLetter(y: Float) {
        val usableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (usableHeight <= 0f) return
        val cellHeight = usableHeight / letters.size
        val idx = ((y - paddingTop) / cellHeight).toInt().coerceIn(0, letters.size - 1)
        if (idx != lastIndex) {
            lastIndex = idx
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onLetterTouched?.invoke(letters[idx])
        }
    }
}
