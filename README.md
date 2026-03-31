# Slate — Minimal Android Launcher

A text-only Android home screen built for focus. No icons, no widgets, no algorithmic feeds competing for your attention — just your apps, listed by name.

---

## Why Slate Exists

Most launchers are designed to keep you on your phone. Colorful icons trigger recognition without thought, notification badges create artificial urgency, and recommendation widgets are optimized for engagement rather than intent.

Slate removes all of that. It presents your apps as plain text — the same way a to-do list presents tasks. You open the app you meant to open, not the one that looked most appealing. Over time this creates a subtle shift: phone use becomes more deliberate and less reflexive.

---

## Features

**Appearance**
- Fully customizable background and text colors with a live color picker
- Per-app color overrides — highlight only what matters
- Typography control: font family (including Google Fonts + import your own), weight, line spacing, word spacing
- Font size scales with app name length — frequently used apps appear larger
- Hide the status bar for a true full-screen experience

**Interaction**
- Swipe up to search apps
- Configurable single, two, and three-finger swipe gestures (open any app, notifications, Wi-Fi, Bluetooth, location, and more)
- Double-tap to lock screen (requires device admin)
- Long-press an app for per-app options (hide, custom color, info)
- Long-press the homescreen to access customization or manage hidden apps
- Sort apps alphabetically or by most recently used

**Control**
- Lock screen rotation to portrait
- Optional persistent search bar on the home screen
- Export and import all settings as a JSON backup
- Onboarding with dark and light preset themes

---

## Privacy

Slate does not collect, transmit, or share any data. There is no analytics, no crash reporting, and no network activity of any kind.

All preferences are stored locally using Android's `SharedPreferences`. App usage counts (for sort-by-usage) never leave the device.

The app requests only the permissions it actively uses:
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` — for the Wi-Fi gesture toggle
- `BLUETOOTH` / `BLUETOOTH_ADMIN` — for the Bluetooth gesture toggle (Android 11 and below only)
- `BIND_DEVICE_ADMIN` — for double-tap screen lock

---

## License

MIT License. See [LICENSE](LICENSE) for details.
