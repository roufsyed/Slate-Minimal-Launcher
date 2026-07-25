package com.slate.launcher.shortcuts

import android.app.Activity
import android.app.Dialog
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe
import com.slate.launcher.PreferencesManager
import com.slate.launcher.R

class ShortcutPickerDialog private constructor(
    activity: Activity,
    private val prefs: PreferencesManager,
    private val launcherApps: LauncherApps,
    private val destination: ShortcutDestination,
    private val sourcePackage: String,
    private val sourceAppName: String
) : Dialog(activity, R.style.SlateDialogTheme) {

    companion object {
        private var active: ShortcutPickerDialog? = null

        fun show(
            activity: Activity,
            prefs: PreferencesManager,
            launcherApps: LauncherApps,
            destination: ShortcutDestination,
            sourcePackage: String,
            sourceAppName: String
        ) {
            // Guard against two live pickers for the same package racing each other's toggles.
            active?.let { runCatching { it.dismiss() } }
            val dialog = ShortcutPickerDialog(activity, prefs, launcherApps, destination, sourcePackage, sourceAppName)
            active = dialog
            dialog.show()
        }
    }

    private lateinit var listContainer: LinearLayout
    private var primary: Int = Color.WHITE
    private var secondary: Int = Color.parseColor("#999999")
    private var accent: Int = Color.parseColor("#8888FF")
    private var density: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = context.resources.displayMetrics
        window?.setLayout((dm.widthPixels * 0.9).toInt(), (dm.heightPixels * 0.8).toInt())
        window?.setGravity(Gravity.CENTER)
        setCanceledOnTouchOutside(true)

        val bgColor = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bgColor)
        primary = if (isLight) Color.BLACK else Color.WHITE
        secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        density = context.resources.displayMetrics.density

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
        setContentView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(TextView(context).apply {
            text = sourceAppName
            textSize = 13f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
            isAllCaps = true
            setPadding(0, 0, 0, (16 * density).toInt())
        })

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            isVerticalFadingEdgeEnabled = false
        }
        listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)
        root.addView(scroll)

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

        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()

        if (!PinnedShortcutStore.hasShortcutHostPermissionSafe(launcherApps)) {
            listContainer.addView(emptyRow("Set Slate as your default launcher to add shortcuts"))
            return
        }
        val shortcuts = PinnedShortcutStore.queryShortcuts(launcherApps, sourcePackage)
        if (shortcuts.isEmpty()) {
            listContainer.addView(emptyRow("No shortcuts found for $sourceAppName"))
            return
        }
        shortcuts.forEach { info -> listContainer.addView(createShortcutRow(info)) }
    }

    private fun emptyRow(text: String): View {
        return TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(secondary)
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
        }
    }

    private fun createShortcutRow(info: ShortcutInfo): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
        }

        val icon = runCatching {
            launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
        }.getOrNull()
        if (icon != null) {
            row.addView(ImageView(context).apply {
                setImageDrawable(icon)
                val size = (36 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (12 * density).toInt() }
            })
        }

        val label = (info.longLabel ?: info.shortLabel)?.toString() ?: info.id
        val existing = PinnedShortcutStore.findByShortcut(prefs, sourcePackage, info.id)

        val labelGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        labelGroup.addView(TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(primary)
        })
        val otherDestination = destination.other()
        if (existing != null && otherDestination in existing.destinations) {
            labelGroup.addView(TextView(context).apply {
                text = "Also pinned to ${otherDestination.displayLabel().lowercase()}"
                textSize = 12f
                setTextColor(secondary)
                alpha = 0.7f
            })
        }
        row.addView(labelGroup)
        row.addView(buildSwitch(info, label, existing))
        return row
    }

    private fun buildSwitch(info: ShortcutInfo, label: String, existing: PinnedShortcut?): MaterialSwitch {
        val isLight = isColorLight(parseColorSafe(prefs.backgroundColor))
        return MaterialSwitch(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (4 * density).toInt() }
            isChecked = existing != null && destination in existing.destinations
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
                    PinnedShortcutStore.add(
                        prefs = prefs,
                        launcherApps = launcherApps,
                        sourcePackage = sourcePackage,
                        shortcutId = info.id,
                        label = label,
                        destination = destination,
                        pinnedAtMs = System.currentTimeMillis()
                    )
                } else {
                    PinnedShortcutStore.remove(prefs, launcherApps, sourcePackage, info.id, destination)
                }
            }
        }
    }
}
