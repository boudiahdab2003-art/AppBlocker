# Changelog

All notable changes to AppBlocker, newest first. Versions map to `versionName` in
`app/build.gradle.kts` and the `vX.Y` git tags / GitHub releases the in-app updater reads.

## v1.12 — Housekeeping & docs
- Disabled cloud/`adb` backup (`allowBackup=false`) so the PIN hash and block rules can't be
  restored onto another device.
- Added this CHANGELOG and a documented release process in the README; refreshed the README to
  match the current feature set.

## v1.11 — Engineering health
- Enabled R8 + resource shrinking for release (APK ~15 MB → ~1.2 MB) with keep-rules for the
  accessibility service, receivers, and Room.
- Added the first unit tests: version comparison, the Strict/Timer clock logic, and the schedule
  time-window midnight wrap.
- Batched the Quick Block save into a single DB transaction.
- Hardened the updater: internal-storage fallback, partial-download cleanup, truncation check, retry.

## v1.10 — UX smoothness
- Animated bottom-nav tab switches and editor overlays; each tab now keeps its state across switches.
- Toast feedback when a Timer/Pomodoro starts; haptic tick on the duration wheel.
- Persist the Insights sub-tab; refresh Profile PIN state on resume; accessibility labels.

## v1.9 — Watcher reliability
- Fixed a location-listener leak (battery/privacy) on service shutdown.
- Refresh the browser list when apps are installed/removed so "block unsupported browsers" can't be
  bypassed by installing a new browser.
- Moved the web-content scan off the main thread to reduce jank while browsing.

## v1.8 — Strict Mode integrity
- Strict Mode / Timer / Pomodoro now anchor to the monotonic clock, so changing the device clock
  can no longer end an "un-stoppable" session early (wall-clock fallback after reboot).
- Replaced destructive DB migration with explicit migrations + schema export, so app updates no
  longer risk wiping all rules/schedules/PIN.

## v1.7 — In-app updater
- Check GitHub for the latest release, download and install it from within the app, with an
  "Update available" banner.

## v1.6 — Fixes
- Template cards no longer clip the time line at large font scales.

## v1.5 — Web filtering scope
- Website/keyword filtering only applies inside browsers; fixed a wrapping navigation label.

## v1.4 — More blocking
- Auto-block newly installed apps; block in-app purchases.

## v1.3 — Strict Mode controls
- Allow strengthening blocks during Strict Mode with a confirmation; wire up "block unsupported
  browsers".

## v1.2 — Strict Mode fixes
- Fixed a Strict Mode trap, template clipping, and the Strict timer format.

## v1.1 — Pomodoro & pickers
- AppBlock-style wheel duration picker; restyled Pomodoro.

## v1.0 — Initial release
- Block apps via an AccessibilityService with an instant block overlay.
- Strict/Focus sessions, daily time limits, launch-count/usage/Wi-Fi/location schedules.
- Website/keyword + adult-content filtering, one-tap category templates.
- PIN lock and Device Admin uninstall protection; signed release build.
