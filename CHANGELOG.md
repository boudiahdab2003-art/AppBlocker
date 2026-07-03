# Changelog

All notable changes to AppBlocker, newest first. Versions map to `versionName` in
`app/build.gradle.kts` and the `vX.Y` git tags / GitHub releases the in-app updater reads.

## v1.48
- The coach's answers got a face-lift: section headings in bold, lists with the app's gradient bullet dots, and key numbers and app names highlighted - weekly reports finally look like real reports instead of a wall of text.

## v1.47
- The coach levels up: ask for a proper weekly report (day-by-day breakdown of your week), have him set a specific weekly goal with a detailed plan he tracks, and tap suggestion chips above the message box to chat without typing - starters when you open, his own follow-ups after every reply.

## v1.46
- Chat with your AI Coach: a full conversation screen in Insights - he knows your data, the app's features and your setup, and you can set long-term goals together that he tracks in daily tips. Plus: the 'Update available' popup now shows only once per app open, and Profile has a new 'What's new' page with the full detailed history of every version.

## v1.45
- NEW: AI Coach in Insights - Gemini writes you 2-3 daily tips from your real usage data (paste your free Gemini API key once in the app); plus brighter, easier-to-read row titles across Insights

## v1.44
- Insights page redesigned: gradient hero with key stats, comparison bars on every app list, colored week-over-week trends, glowing cards with section icons, animated charts

## v1.43
- Blocks now apply mid-use (limits/schedules catch you inside the app), disabled location schedules stop draining battery, tablet-friendly New-schedule tiles, more professional Profile page

## v1.42
- Insights loads lighter (reuses cached app icons), less background work while using other apps, and Profile's Prevent-uninstall now switches off properly

## v1.41
- Faster Insights (stats cached per day, refreshes every time you open the tab) and snappier blocking checks

## v1.40
- Profile now shows your name (Abdallah Ahdab) with an avatar and a rename option. YouTube Shorts blocking now starts and stops with Quick Block. You can now choose which of your apps each template blocks (edit pencil on each template card).

## v1.39
- Block YouTube Shorts is now shown as a nested 'Shorts (BETA)' sub-row right under YouTube in the app list (with its own checkbox), instead of a separate Extra-options toggle.

## v1.38
- New option in Quick Block extra options: Block YouTube Shorts. Blocks only the Shorts feed/player in the YouTube app (and youtube.com/shorts in browsers) while the rest of YouTube keeps working.

## v1.37
- Redesigned the Profile page: a gradient header with the app shield, version and a live 'Protection active / Action needed' status; iconed rows with On/Off status badges (PIN, Prevent uninstall); a Share AppBlocker option; and a cleaner layout.

## v1.36
- Insights: new Trend tab with a 30-day chart, 30-day average and this-week-vs-last-week; a Patterns card (weekday vs weekend); a 'Trending this week' list showing how each app changed vs last week; and phone unlocks per day in the Summary.

## v1.35
- Insights: new Summary card with daily average (7 days), busiest day, screen time compared to yesterday (up/down %), and a Light/Moderate/Heavy usage rating for today.

## v1.34
- Insights graph is now interactive: tap or scrub a bar to read its exact value (e.g. '7 PM - 24m'), the busiest bar is highlighted as the peak by default, and the Day/Week charts have clearer time labels (Week now shows the real weekdays).

## v1.33
- Insights: tap any app to see its screen time, opens and block attempts together in a detail panel; the Most opened apps header now shows total app opens today.

## v1.32
- Insights: added a 'Most opened apps' section showing how many times you opened each app today, and the 'Most used apps' rows now show opens alongside screen time.

## v1.31
- Added 'Prevent uninstall (Device admin)' to the Setup and permissions list. Also fixed a bug where the device-admin activation screen self-closed (it was launched as a new task), so Prevent uninstall now actually activates from here, Profile and Strict Mode.

## v1.30
- Blocking a social media app now automatically blocks its website too: while Quick Block is on, a blocked social app's site (e.g. instagram.com when Instagram is blocked) is blocked in browsers. Stays in sync - pausing Quick Block relieves it too.

## v1.29
- Hypothetical apps: now social-media only (removed games, streaming, messaging) and each app shows a brand-coloured initial badge instead of a generic icon.

## v1.28
- Renamed the pre-block popular-apps section to 'Hypothetical apps' (its own list inside Quick Block, hidden/collapsed by default, separate from your installed apps).

## v1.27
- Added Grok to the 'Block before you install' popular-apps list.

## v1.26
- New 'Block before you install' list in Quick Block: pre-block popular apps (TikTok, Instagram, Snapchat, etc.) even if they aren't installed yet - they're blocked the moment you install and open them.

## v1.25
- Usage limit and Launch count are now clean editable fields instead of preset chips: Usage limit has hours + minutes steppers, Launch count has an opens stepper. Type any value or use the minus/plus buttons.

## v1.24
- Location schedules: save a captured spot under a name (e.g. 'UK') and reuse it from a Saved places list, instead of re-capturing every time. Long-press a saved place to delete it.

## v1.23
- Usage limit and Launch count now have an 'Other...' option to enter any custom number (e.g. a 45-minute daily limit or block after 7 opens), not just the presets.

## v1.22
- Fixed Location blocking: it now guides you to grant 'Allow all the time' location (required since blocking runs in the background) and reliably reads your current location, so apps are blocked inside the chosen area.

## v1.21
- Fixed schedule preset buttons (usage limit / launch count) so all options stay fully visible and wrap neatly instead of getting cut off or split across lines.

## v1.20
- The Create schedule button now stays fixed at the bottom of the screen while you scroll the app list.

## v1.19
- Schedule times now read 9:00 AM / 5:00 PM (12-hour), and limits show 30 min / 1 hr / 2 hr and '10 opens' instead of raw numbers.

## v1.18
- Faster app lists, and apps are now ordered by what's most worth blocking (most-distracting + most-used first) instead of alphabetically.

## v1.17
- App and Websites lists in the editors can now be collapsed and expanded by tapping their header.

## v1.16
- The version number now always shows in Profile > About, even after checking for updates.

## v1.15
- Automatic 'Update available' prompt on launch with one-tap Update now; easier install via a permanent download link and QR code.

## v1.14 — Internal cleanup
- Split the 740-line home screen into focused files (Quick Block, schedules, templates, banners).
  Refactor only — no user-facing change.

## v1.13 — Onboarding
- New first-run setup wizard: walks through the essential permissions (accessibility, overlay) one
  step at a time with a progress indicator, then the recommended optional ones.
- Setup is now only marked "done" once you finish or skip the wizard, so quitting mid-setup brings
  it back next launch instead of silently dropping you into a half-configured app.

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
