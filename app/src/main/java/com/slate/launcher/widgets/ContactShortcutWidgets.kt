package com.slate.launcher.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Tap → opens the system dialer with the number pre-filled. No permission required. */
class CallShortcutWidget(private val shortcut: ContactShortcut) : QuickWidget() {
    override val id: String get() = shortcut.id
    override val displayName: String get() = "Call ${shortcut.displayName}"
    override fun renderLabel(context: Context) = WidgetLabel("call ${shortcut.displayName}")

    override fun onTap(context: Context) {
        val telUri = Uri.parse("tel:${Uri.encode(shortcut.number)}")
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, telUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Tap → `ACTION_SENDTO` with `smsto:` URI opens the default SMS composer to this number. */
class SmsShortcutWidget(private val shortcut: ContactShortcut) : QuickWidget() {
    override val id: String get() = shortcut.id
    override val displayName: String get() = "Text ${shortcut.displayName}"
    override fun renderLabel(context: Context) = WidgetLabel("text ${shortcut.displayName}")

    override fun onTap(context: Context) {
        val smsUri = Uri.parse("smsto:${Uri.encode(shortcut.number)}")
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, smsUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
