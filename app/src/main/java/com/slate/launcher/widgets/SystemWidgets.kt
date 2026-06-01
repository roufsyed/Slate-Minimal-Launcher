package com.slate.launcher.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Find the first camera that has a flash, or null if the device has none. */
private fun firstFlashCameraId(context: Context): String? {
    val mgr = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        ?: return null
    return runCatching {
        mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
}

object TorchWidget : QuickWidget() {
    override val id = "torch"
    override val displayName = "Torch"

    // The widget tracks the latest state from TorchCallback in a process-singleton so renderLabel
    // is cheap and synchronous. The callback is the only state source - CameraManager has no
    // "isTorchOn" getter.
    @Volatile private var lastKnownOn: Boolean = false

    override fun isAvailable(context: Context): Boolean = firstFlashCameraId(context) != null

    override fun renderLabel(context: Context): WidgetLabel =
        WidgetLabel("Torch", active = lastKnownOn)

    override fun onTap(context: Context) {
        val mgr = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return
        val id = firstFlashCameraId(context) ?: return
        // Optimistically flip the cached state before the callback confirms - gives instant UI
        // feedback even though the official source of truth is the callback.
        val next = !lastKnownOn
        runCatching { mgr.setTorchMode(id, next) }
    }

    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        val mgr = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return NoOpSubscription
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                lastKnownOn = enabled
                onChanged()
            }
            override fun onTorchModeUnavailable(cameraId: String) {
                lastKnownOn = false
                onChanged()
            }
        }
        // Register on the main thread so the callback fires on the main thread.
        mgr.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        return object : WidgetSubscription {
            override fun close() {
                runCatching { mgr.unregisterTorchCallback(callback) }
            }
        }
    }
}

object BrightnessWidget : QuickWidget() {
    override val id = "brightness"
    override val displayName = "Brightness"
    override fun renderLabel(context: Context): WidgetLabel {
        // Settings.System.SCREEN_BRIGHTNESS is 0..255 in legacy units. Read-only here - writing
        // would require WRITE_SETTINGS special access, which we don't take.
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        if (raw < 0) return WidgetLabel("Brightness: -", active = false)
        val pct = (raw * 100 / 255).coerceIn(0, 100)
        return WidgetLabel("Brightness: ${pct}%", active = true)
    }
    override fun onTap(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChanged()
        }
        val uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
        context.contentResolver.registerContentObserver(uri, false, observer)
        return object : WidgetSubscription {
            override fun close() {
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
            }
        }
    }
}

object LocationWidget : QuickWidget() {
    override val id = "location"
    override val displayName = "Location services"
    override fun renderLabel(context: Context): WidgetLabel {
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val on = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
            lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        }
        return WidgetLabel("Location", active = on)
    }
    override fun onTap(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return NoOpSubscription
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = onChanged()
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        return object : WidgetSubscription {
            override fun close() {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}

object NfcWidget : QuickWidget() {
    override val id = "nfc"
    override val displayName = "NFC"
    override fun isAvailable(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context.applicationContext) != null
    override fun renderLabel(context: Context): WidgetLabel {
        val adapter = NfcAdapter.getDefaultAdapter(context.applicationContext)
        val on = adapter?.isEnabled == true
        return WidgetLabel("NFC", active = on)
    }
    override fun onTap(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = onChanged()
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        return object : WidgetSubscription {
            override fun close() {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}
