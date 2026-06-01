package com.slate.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.slate.launcher.MainActivity.Companion.isColorLight
import com.slate.launcher.MainActivity.Companion.parseColorSafe

/**
 * One-time guided product tour. Walks the user through the non-obvious interactions (long-press,
 * gestures, folders, etc.) via a sequence of themed modal dialogs.
 *
 *   - Auto-triggers from `MainActivity.onResume` (via [resumeIfPending]) when the user has
 *     completed onboarding but hasn't seen the current tour version yet.
 *   - Re-runnable from Settings via [show], which resets to step 0.
 *   - Step state is persisted in [PreferencesManager.guidedTourStepIndex] so a process-death
 *     mid-tour resumes at the same step. `-1` signals "tour complete".
 *   - Conditional steps (gestures, double-tap, quick strip) are silently dropped when the
 *     corresponding feature is disabled - re-pitching opted-out features feels nag-y and tanks
 *     completion. Folders is always shown because it's high-value and zero-config-by-default.
 *
 * Bump [CURRENT_TOUR_VERSION] when meaningful content changes - existing users with a lower
 * `seenVersion` re-see the tour on next resume.
 */
object GuidedTourManager {

    /** In-code version; bump to surface tour-content updates to existing users. */
    const val CURRENT_TOUR_VERSION = 1

    /** Delay before the first auto-trigger so the home screen renders behind the dialog. */
    private const val AUTO_TRIGGER_DELAY_MS = 400L

    private var activeDialog: Dialog? = null
    // Tracks the pending auto-trigger callback so re-entries of `onResume` don't stack callbacks
    // and produce flicker. We cancel any pending callback before posting a new one.
    private var pendingTrigger: Runnable? = null

