package com.slate.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe

class SlateListDialog(
    context: Context,
    private val title: String,
    private val items: List<String>,
    private val bgColor: String,
    /**
     * Optional per-row right-aligned preview text. When non-null AND the same length as
     * [items], each row renders as a two-column layout: primary label on the left, preview on
     * the right (both in the dialog's primary text colour so the preview reads exactly as it
     * will on the home screen). If null or size-mismatched, falls back to the standard
     * single-column layout — preserves behaviour for all existing call sites.
     */
    private val secondaryItems: List<String>? = null,
    private val onItemSelected: (index: Int, label: String) -> Unit
) : Dialog(context, R.style.SlateDialogTheme) {

    init {
        setContentView(R.layout.dialog_slate_list)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Center on screen, 80% width, wrap height
        val screenWidth = context.resources.displayMetrics.widthPixels
        window?.setLayout((screenWidth * 0.80).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.CENTER)

        // Dismiss when tapping outside the dialog
        setCanceledOnTouchOutside(true)

        setupViews()
    }

    private fun setupViews() {
        val bg = parseColorSafe(bgColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#888888")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val dividerColor = if (isLight) Color.parseColor("#DDDDDD") else Color.parseColor("#333333")
        val rippleOverlay = if (isLight) Color.parseColor("#15000000") else Color.parseColor("#20FFFFFF")

        val density = context.resources.displayMetrics.density

        // Card background
        val root = findViewById<View>(R.id.dialogTitle)?.parent as? android.view.ViewGroup ?: return
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        // Title and divider — hide both when title is empty
        val titleView = findViewById<TextView>(R.id.dialogTitle) ?: return
        val divider = findViewById<View>(R.id.titleDivider) ?: return

        if (title.isEmpty()) {
            titleView.visibility = View.GONE
            divider.visibility = View.GONE
        } else {
            titleView.text = title
            titleView.setTextColor(accent)
            divider.setBackgroundColor(dividerColor)
        }

        // List items
        val container = findViewById<LinearLayout>(R.id.listContainer) ?: return
        container.removeAllViews()

        // Use the secondary previews only if the caller supplied one for every row; a partial
        // list would silently misalign the picker. Falling back to one-column keeps callers
        // honest without throwing.
        val previews = secondaryItems?.takeIf { it.size == items.size }

        val hPad = (24 * density).toInt()
        val vPad = (16 * density).toInt()

        items.forEachIndexed { index, label ->
            val row: View = if (previews != null) {
                buildTwoColumnRow(
                    label = label,
                    preview = previews[index],
                    primary = primary,
                    previewColor = secondary,
                    rippleOverlay = rippleOverlay,
                    hPad = hPad,
                    vPad = vPad
                ) { onItemSelected(index, label); dismiss() }
            } else {
                buildSingleColumnRow(
                    label = label,
                    primary = primary,
                    rippleOverlay = rippleOverlay,
                    hPad = hPad,
                    vPad = vPad
                ) { onItemSelected(index, label); dismiss() }
            }
            container.addView(row)

            // Thin separator between items (not after last item)
            if (index < items.size - 1) {
                val sep = View(context)
                sep.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                ).apply {
                    marginStart = (24 * density).toInt()
                    marginEnd = (24 * density).toInt()
                }
                sep.setBackgroundColor(dividerColor)
                container.addView(sep)
            }
        }
    }

    private fun buildSingleColumnRow(
        label: String,
        primary: Int,
        rippleOverlay: Int,
        hPad: Int,
        vPad: Int,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 17f
        setTextColor(primary)
        setPadding(hPad, vPad, hPad, vPad)
        setOnClickListener { onClick() }
        setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN ->
                    (v as TextView).setBackgroundColor(rippleOverlay)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    (v as TextView).setBackgroundColor(Color.TRANSPARENT)
            }
            false
        }
    }

    private fun buildTwoColumnRow(
        label: String,
        preview: String,
        primary: Int,
        previewColor: Int,
        rippleOverlay: Int,
        hPad: Int,
        vPad: Int,
        onClick: () -> Unit
    ): LinearLayout {
        // The whole row is clickable so taps anywhere on it select the option; we apply the
        // ripple overlay to the row, not the inner TextViews, so the visual feedback covers
        // the entire tap target.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> v.setBackgroundColor(rippleOverlay)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.TRANSPARENT)
                }
                false
            }
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 17f
            setTextColor(primary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { weight = 1f }
        })
        // Preview uses the secondary text colour so it reads as a hint, matching the
        // row-value style used elsewhere in Settings (Font / Weight / Alignment row values).
        val density = context.resources.displayMetrics.density
        row.addView(TextView(context).apply {
            text = preview
            textSize = 17f
            setTextColor(previewColor)
            // If a future preview is unusually long (e.g., a custom marker), don't overflow the
            // primary label out of the row.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (16 * density).toInt() }
        })
        return row
    }
}
