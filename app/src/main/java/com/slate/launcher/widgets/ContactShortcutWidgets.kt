package com.slate.launcher.widgets

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.slate.launcher.PreferencesManager

/**
 * Tap: dispatches to the system dialer (ACTION_DIAL) by default. When the user opts in via
 * Settings → Quick toggles → Direct call AND maps the configured trigger to this gesture, taps
 * place the call directly via ACTION_CALL. See [dispatchCall] for the permission check.
 */
class CallShortcutWidget(private val shortcut: ContactShortcut) : QuickWidget() {
    override val id: String get() = shortcut.id
    override val displayName: String get() = "Call ${shortcut.displayName}"
    override fun renderLabel(context: Context) = WidgetLabel("call ${shortcut.displayName}")

    /**
     * Standard tap handler. Direct-calls only when the user has chosen "tap" as the trigger
     * AND CALL_PHONE is currently granted. Any other state opens the dialer.
     */
    override fun onTap(context: Context) {
        val prefs = PreferencesManager(context)
        val direct = prefs.directCallEnabled && prefs.directCallTrigger == "tap"
        dispatchCall(context, direct)
    }

    /**
     * Long-press handler — called from AppDrawerFragment.onLongPress when the touch hit-tests
     * to this widget AND the user has chosen "longPress" as the direct-call trigger. The
     * caller has already verified the trigger pref; this helper just resolves direct vs dialer
     * based on the live permission state. Never surfaces the home long-press menu — by the
     * time this runs the caller has already committed to the call-shortcut interaction.
     */
    fun onLongPressDirect(context: Context) {
        dispatchCall(context, true)
    }

    /**
     * Build the call/dial intent. [direct] is the caller's authoritative "should we try direct
     * call here?" decision; we still re-check permission live because the user can revoke it
     * between Settings and home with no signal to us. Permission-denied falls through to
     * ACTION_DIAL — the same safe behaviour the user had before opting in.
     */
    private fun dispatchCall(context: Context, direct: Boolean) {
        val telUri = Uri.parse("tel:${Uri.encode(shortcut.number)}")
        val canDirect = direct && ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val action = if (canDirect) Intent.ACTION_CALL else Intent.ACTION_DIAL
        runCatching {
            context.startActivity(
                Intent(action, telUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
