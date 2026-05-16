package com.slate.launcher.widgets

import com.slate.launcher.PreferencesManager

/**
 * Source of truth for the available widget set. Two flavours:
 *   1. Static widgets ([staticWidgets]) — fixed, stateless singletons, one entry per kind.
 *   2. Dynamic contact-shortcut widgets — one instance per pinned contact, persisted via
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

    /** Static widgets + all currently-pinned contact shortcuts. Order matches picker display. */
    fun allFor(prefs: PreferencesManager): List<QuickWidget> {
        val shortcuts = ContactShortcutStore.all(prefs).map { it.toWidget() }
        return staticWidgets + shortcuts
    }

    fun byId(prefs: PreferencesManager, id: String): QuickWidget? {
        staticWidgets.firstOrNull { it.id == id }?.let { return it }
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
