package com.slate.launcher

/**
 * One renderable cell in the home-screen list. The renderer dispatches on this type — apps and
 * folders look subtly different (folders gain a trailing chevron, optional custom colour), and
 * [BackOut] is the leading "‹ back" affordance shown only while inside an expanded folder.
 */
sealed class HomeItem {
    data class AppItem(val info: AppInfo) : HomeItem()
    /**
     * @param visibleCount how many of the folder's packages are currently visible (i.e., not
     * hidden, not uninstalled). Surfaced by the `count` folder-style marker `Work (n)` so the
     * number matches what the user sees on expand; ignored by the other styles.
     */
    data class FolderItem(val folder: Folder, val visibleCount: Int) : HomeItem()
    data object BackOut : HomeItem()
}
