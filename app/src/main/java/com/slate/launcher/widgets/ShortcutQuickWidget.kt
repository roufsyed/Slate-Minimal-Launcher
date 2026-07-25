package com.slate.launcher.widgets

import android.content.Context
import com.slate.launcher.shortcuts.PinnedShortcut
import com.slate.launcher.shortcuts.PinnedShortcutStore

/**
 * Renders a [PinnedShortcut] in the quick-toggles strip. Text-only, like every other
 * [QuickWidget] - the only icon anywhere in this feature lives in the transient picker dialog,
 * never on a permanent row. The trailing arrow matches the application-list row's marker
 * ([com.slate.launcher.AppDrawerFragment]'s `createShortcutTextView`) so a shortcut pinned to
 * both destinations reads identically in either place.
 */
class ShortcutQuickWidget(private val shortcut: PinnedShortcut) : QuickWidget() {

    override val id: String get() = shortcut.id
    override val displayName: String get() = shortcut.pinnedLabel

    override fun renderLabel(context: Context): WidgetLabel =
        WidgetLabel("${shortcut.pinnedLabel} ↗", active = !PinnedShortcutStore.isLikelyStale(shortcut))

    override fun onTap(context: Context) {
        val launcherApps = PinnedShortcutStore.launcherApps(context)
        PinnedShortcutStore.startShortcut(launcherApps, shortcut)
    }
}

fun PinnedShortcut.toWidget(): QuickWidget = ShortcutQuickWidget(this)
