package com.slate.launcher

sealed class GestureAction {
    object None : GestureAction()
    object OpenNotifications : GestureAction()
    object LockScreen : GestureAction()
    object OpenSettings : GestureAction()
    object Search : GestureAction()
    object ToggleWifi : GestureAction()
    object ToggleBluetooth : GestureAction()
    object ToggleLocation : GestureAction()
    object OpenCamera : GestureAction()
    data class OpenApp(val packageName: String) : GestureAction()

    fun serialize(): String = when (this) {
        is None              -> "NONE"
        is OpenNotifications -> "OPEN_NOTIFICATIONS"
        is LockScreen        -> "LOCK_SCREEN"
        is OpenSettings      -> "OPEN_SETTINGS"
        is Search            -> "SEARCH"
        is ToggleWifi        -> "TOGGLE_WIFI"
        is ToggleBluetooth   -> "TOGGLE_BLUETOOTH"
        is ToggleLocation    -> "TOGGLE_LOCATION"
        is OpenCamera        -> "OPEN_CAMERA"
        is OpenApp           -> "app:$packageName"
    }

    companion object {
        /** Actions shown as static menu items (before "Open app…"). */
        val staticActions: List<GestureAction> =
            listOf(None, OpenNotifications, LockScreen, OpenSettings, Search,
                   ToggleWifi, ToggleBluetooth, ToggleLocation, OpenCamera)

        fun deserialize(value: String): GestureAction = when {
            value == "OPEN_NOTIFICATIONS" -> OpenNotifications
            value == "LOCK_SCREEN"        -> LockScreen
            value == "OPEN_SETTINGS"      -> OpenSettings
            value == "SEARCH"             -> Search
            value == "TOGGLE_WIFI"        -> ToggleWifi
            value == "TOGGLE_BLUETOOTH"   -> ToggleBluetooth
            value == "TOGGLE_LOCATION"    -> ToggleLocation
            value == "OPEN_CAMERA"        -> OpenCamera
            value.startsWith("app:")      -> OpenApp(value.removePrefix("app:"))
            else                          -> None
        }

        fun defaultFor(fingers: Int, dir: MultiFingerGestureDetector.Direction): GestureAction =
            when {
                fingers == 1 && dir == MultiFingerGestureDetector.Direction.UP   -> Search
                fingers == 1 && dir == MultiFingerGestureDetector.Direction.DOWN -> OpenNotifications
                else -> None
            }
    }
}

/** Human-readable label for static actions. App names are resolved at the call site. */
val GestureAction.staticLabel: String
    get() = when (this) {
        is GestureAction.None              -> "None"
        is GestureAction.OpenNotifications -> "Open notifications"
        is GestureAction.LockScreen        -> "Lock screen"
        is GestureAction.OpenSettings      -> "Open settings"
        is GestureAction.Search            -> "Search apps"
        is GestureAction.ToggleWifi        -> "Toggle Wi-Fi"
        is GestureAction.ToggleBluetooth   -> "Toggle Bluetooth"
        is GestureAction.ToggleLocation    -> "Toggle location"
        is GestureAction.OpenCamera        -> "Open camera"
        is GestureAction.OpenApp           -> packageName   // resolved to app name in UI
    }
