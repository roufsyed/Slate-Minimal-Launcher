package com.slate.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe

class ColorPickerDialog(
    context: Context,
    private val title: String,
    private val initialColor: String,
    private val bgColor: String,
    private val showReset: Boolean = false,
    private val onReset: (() -> Unit)? = null,
    private val onApply: (String) -> Unit
) : Dialog(context, R.style.SlateDialogTheme) {

    companion object {
        private val PRESETS = listOf(
            // Quick picks: common bg and text defaults
            "#101010", "#808080",
            // Dark backgrounds
            "#000000", "#111111", "#1a1a2e", "#16213e", "#0f3460",
            "#1b1b2f", "#2c2c54", "#191919", "#212121", "#263238",
            // Light backgrounds
            "#ffffff", "#f5f5f5", "#eeeeee", "#e0e0e0",
            "#fafafa", "#f0f0f0", "#e8e8e8", "#d0d0d0",
            // Accent colors
            "#b71c1c", "#1a237e", "#1b5e20", "#e65100", "#4a148c",
            "#006064", "#880e4f", "#f57f17", "#33691e", "#bf360c"
        )
    }

    private var currentHex = initialColor

    init {
        setContentView(R.layout.dialog_color_picker)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setup()
    }

    private fun setup() {
        val bg = parseColorSafe(bgColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#888888")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = context.resources.displayMetrics.density
        val borderColor = if (isLight) Color.parseColor("#AAAAAA") else Color.parseColor("#444444")

        // Card background
        val root = findViewById<View>(R.id.dialogTitle)!!.parent as android.view.ViewGroup
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 4
        }

        // Title
        val titleView = findViewById<TextView>(R.id.dialogTitle)!!
        titleView.text = title
        titleView.setTextColor(accent)

        // Preview swatch
        val previewView = findViewById<View>(R.id.colorPreview)!!
        fun updatePreview(hex: String) {
            previewView.background = ColorDrawable(parseColorSafe(hex))
        }
        updatePreview(currentHex)

        // Hex row: label + input
        val hexRow = root.getChildAt(2) as android.view.ViewGroup
        (hexRow.getChildAt(0) as? TextView)?.setTextColor(secondary)

        val hexInput = findViewById<EditText>(R.id.hexInput)!!
        hexInput.setText(currentHex)
        hexInput.setTextColor(primary)
        hexInput.setHintTextColor(secondary)
        hexInput.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(density.toInt().coerceAtLeast(1), borderColor)
            cornerRadius = density * 2
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hex = s?.toString() ?: return
                if (hex.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                    currentHex = hex
                    updatePreview(hex)
                    buildSwatches(previewView, hexInput, accent, density)
                }
            }
        })

        // Swatches
        buildSwatches(previewView, hexInput, accent, density)

        // Buttons
        val btnCancel = findViewById<TextView>(R.id.btnCancel)!!
        val btnApply = findViewById<TextView>(R.id.btnApply)!!
        val btnReset = findViewById<TextView>(R.id.btnReset)!!
        btnCancel.setTextColor(secondary)
        btnApply.setTextColor(accent)
        if (showReset) {
            btnReset.visibility = View.VISIBLE
            btnReset.setTextColor(secondary)
            btnReset.setOnClickListener { onReset?.invoke(); dismiss() }
        }

        btnCancel.setOnClickListener { dismiss() }
        btnApply.setOnClickListener {
            onApply(currentHex)
            dismiss()
        }
    }

    private fun buildSwatches(
        previewView: View,
        hexInput: EditText,
        selectedBorderColor: Int,
        density: Float
    ) {
        val grid = findViewById<FlexboxLayout>(R.id.swatchGrid)!!
        grid.flexWrap = FlexWrap.WRAP
        grid.justifyContent = JustifyContent.FLEX_START
        grid.removeAllViews()

        val size = (28 * density).toInt()
        val margin = (4 * density).toInt()

        PRESETS.forEach { hex ->
            val swatch = View(context)
            val lp = FlexboxLayout.LayoutParams(size, size).apply {
                setMargins(margin, margin, margin, margin)
            }
            swatch.layoutParams = lp
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(parseColorSafe(hex))
                cornerRadius = density * 2
                if (hex.equals(currentHex, ignoreCase = true)) {
                    setStroke((2 * density).toInt(), selectedBorderColor)
                }
            }
            swatch.setOnClickListener {
                currentHex = hex
                hexInput.setText(hex)
                previewView.background = ColorDrawable(parseColorSafe(hex))
                buildSwatches(previewView, hexInput, selectedBorderColor, density)
            }
            grid.addView(swatch)
        }
    }
}
