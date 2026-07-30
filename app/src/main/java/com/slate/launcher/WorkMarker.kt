package com.slate.launcher

object WorkMarker {

    private const val NBSP = '\u00A0'

    /**
     * Total: every input triple returns a string, no branch throws, and no branch returns
     * empty unless [name] was already empty.
     *
     * [profileLabel] has exactly one producer, WorkProfiles.profiles(), which emits
     * `if (index == 0) "Work" else "Work ${index + 1}"` - a compile-time English literal plus a
     * decimal ordinal. Never localised, never OS-supplied (the platform's own badged label is
     * deliberately not used), and never user-editable, since renaming the auto-created folder
     * does not touch it (see WorkGrouping.nameFor). So it is always "Work", or "Work " and an
     * integer >= 2.
     */
    fun decorate(name: String, profileLabel: String, style: String): String {
        val label = profileLabel.trim()
        return when (style) {
            PreferencesManager.WORK_MARKER_NONE    -> name
            PreferencesManager.WORK_MARKER_DAGGER  -> "$name${NBSP}\u2020"
            PreferencesManager.WORK_MARKER_STAR    -> "$name${NBSP}*"
            PreferencesManager.WORK_MARKER_DOT     -> "$name${NBSP}\u2022"
            PreferencesManager.WORK_MARKER_SQUARE  -> "$name${NBSP}\u25A3"
            PreferencesManager.WORK_MARKER_DIAMOND -> "$name${NBSP}\u25C6"
            PreferencesManager.WORK_MARKER_WORD    ->
                if (label.isEmpty()) name else "$name${NBSP}($label)"
            // WORK_MARKER_BRACKETS and every unrecognised stored value land here. Brackets is
            // the default, so the default and the unknown-value fallback share one code path -
            // exactly how `chevron` is folderLabel's unlisted `else`. This is also what retires a
            // removed style safely: a pref still holding "letter" renders as brackets.
            else -> if (label.isEmpty()) name else "$name${NBSP}[$label]"
        }
    }
}