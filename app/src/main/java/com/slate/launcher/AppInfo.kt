package com.slate.launcher

/**
 * One launchable app. [profile] has NO default value on purpose: adding the parameter is meant
 * to break every construction site at compile time, and there is exactly one, so the omission is
 * a feature rather than an inconvenience.
 */
data class AppInfo(
    val name: String,          // custom name, else platform label. NEVER carries the marker.
    val packageName: String,   // bare package, for OS-facing calls only
    val activityName: String,  // was dead; becomes the launch ComponentName class in Stage 2
    val profile: WorkProfile?
) {
    /** Preference-space identity. Identical to [packageName] when [profile] is null. */
    val key: String get() = AppKey.of(packageName, profile?.serial)

    /** Render-space label. Composed here, never stored in [name]. */
    fun displayLabel(): String = if (profile == null) name else "$name (${profile.label})"
}
