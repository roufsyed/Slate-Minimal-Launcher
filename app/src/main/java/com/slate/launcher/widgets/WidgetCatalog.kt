package com.slate.launcher.widgets

import com.slate.launcher.PreferencesManager
import com.slate.launcher.shortcuts.PinnedShortcutStore
import com.slate.launcher.shortcuts.ShortcutDestination

/**
 * Source of truth for the available widget set. Two flavours:
 *   1. Static widgets ([staticWidgets]) - fixed, stateless singletons, one entry per kind.
 *   2. Dynamic contact-shortcut widgets - one instance per pinned contact, persisted via
 *      [ContactShortcutStore]. Resolved by id prefix ("call:" / "sms:").
 */
object WidgetCatalog {

    val staticWidgets: List<QuickWidget> = listOf(
        // Time
        ClockWidget,
        DateWidget,
        NextAlarmWidget,
        // Power
        BatteryPercentWidget,
        ChargingWidget,
        TimeToFullWidget,
        BatteryTempWidget,
        UptimeWidget,
        // Connectivity
        WifiWidget,
        BluetoothWidget,
        MobileDataWidget,
        AirplaneWidget,
        // Audio
        DndWidget,
        MediaVolumeWidget,
        RingerModeWidget,
        // System
        TorchWidget,
        BrightnessWidget,
        LocationWidget,
        NfcWidget,
    )

    /** Static widgets + all currently-pinned contact shortcuts + all currently-pinned app
     * shortcuts targeting the widget strip. Order matches picker display. */
    fun allFor(prefs: PreferencesManager): List<QuickWidget> {
        val contactShortcuts = ContactShortcutStore.all(prefs).map { it.toWidget() }
        val pinnedShortcuts = PinnedShortcutStore.all(prefs)
            .filter { ShortcutDestination.WIDGET_STRIP in it.destinations }
            .map { it.toWidget() }
        return staticWidgets + contactShortcuts + pinnedShortcuts
    }

    fun byId(prefs: PreferencesManager, id: String): QuickWidget? {
        staticWidgets.firstOrNull { it.id == id }?.let { return it }
        if (id.startsWith("shortcut:")) {
            return PinnedShortcutStore.find(prefs, id)
                ?.takeIf { ShortcutDestination.WIDGET_STRIP in it.destinations }
                ?.toWidget()
        }
        // Dynamic resolution for "call:<uri>" / "sms:<uri>" identifiers.
        val sep = id.indexOf(':')
        if (sep <= 0) return null
        return ContactShortcutStore.find(prefs, id)?.toWidget()
    }

    private fun ContactShortcut.toWidget(): QuickWidget = when (type) {
        ContactShortcut.Type.CALL -> CallShortcutWidget(this)
        ContactShortcut.Type.SMS -> SmsShortcutWidget(this)
    }
}
