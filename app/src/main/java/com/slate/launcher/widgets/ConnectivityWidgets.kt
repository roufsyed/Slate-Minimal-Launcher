package com.slate.launcher.widgets

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** BluetoothAdapter resolved via BluetoothManager - getDefaultAdapter() is deprecated API 31+. */
private fun bluetoothAdapter(context: Context): BluetoothAdapter? {
    val mgr = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return mgr?.adapter
}

private fun globalSettingObserver(
    context: Context,
    action: String,
    onChanged: () -> Unit
): WidgetSubscription {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = onChanged()
    }
    ContextCompat.registerReceiver(
        context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED
    )
    return object : WidgetSubscription {
        override fun close() {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}

private fun safeStart(context: Context, intent: Intent) {
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object WifiWidget : QuickWidget() {
    override val id = "wifi"
    override val displayName = "Wi-Fi"
    override fun renderLabel(context: Context): WidgetLabel {
        val mgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val on = mgr?.isWifiEnabled == true
        return WidgetLabel("Wi-Fi", active = on)
    }
    override fun onTap(context: Context) {
        // A29+: the inline Settings.Panel is the only sanctioned way for 3P apps to surface Wi-Fi
        // toggling. Pre-29 falls through to the classic settings screen.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        safeStart(context, intent)
    }
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        globalSettingObserver(context, WifiManager.WIFI_STATE_CHANGED_ACTION, onChanged)
}

object BluetoothWidget : QuickWidget() {
    override val id = "bt"
    override val displayName = "Bluetooth"
    override fun isAvailable(context: Context): Boolean =
        bluetoothAdapter(context) != null
    override fun renderLabel(context: Context): WidgetLabel {
        val on = bluetoothAdapter(context)?.isEnabled == true
        return WidgetLabel("Bluetooth", active = on)
    }
    override fun onTap(context: Context) {
        safeStart(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        globalSettingObserver(context, BluetoothAdapter.ACTION_STATE_CHANGED, onChanged)
}

object MobileDataWidget : QuickWidget() {
    override val id = "data"
    override val displayName = "Mobile data"
    override fun isAvailable(context: Context): Boolean {
        // Reading isDataEnabled() requires READ_BASIC_PHONE_STATE (normal) on API 33+.
        // On older Android the only safe read needs the dangerous READ_PHONE_STATE - we won't
        // declare that, so the widget hides itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val tm = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tm != null && tm.simState == TelephonyManager.SIM_STATE_READY
    }
    override fun renderLabel(context: Context): WidgetLabel {
        val tm = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val on = try {
            tm?.isDataEnabled == true
        } catch (_: SecurityException) {
            false
        }
        return WidgetLabel("Mobile data", active = on)
    }
    override fun onTap(context: Context) {
        safeStart(context, Intent(Settings.ACTION_DATA_USAGE_SETTINGS))
    }
    // No cheap event observer without TelephonyCallback + dangerous perm - refresh on resume.
}

object AirplaneWidget : QuickWidget() {
    override val id = "airplane"
    override val displayName = "Airplane mode"
    override fun renderLabel(context: Context): WidgetLabel {
        val on = Settings.Global.getInt(
            context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0
        return WidgetLabel("Airplane mode", active = on)
    }
    override fun onTap(context: Context) {
        safeStart(context, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
    }
    override fun startObserving(context: Context, onChanged: () -> Unit) =
        globalSettingObserver(context, Intent.ACTION_AIRPLANE_MODE_CHANGED, onChanged)
}

