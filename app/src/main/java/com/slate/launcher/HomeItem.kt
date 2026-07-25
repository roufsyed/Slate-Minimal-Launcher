package com.slate.launcher

import android.net.Uri
import com.slate.launcher.shortcuts.PinnedShortcut

/**
 * One renderable cell in the home-screen list. The renderer dispatches on this type - apps and
 * folders look subtly different (folders gain a trailing chevron, optional custom colour), and
 * [BackOut] is the leading "‹ back" affordance shown only while inside an expanded folder.
 * Contact results only ever appear in the live search list (never on the static home list) and
 * only when the user has opted into Search → Search contacts.
 */
sealed class HomeItem {
    data class AppItem(val info: AppInfo) : HomeItem()
    /**
     * @param visibleCount how many of the folder's packages are currently visible (i.e., not
     * hidden, not uninstalled). Surfaced by the `count` folder-style marker `Work (n)` so the
     * number matches what the user sees on expand; ignored by the other styles.
     */
    data class FolderItem(val folder: Folder, val visibleCount: Int) : HomeItem()
    /**
     * A contact match shown inline alongside app matches when the user has opted into contact
     * search. One instance per phone-number row, so a contact with multiple numbers shows
     * multiple list items - and those rows are disambiguated via [typeLabel].
     *
     * The phone number is deliberately NOT a render input: the row shows the contact's name
     * (optionally suffixed with the number type for multi-number contacts) and the dialer
     * pre-populates with [number] on tap. The contact's source account (Google, WhatsApp,
     * SIM, etc.) is also intentionally NOT exposed - every contact row reads the same shape
     * for visual consistency. Source-level filtering still happens in the query path via
     * `prefs.googleContactsOnly`, but it's silent on the surface.
     *
     * @param displayName the contact's display name (DISPLAY_NAME_PRIMARY).
     * @param number the raw phone number - used to construct the `tel:` Uri for
     *     [Intent.ACTION_DIAL] when the row is tapped. Never displayed.
     * @param typeLabel localised type label ("mobile" / "work" / "home" / etc.), populated
     *     only when the contact has more than one phone number - that's the case where we
     *     need to disambiguate the row visually. `null` for single-number contacts so the
     *     row renders as just the bare name.
     * @param lookupUri stable contact URI; reserved for future "view contact" affordances.
     *     Not used at tap time today - the cached [number] is always sufficient for dialing.
     */
    data class ContactItem(
        val displayName: String,
        val number: String,
        val typeLabel: String?,
        val lookupUri: Uri
    ) : HomeItem()
    /**
     * A pinned reference to another app's published shortcut, rendered as a permanent row in
     * the application list (as opposed to the widget-strip destination, which renders via
     * [com.slate.launcher.widgets.ShortcutQuickWidget] instead). See [PinnedShortcut] for the
     * persisted identity/label/destination model.
     */
    data class ShortcutItem(val shortcut: PinnedShortcut) : HomeItem()
    data object BackOut : HomeItem()
}
