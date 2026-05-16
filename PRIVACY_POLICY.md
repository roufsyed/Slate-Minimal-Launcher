# Privacy Policy — Slate Launcher

**Effective date:** 2026-04-01

## What data Slate collects

Slate does not collect, transmit, or share any personal data. All information stays on your device.

The following data is stored locally in the app's private storage:

| Data | Purpose |
|------|---------|
| App launch counts | Sort apps by usage frequency |
| Hidden app list | Hide apps from the launcher |
| Pinned app list | Keep selected apps at the top of the list |
| Per-app text colors | Custom color assigned by the user |
| Per-app custom names | Renamed app labels chosen by the user |
| Gesture assignments | User-configured swipe actions |
| Visual preferences (font, colors, spacing) | Appearance customization |

## Lockscreen wallpaper

When the "Apply to lockscreen" toggle is enabled in Settings, Slate writes a solid-color image to the lockscreen wallpaper layer using the `SET_WALLPAPER` permission. This action only occurs when the toggle is turned on or when the background color is changed while the toggle is active. No image data is read from the device; a plain color bitmap is generated in memory and immediately discarded after being applied.

## Notification access

Slate requests permission to read active notifications solely to highlight app labels when a notification is pending. Notification content (title, text, sender) is never read, stored, or transmitted — only the package name of the app that posted the notification is used.

## Accessibility service

Slate requests accessibility service permission solely to perform the screen lock action when the user assigns it to a double-tap or swipe gesture. The service does not observe, record, or transmit any on-screen content or user interactions.

## Hidden apps security

When the "Lock hidden apps" toggle in Settings → Security is enabled, Slate stores a one-way verifier of your PIN — a salted PBKDF2-HMAC-SHA256 hash with 120,000 iterations and a per-device 16-byte random salt — in the app's private SharedPreferences. The plain-text PIN is never written to disk and is zeroed in memory immediately after hashing.

If you opt into biometric unlock, Slate calls Android's standard `BiometricPrompt` (BIOMETRIC_STRONG class). Biometric templates are stored and matched entirely inside the Android OS / secure hardware; Slate only receives a success or failure callback and has no access to fingerprint or face data.

After 5 wrong PIN attempts the app is locked out for 30 seconds; 10 wrong for 5 minutes; 15 wrong for 15 minutes. Lockout state is local and never reported anywhere. There is no remote PIN recovery — clearing app data is the only reset.

JSON backups include the PIN hash, salt, and iteration count so a restored backup keeps working. The hash is a verifier, not the PIN itself, but you should still keep backup files in trusted storage.

## Battery optimization

Slate checks whether the app is exempt from battery optimization using `PowerManager.isIgnoringBatteryOptimizations` and, on Android 9+, `ActivityManager.isBackgroundRestricted`. This check reads a device state only — no data is collected, stored, or transmitted. If a restriction is detected and a background-dependent feature (notification highlight or double-tap to lock) is enabled, a warning banner is shown in Settings.

Tapping "Fix this" on the banner launches a standard system dialog (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) that lets you exempt Slate from battery optimization. The outcome of that dialog stays on your device and is not reported to Slate.

## No third-party sharing

Slate has no analytics, no crash reporting, no advertising SDKs, and no network communication of any kind.

## Contact

For questions about this policy, open an issue at the project repository.
