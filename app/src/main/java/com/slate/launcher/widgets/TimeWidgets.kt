package com.slate.launcher.widgets

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.util.Calendar

object ClockWidget : QuickWidget() {
    override val id = "clock"
    override val displayName = "Clock"
    override fun renderLabel(context: Context): WidgetLabel {
        val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a"
        val text = DateFormat.format(pattern, Calendar.getInstance()).toString()
        return WidgetLabel("Time: $text", active = true)
    }
    override fun onTap(context: Context) {
        runCatching {
            context.startActivity(
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        // ACTION_TIME_TICK fires at every minute boundary; cheap and accurate.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = onChanged()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return object : WidgetSubscription {
            override fun close() {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}

object DateWidget : QuickWidget() {
    override val id = "date"
    override val displayName = "Date"
    override fun renderLabel(context: Context): WidgetLabel {
        val text = DateFormat.format("EEE d MMM", Calendar.getInstance()).toString()
        return WidgetLabel("Date: $text", active = true)
    }
    override fun onTap(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setData(android.net.Uri.parse("content://com.android.calendar/time/"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        // Date changes at midnight — TIME_TICK fires before midnight transitions in practice.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = onChanged()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return object : WidgetSubscription {
            override fun close() {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}

object NextAlarmWidget : QuickWidget() {
    override val id = "next_alarm"
    override val displayName = "Next alarm"
    override fun renderLabel(context: Context): WidgetLabel {
        val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = am.nextAlarmClock ?: return WidgetLabel("Alarm: none", active = false)
        val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a"
        val text = DateFormat.format(pattern, next.triggerTime).toString()
        return WidgetLabel("Alarm: $text", active = true)
    }
    override fun onTap(context: Context) {
        runCatching {
            context.startActivity(
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    override fun startObserving(context: Context, onChanged: () -> Unit): WidgetSubscription {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = onChanged()
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        return object : WidgetSubscription {
            override fun close() {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}

