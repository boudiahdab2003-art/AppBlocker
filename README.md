# AppBlocker

A personal Android app blocker that helps you stay off distracting apps. Built from scratch in Kotlin + Jetpack Compose.

## What it does
- **Block apps** — pick any installed apps; they're stopped the moment you open them, replaced by a calm "🌿 Blocked" screen.
- **Focus mode** — start an *un-stoppable* focus session (15/25/50/90 min). Your blocked apps stay locked until the timer ends — there is no early stop.
- **Daily time limits** — allow an app for, say, 30 minutes a day, then it blocks automatically. Resets every midnight.
- **Anti-bypass** — protect your settings with a PIN, and turn on Device Admin so the app can't be uninstalled on a whim.

## How it works (no root needed)
A background **AccessibilityService** (`BlockerAccessibilityService`) is notified whenever the foreground app changes. If that app is blocked (always, over its daily limit, or during a focus session), it launches a full-screen **block screen** over it (using the *draw over other apps* permission). Rules live in a local **Room** database; daily usage is read from Android's **UsageStats**.

## Project layout
```
app/src/main/java/com/appblocker/
├── MainActivity.kt              # hosts Compose UI behind the PIN LockGate
├── data/                        # Room: AppRule, FocusState, DAOs, PinStore
├── service/
│   ├── BlockerAccessibilityService.kt   # the watcher (core)
│   └── UsageTracker.kt          # per-app daily usage via UsageStats
├── admin/AppBlockerAdminReceiver.kt     # device admin (uninstall protection)
└── ui/                          # Compose screens: AppPicker, Focus, Settings, BlockScreen, PinScreen
```

## Permissions (granted once, in-app prompts guide you)
| Permission | Why |
|-----------|-----|
| Accessibility | See which app is open, to block it (required) |
| Draw over other apps | Show the block screen |
| Usage access | Measure daily app usage (only for daily limits) |
| Device admin | Resist uninstall (optional) |

## Build
Requires Android Studio / the Android SDK (compileSdk 34, JDK 17+).
```
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease    # signed release APK (needs keystore.properties)
```
Install to a connected device/emulator: `adb install -r app-debug.apk`.

## Tech
Kotlin · Jetpack Compose (Material 3) · Room · AccessibilityService · UsageStatsManager · DeviceAdmin · AGP 8.5 / Gradle 8.9.

*Built milestone by milestone — see the git history (M0–M6).*
