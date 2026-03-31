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
        val root = findViewById<View>(R.id.dialogTitle)!!.parent as android.view.ViewGroup
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        // Title and divider — hide both when title is empty
        val titleView = findViewById<TextView>(R.id.dialogTitle)!!
        val divider = findViewById<View>(R.id.titleDivider)!!

        if (title.isEmpty()) {
            titleView.visibility = View.GONE
            divider.visibility = View.GONE
        } else {
            titleView.text = title
            titleView.setTextColor(accent)
            divider.setBackgroundColor(dividerColor)
        }

        // List items
        val container = findViewById<LinearLayout>(R.id.listContainer)!!
        container.removeAllViews()

        items.forEachIndexed { index, label ->
            val item = TextView(context).apply {
                text = label
                textSize = 17f
                setTextColor(primary)
                val hPad = (24 * density).toInt()
                val vPad = (16 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                setOnClickListener {
                    onItemSelected(index, label)
                    dismiss()
                }
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
            container.addView(item)

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
}