    /**
     * Call from `MainActivity.onResume`. Schedules the first-time tour or resumes an interrupted
     * one if the user hasn't yet seen the current tour version. No-op if onboarding hasn't
     * completed or the tour is already done.
     */
    fun resumeIfPending(activity: Activity, prefs: PreferencesManager) {
        if (!prefs.onboardingComplete) return
        if (prefs.guidedTourStepIndex == -1) return
        if (prefs.guidedTourSeenVersion >= CURRENT_TOUR_VERSION) return

        val decor = activity.window.decorView
        // Cancel any callback queued by a prior onResume so notifications, role-pickers, or
        // other transient pauses don't stack multiple 400ms timers and flicker the dialog.
        pendingTrigger?.let { decor.removeCallbacks(it) }
        val trigger = Runnable {
            pendingTrigger = null
            // Don't show on a destroyed OR paused activity - Dialog.show on a paused window can
            // throw BadTokenException on some OEMs and would land on top of whatever foreground
            // app the user has just switched to.
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            val lifecycleOwner = activity as? LifecycleOwner ?: return@Runnable
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                return@Runnable
            }
            launchInternal(activity, prefs, resumeFromCurrentStep = true)
        }
        pendingTrigger = trigger
        // Delay so the home content paints first - landing in a modal on a blank black screen
        // feels jarring.
        decor.postDelayed(trigger, AUTO_TRIGGER_DELAY_MS)
    }

    /** Manual entry point (e.g., Settings re-run row). Always restarts from step 0. */
    fun show(activity: Activity, prefs: PreferencesManager) {
        prefs.guidedTourStepIndex = 0
        launchInternal(activity, prefs, resumeFromCurrentStep = false)
    }

    /** Dismiss any showing dialog. Call from host Activity.onDestroy to avoid window leaks. */
    fun dismissActive() {
        // Drop any pending postDelayed callback too - its captured Activity will be gone.
        activeDialog?.let { d ->
            val decor = runCatching { d.window?.decorView }.getOrNull()
            pendingTrigger?.let { decor?.removeCallbacks(it) }
            runCatching { d.dismiss() }
        }
        pendingTrigger = null
        activeDialog = null
    }

    private fun launchInternal(
        activity: Activity,
        prefs: PreferencesManager,
        resumeFromCurrentStep: Boolean
    ) {
        val steps = buildSteps(prefs)
        if (steps.isEmpty()) {
            complete(prefs)
            return
        }
        val startIndex =
            if (resumeFromCurrentStep) prefs.guidedTourStepIndex.coerceIn(0, steps.size - 1)
            else 0
        showStep(activity, prefs, steps, startIndex)
    }

    /** Build the runtime step list, dropping conditional steps for disabled features. */
    private fun buildSteps(prefs: PreferencesManager): List<TourStep> = buildList {
        add(TourStep(
            "Welcome to Slate",
            "Slate shows your apps as plain text - no icons, no widgets. " +
                    "Here's a 30-second tour of how to use it."
        ))
        add(TourStep(
            "Tap to open",
            "Tap any app's name to open it. The more you use an app, the larger its name " +
                    "appears (in Flow view)."
        ))
        add(TourStep(
            "Long-press an app",
            "Press and hold any app name to pin it, hide it, move it to a folder, " +
                    "rename it, change its color, or uninstall."
        ))
        add(TourStep(
            "Long-press the home screen",
            "Press and hold a blank area of the home screen for Customize (Settings), " +
                    "Hidden Apps, and FAQ."
        ))
        if (prefs.searchEnabled) {
            add(TourStep(
                "Swipe up to search",
                "Swipe up anywhere on home to open the search bar. Start typing to filter apps."
            ))
        }
        add(TourStep(
            "Folders",
            "Long-press an app and pick \"Move to folder\" to group related apps. " +
                    "A folder appears as \"Name ›\" - tap to expand, long-press to rename or delete."
        ))
        if (hasCustomGestures(prefs)) {
            add(TourStep(
                "Swipe gestures",
                "You've mapped extra swipe gestures. Swipe up/down/left/right on home to " +
                        "trigger the actions you configured in Settings → Gestures."
            ))
        }
        if (prefs.doubleTapToLock) {
            add(TourStep(
                "Double-tap to lock",
                "Double-tap an empty area of the home screen to lock the device. " +
                        "You enabled this in Settings → General."
            ))
        }
        if (prefs.quickStripEnabled) {
            add(TourStep(
                "Quick toggles strip",
                "The strip at the bottom shows live status (Wi-Fi, battery, clock, …). " +
                        "Tap a toggle to open its settings; manage which widgets appear in " +
                        "Settings → Quick toggles."
            ))
        }
        add(TourStep(
            "Customize more",
            "Fonts, colors, gestures, search bar, security, theme, and backup - all live in " +
                    "Settings. Long-press the home screen → Customize to open them."
        ))
        add(TourStep(
            "You're set",
            "That's everything. You can re-run this tour anytime from Settings → About → " +
                    "Show guided tour."
        ))
    }

    /**
     * Returns true if the user has mapped any swipe gesture to a non-default action. Defaults
     * are: UP=Search, DOWN=OpenNotifications, LEFT/RIGHT=None. Comparing the serialized form
     * avoids relying on referential equality of the sealed-class objects.
     */
    private fun hasCustomGestures(prefs: PreferencesManager): Boolean {
        return Direction.entries.any { dir ->
            val current = prefs.getGestureAction(1, dir).serialize()
            val default = GestureAction.defaultFor(1, dir).serialize()
            current != default
        }
    }

    private fun showStep(
        activity: Activity,
        prefs: PreferencesManager,
        steps: List<TourStep>,
        index: Int
    ) {
        // Persist position BEFORE building the dialog so an interrupt (process death, accidental
        // back) resumes precisely on the step the user is currently looking at.
        prefs.guidedTourStepIndex = index
        dismissActive()

        val step = steps[index]
        val isLast = (index == steps.size - 1)

        val dialog = Dialog(activity, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_guided_tour)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = activity.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        // No outside-tap dismissal - accidental taps shouldn't silently skip the tour. Cancelable
        // is FALSE so a predictive-back gesture (A33+ OnBackInvokedDispatcher) can't silently
        // dismiss the dialog and lose tour state; the BACK keypath below is the only way to
        // navigate backwards.
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }

        // BACK navigates to the previous step rather than dismissing. On step 0 it dismisses
        // (no prior step), behaving as a Skip.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (index > 0) {
                    showStep(activity, prefs, steps, index - 1)
                } else {
                    complete(prefs)
                }
                true
            } else false
        }

        themeAndPopulate(activity, dialog, prefs, step, index, steps.size)

        dialog.findViewById<TextView>(R.id.btnTourSkip).apply {
            setOnClickListener { complete(prefs) }
        }
        dialog.findViewById<TextView>(R.id.btnTourNext).apply {
            text = if (isLast) "Done" else "Next"
            setOnClickListener {
                if (isLast) complete(prefs)
                else showStep(activity, prefs, steps, index + 1)
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    private fun themeAndPopulate(
        activity: Activity,
        dialog: Dialog,
        prefs: PreferencesManager,
        step: TourStep,
        index: Int,
        total: Int
    ) {
        val bg = parseColorSafe(prefs.backgroundColor)
        val isLight = isColorLight(bg)
        val primary = if (isLight) Color.BLACK else Color.WHITE
        val secondary = if (isLight) Color.parseColor("#555555") else Color.parseColor("#999999")
        val accent = if (isLight) Color.parseColor("#333399") else Color.parseColor("#8888FF")
        val density = activity.resources.displayMetrics.density

        // Look up the outer root by id rather than walking parents - robust against XML
        // restructure that would invalidate any `.parent.parent` chain.
        dialog.findViewById<ViewGroup>(R.id.tourRoot)?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bg)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.tourTitle).apply {
            text = step.title
            setTextColor(accent)
        }
        dialog.findViewById<TextView>(R.id.tourCounter).apply {
            text = "${index + 1} / $total"
            setTextColor(secondary)
            contentDescription = "Step ${index + 1} of $total"
        }
        dialog.findViewById<TextView>(R.id.tourBody).apply {
            text = step.body
            setTextColor(primary)
        }
        dialog.findViewById<TextView>(R.id.btnTourSkip).setTextColor(secondary)
        dialog.findViewById<TextView>(R.id.btnTourNext).setTextColor(accent)
    }

    private fun complete(prefs: PreferencesManager) {
        prefs.guidedTourStepIndex = -1
        prefs.guidedTourSeenVersion = CURRENT_TOUR_VERSION
        dismissActive()
    }

    private data class TourStep(val title: String, val body: String)
}
