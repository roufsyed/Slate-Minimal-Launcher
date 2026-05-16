package com.slate.launcher

/**
 * One renderable cell in the home-screen list. The renderer dispatches on this type — apps and
 * folders look subtly different (folders gain a trailing chevron, optional custom colour), and
 * [BackOut] is the leading "‹ back" affordance shown only while inside an expanded folder.
 */
sealed class HomeItem {
    data class AppItem(val info: AppInfo) : HomeItem()
    data class FolderItem(val folder: Folder) : HomeItem()
    data object BackOut : HomeItem()
}
