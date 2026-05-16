package com.slate.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe

/**
 * A themed PIN entry dialog. Used as a building block for setup, verify, and change flows.
 * The entered PIN is delivered to [onConfirm] as a [CharArray] — callers MUST zero it after use.
 */
class PinEntryDialog(
    context: Context,
    private val bgColor: String,
    private val title: String,
    private val message: String,
    private val confirmLabel: String = "OK",
    private val onConfirm: (CharArray) -> Unit,
    private val onCancel: () -> Unit = {}
) : Dialog(context, R.style.SlateDialogTheme) {

    private var consumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_pin_entry)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val screenWidth = context.resources.displayMetrics.widthPixels
        window?.setLayout((screenWidth * 0.85).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.CENTER)
        setCanceledOnTouchOutside(false)
        // Use OnDismissListener (not OnCancelListener) so we also catch dismissals from the
        // activity being destroyed (rotation, low-memory kill, finish()). This guarantees the
        // input buffer is cleared and the caller's onCancel hook fires, letting upstream code
        // zero any captured PIN CharArrays.
        setOnDismissListener {
            findViewById<EditText>(R.id.pinDialogInput)?.text?.clear()
            if (!consumed) onCancel()
        }

        val bg = parseColorSafe(bgColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val errorColor = if (isLight) Color.parseColor("#B00020") else Color.parseColor("#FF6B6B")
        val density = context.resources.displayMetrics.density

        val root = findViewById<TextView>(R.id.pinDialogTitle).parent as android.view.ViewGroup
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        findViewById<TextView>(R.id.pinDialogTitle).apply {
            text = title
            setTextColor(accent)
        }
        findViewById<TextView>(R.id.pinDialogMessage).apply {
            text = message
            setTextColor(primary)
        }

        val input = findViewById<EditText>(R.id.pinDialogInput).apply {
            setTextColor(primary)
            setHintTextColor(secondary)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

        val btnOk = findViewById<TextView>(R.id.pinBtnOk).apply {
            text = confirmLabel
            setTextColor(secondary) // disabled-looking until min length reached
            isEnabled = false
        }
        val btnCancel = findViewById<TextView>(R.id.pinBtnCancel).apply {
            setTextColor(secondary)
        }
        val errorView = findViewById<TextView>(R.id.pinDialogError).apply {
            setTextColor(errorColor)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val len = s?.length ?: 0
                val valid = len in PinManager.MIN_PIN_LENGTH..PinManager.MAX_PIN_LENGTH
                btnOk.isEnabled = valid
                btnOk.setTextColor(if (valid) accent else secondary)
                if (errorView.visibility == android.view.View.VISIBLE) {
                    errorView.visibility = android.view.View.GONE
                }
            }
        })

        fun submit() {
            if (!btnOk.isEnabled) return
            val text = input.text
            val chars = CharArray(text.length).also { text.getChars(0, text.length, it, 0) }
            // Clear the EditText so the PIN isn't retained in memory
            input.text.clear()
            consumed = true
            hideKeyboard(input)
            dismiss()
            onConfirm(chars)
        }

        btnOk.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }

        btnCancel.setOnClickListener {
            consumed = true
            hideKeyboard(input)
            dismiss()
            onCancel()
        }

        // Auto-focus and show keyboard
        input.requestFocus()
        input.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    fun setError(message: String) {
        val errorView = findViewById<TextView>(R.id.pinDialogError) ?: return
        errorView.text = message
        errorView.visibility = android.view.View.VISIBLE
    }

    private fun hideKeyboard(input: EditText) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }
}
