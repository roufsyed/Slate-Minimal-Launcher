package com.slate.launcher.widgets

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import com.slate.launcher.PreferencesManager
import com.slate.launcher.R

/**
 * Reorder dialog for the quick-toggles strip. Renders the currently-enabled widgets in order,
 * each with up/down arrow buttons. Tapping an arrow swaps with the adjacent row and writes the
 * new order to [PreferencesManager.quickStripWidgets] immediately — no explicit save. The strip
 * picks up the new order on the next home foreground.
 *
 * Disabled widgets and unresolvable IDs are silently dropped from the list (orphaned shortcut
 * IDs from a stale backup, contact shortcuts whose data was wiped, etc.). The dialog reads the
 * current order once on open and operates on its own mutable copy to keep the UI snappy.
 */
class WidgetArrangeDialog(
    context: Context,
    private val prefs: PreferencesManager,
    private val onChanged: () -> Unit
) : Dialog(context, R.style.SlateDialogTheme) {

    companion object {
        private var active: WidgetArrangeDialog? = null

        /** Dismiss any showing instance — call from host Activity.onDestroy() to avoid leaks. */
        fun dismissActive() {
            active?.let { runCatching { it.dismiss() } }
            active = null
        }
    }

    private lateinit var listContainer: LinearLayout
    private var primary: Int = Color.WHITE
    private var secondary: Int = Color.parseColor("#999999")
    private var accent: Int = Color.parseColor("#8888FF")
    private val orderedIds = mutableListOf<String>()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = context.resources.displayMetrics
        window?.setLayout(
            (dm.widthPixels * 0.9).toInt(),
            (dm.heightPixels * 0.8).toInt()
        )
        window?.setGravity(Gravity.CENTER)
        setCanceledOnTouchOutside(true)

        val bgColor = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bgColor)
        primary = if (isLight) Color.BLACK else Color.WHITE
        secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgColor)
                cornerRadius = density * 12
            }
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(
            root,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(TextView(context).apply {
            text = "ARRANGE WIDGETS"
            textSize = 13f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
            isAllCaps = true
            setPadding(0, 0, 0, (16 * density).toInt())
        })

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
            isVerticalFadingEdgeEnabled = false
        }
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        // Done button
        val closeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        closeRow.addView(TextView(context).apply {
            text = "Done"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            val hPad = (16 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            setOnClickListener { dismiss() }
        })
        root.addView(closeRow)

        setOnDismissListener { if (active === this) active = null }
        active = this

        // Snapshot the saved order, filtering to widgets we can actually resolve. Orphaned ids
        // (e.g., a contact shortcut whose data was cleared) are dropped here AND immediately
        // evicted from prefs, so the stored CSV doesn't bloat with stale ids when the user
        // opens-and-closes the dialog without making changes. QuickStripManager also filters
        // unresolvable ids at render time — this is a belt-and-braces cleanup.
        val stored = prefs.quickStripWidgets
        orderedIds.clear()
        stored.forEach { id ->
            if (WidgetCatalog.byId(prefs, id) != null) orderedIds.add(id)
        }
        if (orderedIds.size != stored.size) {
            prefs.quickStripWidgets = orderedIds.toList()
            onChanged()
        }

        renderList()
    }

    private fun renderList() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        for (index in orderedIds.indices) {
            listContainer.addView(buildRow(index))
        }
    }

    private fun buildRow(index: Int): View {
        val density = context.resources.displayMetrics.density
        val id = orderedIds[index]
        val widget = WidgetCatalog.byId(prefs, id)
        val displayName = widget?.displayName ?: id

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
        }

        row.addView(TextView(context).apply {
            text = displayName
            textSize = 16f
            setTextColor(primary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { weight = 1f }
        })

        // Up button — disabled on first row.
        row.addView(arrowButton("↑", "Move up", enabled = index > 0) {
            swap(index, index - 1)
        })
        // Down button — disabled on last row.
        row.addView(arrowButton("↓", "Move down", enabled = index < orderedIds.size - 1) {
            swap(index, index + 1)
        })
        return row
    }

    private fun arrowButton(
        glyph: String,
        accessibilityLabel: String,
        enabled: Boolean,
        onClick: () -> Unit
    ): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = glyph
            textSize = 22f
            setTextColor(if (enabled) accent else secondary)
            alpha = if (enabled) 1f else 0.3f
            // Big tap target — arrows need room for one-handed reach.
            val hPad = (14 * density).toInt()
            val vPad = (8 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            // TalkBack reads the glyph as "↑" / "↓" verbatim; give it a meaningful label.
            contentDescription = accessibilityLabel
            // isEnabled drives the standard "disabled" announcement for accessibility services.
            isEnabled = enabled
            if (enabled) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
    }

    private fun swap(a: Int, b: Int) {
        if (a !in orderedIds.indices || b !in orderedIds.indices) return
        val tmp = orderedIds[a]
        orderedIds[a] = orderedIds[b]
        orderedIds[b] = tmp
        prefs.quickStripWidgets = orderedIds.toList()
        onChanged()
        renderList()
    }
}
