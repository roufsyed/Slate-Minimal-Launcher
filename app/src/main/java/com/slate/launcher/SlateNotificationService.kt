package com.slate.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Collections

class SlateNotificationService : NotificationListenerService() {

    companion object {
        /** Packages that currently have at least one active notification. */
        val activePackages: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        /** Called on the service thread whenever the set changes. */
        var onChange: (() -> Unit)? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (activePackages.add(sbn.packageName)) onChange?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        // Only remove if no other notifications remain from this package
        val stillActive = try {
            activeNotifications?.any { it.packageName == sbn.packageName } == true
        } catch (_: Exception) { false }

        if (!stillActive && activePackages.remove(sbn.packageName)) {
            onChange?.invoke()
        }
    }

    override fun onListenerConnected() {
        try {
            activePackages.clear()
            activeNotifications?.forEach { activePackages.add(it.packageName) }
            onChange?.invoke()
        } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        activePackages.clear()
        onChange?.invoke()
    }
}
