package com.slate.launcher.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat

private fun batteryReceiver(context: Context, onChanged: () -> Unit): WidgetSubscription {
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_BATTERY_CHANGED)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = onChanged()
    }
    // RECEIVER_EXPORTED required on API 34+ for system-originated broadcasts; ContextCompat
    // routes correctly on older APIs.
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    return object : WidgetSubscription {
        override fun close() {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}

/** Tries to deep-link to the battery settings page; falls back silently if unsupported. */
private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

object BatteryPercentWidget : QuickWidget() {
    override val id = "battery"
    override val displayName = "Battery %"
    override fun renderLabel(context: Context): WidgetLabel {
        val mgr = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = mgr.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val text = if (pct in 0..100) "${pct}%" else "—%"
        return WidgetLabel(text, active = true)
    }
    override fun onTap(context: Context) = openBatterySettings(context)
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        batteryReceiver(context, onChanged)
}

object ChargingWidget : QuickWidget() {
    override val id = "charging"
    override val displayName = "Charging indicator"
    override fun renderLabel(context: Context): WidgetLabel {
        val mgr = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val charging = mgr.isCharging
        return WidgetLabel(if (charging) "charging" else "charging", active = charging)
    }
    override fun onTap(context: Context) = openBatterySettings(context)
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        batteryReceiver(context, onChanged)
}

object BatteryTempWidget : QuickWidget() {
    override val id = "battery_temp"
    override val displayName = "Battery temperature"
    override fun renderLabel(context: Context): WidgetLabel {
        // EXTRA_TEMPERATURE is reported in tenths of a degree Celsius via the sticky broadcast.
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenthsC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tenthsC < 0) return WidgetLabel("—", active = false)
        val celsius = tenthsC / 10
        return WidgetLabel("${celsius}°C", active = true)
    }
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        batteryReceiver(context, onChanged)
}

object UptimeWidget : QuickWidget() {
    override val id = "uptime"
    override val displayName = "Uptime"
    override fun renderLabel(context: Context): WidgetLabel {
        val ms = SystemClock.elapsedRealtime()
        val totalMinutes = ms / 60_000L
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        val text = when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
        return WidgetLabel(text, active = true)
    }
    // No broadcast — strip will refresh on resume; close-enough granularity for an uptime label.
}
