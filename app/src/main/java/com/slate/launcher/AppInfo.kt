package com.slate.launcher

/**
 * One launchable app. [profile] has NO default value on purpose: adding the parameter is meant
 * to break every construction site at compile time, and there are exactly two, so the omission
 * is a feature rather than an inconvenience.
 */
data class AppInfo(
    val name: String,          // custom name, else platform label. NEVER carries the marker.
    val packageName: String,   // bare package, for OS-facing calls only
    val activityName: String,  // was dead; becomes the launch ComponentName class in Stage 2
    val profile: WorkProfile?
) {
    /** Preference-space identity. Identical to [packageName] when [profile] is null. */
    val key: String get() = AppKey.of(packageName, profile?.serial)

    /**
     * Render-space label. Composed by [WorkMarker], never stored in [name].
     *
     * [style] is one of the `WORK_MARKER_*` constants and has NO default value, for the same
     * reason [profile] has none: a new call site must be forced to decide, because silently
     * defaulting would render the out-of-the-box marker instead of the user's choice.
     *
     * Invariant, for all eight styles: `displayLabel(style).startsWith(name)`. The marker is
     * always a suffix, which is what keeps configureFastScroll (reads the model's first char)
     * and scrollToLetter (reads the rendered row's first char) in lockstep.
     */
    fun displayLabel(style: String): String =
        if (profile == null) name else WorkMarker.decorate(name, profile.label, style)
}
