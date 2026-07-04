# AppBlocker

A personal Android app & website blocker that helps you stay off distracting apps. Built from
scratch in Kotlin + Jetpack Compose (Material 3), no root required.

## Install it on your phone
Open this link on the phone (or scan the QR code), then tap the downloaded file to install:

**https://github.com/boudiahdab2003-art/AppBlocker/releases/latest/download/AppBlocker.apk**

This link always points to the newest version, so it never goes out of date.

![Scan to install](qr-install.png)

The first time, Android will ask to "allow installing unknown apps" — say yes. After that, the app
keeps itself up to date: when a new version exists, opening AppBlocker shows an **Update available**
prompt — tap **Update now**.

## What it does
- **Block apps** — pick any installed apps; they're covered the moment you open them by an instant
  full-screen block overlay.
- **Quick Block** — a one-tap on/off set of blocked apps, with optional **Timer** (block for N
  minutes) and **Pomodoro** (block during work, free during breaks) sessions.
- **Strict Mode** — an *un-stoppable* focus session: blocks stay locked until the timer ends, and it
  can't be ended early — not even by changing the device clock (anchored to the monotonic clock).
- **Schedules** — block chosen apps by **time** window, **daily usage limit**, **launch count**,
  **Wi-Fi** network, or **location**.
- **Website & keyword filtering** — block adult sites and your own keywords inside browsers; block
  "unsupported" browsers that can't be filtered.
- **Templates** — one tap to block a whole category.
- **Insights** — daily/weekly screen-time and most-used apps (via UsageStats).
- **Anti-bypass** — protect settings with a PIN, and enable Device Admin so the app resists uninstall.
- **In-app updates** — checks GitHub for the latest release and installs it from within the app.

## How it works (no root needed)
A background **AccessibilityService** (`BlockerAccessibilityService`) is notified whenever the
foreground app or on-screen text changes. If the app is blocked (always, over a limit, on a matching
schedule, or during Strict Mode) it draws a full-screen **block overlay** over it (using the *draw
over other apps* permission). Rules live in a local **Room** database; daily usage comes from
Android's **UsageStatsManager**.

## Privacy
All blocking rules, statistics, goals and coach data live **only on your device** — no accounts, no
analytics, no ads, no servers of ours. Blocking and web-filter checks happen entirely on-device. The
**optional AI Coach** (enabled only if you paste your own free Gemini API key) sends aggregate usage
statistics, your goals/profile facts and your chat messages to Google's Gemini API to generate tips
and replies; remove the key to stop all AI traffic. The launch-time update check contacts GitHub with
no personal data. Full details: **[Privacy Policy](https://boudiahdab2003-art.github.io/AppBlocker/privacy-policy)**.

## Project layout
```
app/src/main/java/com/appblocker/
├── MainActivity.kt          # hosts the Compose UI behind the PIN LockGate
├── data/                    # Room (AppRule, FocusState, Schedule, BlockedKeyword, DAOs),
│                            #   SessionClock, QuickSession, stores, Updater
├── service/                 # BlockerAccessibilityService (the watcher), WebContentFilter,
│                            #   UsageTracker, PackageInstallReceiver, TimeWindow
├── admin/                   # AppBlockerAdminReceiver (uninstall protection)
└── ui/                      # Compose screens: Blocking, Strict, Insights, Profile, editors
app/src/test/                # JVM unit tests (version compare, SessionClock, time-window)
app/schemas/                 # exported Room schemas (for migrations)
```

## Permissions (granted once; in-app prompts guide you)
| Permission | Why |
|-----------|-----|
| Accessibility | See which app/page is open, to block it (required) |
| Display over other apps | Show the block overlay (required) |
| Usage access | Daily limits & Insights (optional) |
| Ignore battery optimization | Keep the blocker alive in the background (optional) |
| Location | Only for Wi-Fi/Location schedules (optional) |

## Build
Requires the Android SDK (compileSdk 34) and JDK 17. There is **no Gradle wrapper checked in** —
build from Android Studio, or with a local Gradle 8.9 using the Android Studio JBR as `JAVA_HOME`:
```
gradle :app:assembleDebug       # debug APK  -> app/build/outputs/apk/debug/
gradle :app:testDebugUnitTest   # run unit tests
gradle :app:assembleRelease     # signed, R8-minified release (needs keystore.properties)
```
Install to a device/emulator: `adb install -r app-debug.apk`. The release build is R8-minified and
resource-shrunk (~1.2 MB).

## Release process
**Double-click `Publish.bat`** and type a short note about what changed. That's it — it bumps the
version, builds the signed release, saves it to GitHub, and publishes the new version. Everyone on the
permanent install link (and every phone running the app) gets the update automatically.

<details>
<summary>What Publish.bat does under the hood</summary>

1. Bumps `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Builds the signed release with `gradle :app:assembleRelease` (uses `keystore.properties`).
3. Verifies the APK is signed with the AppBlocker release key (so in-place updates don't break).
4. Adds a `CHANGELOG.md` entry, commits, tags `vX.Y`, and pushes.
5. Creates the GitHub release and uploads the APK as a constant-named asset `AppBlocker.apk`, which
   powers the permanent download link and the in-app `Updater`.
</details>

## Tech
Kotlin · Jetpack Compose (Material 3) · Room · AccessibilityService · UsageStatsManager · DeviceAdmin
· R8 · AGP 8.5 / Gradle 8.9 / Kotlin 1.9.24.

See `CHANGELOG.md` for version history.
