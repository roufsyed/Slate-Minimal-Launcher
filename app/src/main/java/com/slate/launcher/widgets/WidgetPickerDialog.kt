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
import com.google.android.material.materialswitch.MaterialSwitch
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import com.slate.launcher.PreferencesManager
import com.slate.launcher.R

/**
 * Multi-select picker for the quick-toggles strip. Renders three sections in order:
 *   1. "Add call shortcut" / "Add SMS shortcut" action rows that ask the host Activity to launch
 *      the system contact picker via [onAddShortcut].
 *   2. Currently-pinned contact shortcuts, each with an enable-in-strip switch and a delete
 *      button.
 *   3. The static widget catalog (clock, battery, wifi, …), each with an enable-in-strip switch.
 *
 * Saves on every toggle/delete (no explicit confirm button) so the UI feels live. Call
 * [refreshList] from the host after creating or removing shortcuts to redraw without dismissing.
 */
class WidgetPickerDialog(
    context: Context,
    private val prefs: PreferencesManager,
    private val onChanged: () -> Unit,
    private val onAddShortcut: (ContactShortcut.Type) -> Unit
) : Dialog(context, R.style.SlateDialogTheme) {

    companion object {
        private var active: WidgetPickerDialog? = null

        /** Dismiss any showing instance — call from host Activity.onDestroy() to avoid leaks. */
        fun dismissActive() {
            active?.let { runCatching { it.dismiss() } }
            active = null
        }

        /** Refresh the currently-showing picker (if any). Host calls this after picker results. */
        fun refreshActive() {
            active?.refreshList()
        }
    }

    private lateinit var listContainer: LinearLayout
    private var primary: Int = Color.WHITE
    private var secondary: Int = Color.parseColor("#999999")
    private var accent: Int = Color.parseColor("#8888FF")

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
            text = "CHOOSE WIDGETS"
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

        refreshList()
    }

    /** Rebuild the list — called on first show and from [refreshActive] after picker results. */
    fun refreshList() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val currentSelection = prefs.quickStripWidgets.toMutableList()

        // Section 1: add-shortcut action rows
        listContainer.addView(addActionRow("+ Add call shortcut", ContactShortcut.Type.CALL))
        listContainer.addView(addActionRow("+ Add SMS shortcut", ContactShortcut.Type.SMS))

        // Section 2: pinned contact shortcuts (with delete)
        val shortcuts = ContactShortcutStore.all(prefs)
        if (shortcuts.isNotEmpty()) {
            listContainer.addView(sectionLabel("CONTACT SHORTCUTS"))
            shortcuts.forEach { shortcut ->
                listContainer.addView(createShortcutRow(shortcut, currentSelection))
            }
        }

        // Section 3: static widgets
        listContainer.addView(sectionLabel("WIDGETS"))
        WidgetCatalog.staticWidgets.forEach { widget ->
            if (!widget.isAvailable(context)) return@forEach
            listContainer.addView(createWidgetRow(widget, currentSelection))
        }
    }

    private fun addActionRow(label: String, type: ContactShortcut.Type): View {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(accent)
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onAddShortcut(type) }
        }
    }

    private fun sectionLabel(text: String): View {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(secondary)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
            isAllCaps = true
            setPadding(0, (20 * density).toInt(), 0, (8 * density).toInt())
        }
    }

    private fun createShortcutRow(
        shortcut: ContactShortcut,
        currentSelection: MutableList<String>
    ): View {
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
        }

        val labelGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { weight = 1f }
        }
        val verb = if (shortcut.type == ContactShortcut.Type.CALL) "Call" else "Text"
        labelGroup.addView(TextView(context).apply {
            text = "$verb ${shortcut.displayName}"
            textSize = 16f
            setTextColor(primary)
        })
        labelGroup.addView(TextView(context).apply {
            text = shortcut.number
            textSize = 12f
            setTextColor(secondary)
            alpha = 0.7f
        })
        row.addView(labelGroup)

        // Enable-in-strip switch
        row.addView(buildEnableSwitch(shortcut.id, currentSelection))

        // Delete button
        row.addView(TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(secondary)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ContactShortcutStore.remove(prefs, shortcut.id)
                // Also drop from active strip selection if present.
                if (currentSelection.remove(shortcut.id)) {
                    prefs.quickStripWidgets = currentSelection.toList()
                }
                onChanged()
                refreshList()
            }
        })
        return row
    }

    private fun createWidgetRow(
        widget: QuickWidget,
        currentSelection: MutableList<String>
    ): View {
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
        }

        val labelGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { weight = 1f }
        }
        labelGroup.addView(TextView(context).apply {
            text = widget.displayName
            textSize = 16f
            setTextColor(primary)
        })
        if (widget.requiresSpecialAccess) {
            labelGroup.addView(TextView(context).apply {
                text = "Requires special access"
                textSize = 12f
                setTextColor(secondary)
                alpha = 0.7f
            })
        }
        widget.pickerNote?.let { note ->
            labelGroup.addView(TextView(context).apply {
                text = note
                textSize = 12f
                setTextColor(secondary)
                alpha = 0.7f
            })
        }
        row.addView(labelGroup)
        row.addView(buildEnableSwitch(widget.id, currentSelection))
        return row
    }

    private fun buildEnableSwitch(id: String, currentSelection: MutableList<String>): MaterialSwitch {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        return MaterialSwitch(context).apply {
            isChecked = currentSelection.contains(id)
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, if (isLight) Color.WHITE else Color.parseColor("#888888"))
            )
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, if (isLight) Color.parseColor("#CCCCCC") else Color.parseColor("#555555"))
            )
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (!currentSelection.contains(id)) currentSelection.add(id)
                } else {
                    currentSelection.remove(id)
                }
                prefs.quickStripWidgets = currentSelection.toList()
                onChanged()
            }
        }
    }
}
