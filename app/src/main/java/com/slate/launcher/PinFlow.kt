package com.slate.launcher

import android.widget.Toast
import androidx.fragment.app.FragmentActivity

/**
 * Multi-step PIN flows built on top of [PinEntryDialog]. Each flow zeros the entered PIN before
 * dispatching the result. Lockout is enforced via [PinManager] for verify flows.
 */
object PinFlow {

    /**
     * Set a brand-new PIN. Shows "Set PIN" then "Confirm PIN"; on mismatch, restarts. On a
     * trivial-PIN choice (1234, 0000, …) shows a one-time warning but does not block.
     */
    fun setupNew(
        activity: FragmentActivity,
        prefs: PreferencesManager,
        pinManager: PinManager,
        onComplete: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        PinEntryDialog(
            context = activity,
            bgColor = prefs.backgroundColor,
            title = "SET PIN",
            message = "Choose a 4–8 digit PIN. You'll need it to view hidden apps.",
            confirmLabel = "Next",
            onConfirm = { newPin ->
                if (PinManager.isTrivial(newPin)) {
                    Toast.makeText(
                        activity,
                        "Heads up: that PIN is easy to guess.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                askConfirm(activity, prefs, pinManager, newPin, onComplete, onCancel)
            },
            onCancel = onCancel
        ).show()
    }

    private fun askConfirm(
        activity: FragmentActivity,
        prefs: PreferencesManager,
        pinManager: PinManager,
        newPin: CharArray,
        onComplete: () -> Unit,
        onCancel: () -> Unit
    ) {
        PinEntryDialog(
            context = activity,
            bgColor = prefs.backgroundColor,
            title = "CONFIRM PIN",
            message = "Re-enter the PIN you just chose.",
            confirmLabel = "Save",
            onConfirm = { confirmPin ->
                if (newPin.contentEquals(confirmPin)) {
                    pinManager.setPin(newPin.copyOf())
                    newPin.fill(' ')
                    confirmPin.fill(' ')
                    onComplete()
                } else {
                    newPin.fill(' ')
                    confirmPin.fill(' ')
                    Toast.makeText(activity, "PINs didn't match. Try again.", Toast.LENGTH_SHORT).show()
                    setupNew(activity, prefs, pinManager, onComplete, onCancel)
                }
            },
            onCancel = {
                newPin.fill(' ')
                onCancel()
            }
        ).show()
    }

    /**
     * Prompt the user to enter the current PIN. Verifies against [pinManager]; records
     * success/failure for rate-limiting. Re-prompts on wrong PIN until [onCancel] or lockout.
     */
    fun verifyExisting(
        activity: FragmentActivity,
        prefs: PreferencesManager,
        pinManager: PinManager,
        title: String,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        verifyExistingWithMessage(
            activity, prefs, pinManager,
            title = title,
            message = "Enter your PIN to continue.",
            onSuccess = onSuccess,
            onCancel = onCancel
        )
    }

    private fun verifyExistingWithMessage(
        activity: FragmentActivity,
        prefs: PreferencesManager,
        pinManager: PinManager,
        title: String,
        message: String,
        onSuccess: () -> Unit,
        onCancel: () -> Unit
    ) {
        val lockoutMs = pinManager.lockoutMillisRemaining()
        if (lockoutMs > 0) {
            val seconds = (lockoutMs / 1000).coerceAtLeast(1)
            val msg = if (seconds >= 60) {
                "Too many attempts. Try again in ${seconds / 60} min."
            } else {
                "Too many attempts. Try again in $seconds s."
            }
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            onCancel()
            return
        }

        val dialog = PinEntryDialog(
            context = activity,
            bgColor = prefs.backgroundColor,
            title = title.uppercase(),
            message = message,
            confirmLabel = "Unlock",
            onConfirm = { pin ->
                if (pinManager.verifyPin(pin)) {
                    pinManager.recordSuccess()
                    onSuccess()
                } else {
                    pinManager.recordFailure()
                    val remaining = pinManager.lockoutMillisRemaining()
                    if (remaining > 0) {
                        val sec = (remaining / 1000).coerceAtLeast(1)
                        val msg = if (sec >= 60) {
                            "Wrong PIN. Locked for ${sec / 60} min."
                        } else {
                            "Wrong PIN. Locked for $sec s."
                        }
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                        onCancel()
                    } else {
                        verifyExistingWithMessage(
                            activity, prefs, pinManager,
                            title = title,
                            message = "Wrong PIN. Try again.",
                            onSuccess = onSuccess,
                            onCancel = onCancel
                        )
                    }
                }
            },
            onCancel = onCancel
        )
        dialog.show()
    }

    /**
     * Verify current PIN → set new PIN. Used by the "Change PIN" settings row.
     */
    fun changePin(
        activity: FragmentActivity,
        prefs: PreferencesManager,
        pinManager: PinManager,
        onComplete: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        verifyExisting(
            activity, prefs, pinManager,
            title = "Change PIN",
            onSuccess = { setupNew(activity, prefs, pinManager, onComplete, onCancel) },
            onCancel = onCancel
        )
    }
}
