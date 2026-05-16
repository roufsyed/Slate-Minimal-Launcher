package com.slate.launcher.widgets

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat

private fun openSoundSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

object DndWidget : QuickWidget() {
    override val id = "dnd"
    override val displayName = "Do not disturb"
    override fun renderLabel(context: Context): WidgetLabel {
        val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val on = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return WidgetLabel("dnd", active = on)
    }
    override fun onTap(context: Context) {
        // Scope A: don't write the policy (would require ACCESS_NOTIFICATION_POLICY special access).
        // Deep-link the user to the DND settings page instead.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SOUND_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = onChanged()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        return object : WidgetSubscription {
            override fun close() {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}

object MediaVolumeWidget : QuickWidget() {
    override val id = "media_vol"
    override val displayName = "Media volume"
    override fun renderLabel(context: Context): WidgetLabel {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val pct = (100 * current / max)
        return WidgetLabel("vol ${pct}%", active = current > 0)
    }
    override fun onTap(context: Context) = openSoundSettings(context)
    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        // Audio volume doesn't expose a stable broadcast on all OEMs. Observing the system
        // volume content URI is the supported cross-vendor approach.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChanged()
        }
        val uri = Settings.System.CONTENT_URI
        context.contentResolver.registerContentObserver(uri, true, observer)
        return object : WidgetSubscription {
            override fun close() {
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
            }
        }
    }
}

object RingerModeWidget : QuickWidget() {
    override val id = "ringer"
    override val displayName = "Ringer mode"
    override fun renderLabel(context: Context): WidgetLabel {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val text = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "ring"
        }
        val active = am.ringerMode != AudioManager.RINGER_MODE_NORMAL
        return WidgetLabel(text, active = active)
    }
    override fun onTap(context: Context) = openSoundSettings(context)
    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = onChanged()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        return object : WidgetSubscription {
            override fun close() {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}
