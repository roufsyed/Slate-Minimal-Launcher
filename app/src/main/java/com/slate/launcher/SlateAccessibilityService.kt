package com.slate.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

class SlateAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: SlateAccessibilityService? = null

        fun lockScreen(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        /**
         * Whether the Slate accessibility service is currently enabled at the OS level.
         *
         * Robust across OEMs: primary check uses [AccessibilityManager.getEnabledAccessibilityServiceList]
         * which honours device-policy and per-OEM nuance; falls back to a direct read of
         * [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] when the manager returns null.
         *
         * Note: this reflects the OS-level "is the service enabled" state, which can be true
         * even when `instance` is still null (the service hasn't been bound to our process yet
         * - common during the moments after the user toggles it on, especially on slower
         * OEMs). Callers that need "is the service usable RIGHT NOW" should also check
         * [lockScreen]'s return value or guard their action separately.
         */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val expected = ComponentName(context, SlateAccessibilityService::class.java)
                val running = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                for (info in running) {
                    val component = ComponentName.unflattenFromString(info.id)
                    if (expected == component) return true
                }
            }
            val cn = ComponentName(context, SlateAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(cn.flattenToString())
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
