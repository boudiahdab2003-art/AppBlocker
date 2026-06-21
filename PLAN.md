# AppBlocker — Android App Blocker Project Plan

## Context
You asked me to remember "AppBlocker" as a project and lay out a plan to build it. From now on, when you say **"appblocker"**, I'll load this project's context and pick up where we left off (I'll save a `appblocker_project.md` memory file once we leave plan mode — I can't write memory while planning).

**Decisions you gave me:**
- **Platform:** Android only
- **Behaviors:** ALL of — (1) hard-block chosen apps, (2) schedule/focus times, (3) daily time limits, (4) hard to bypass
- **Build style:** Real native app, built together step-by-step
- **Your level:** Non-technical → every step will be spelled out, tooling kept as simple as possible

**Goal:** A working Android app that runs on YOUR phone (sideloaded, no Play Store needed) and actually prevents distracting apps from being used according to rules you set.

---

## How an Android app blocker works (plain English)
Android won't let one app directly "kill" another. The standard, reliable trick that real blockers use:

1. **A watcher** runs in the background using Android's **AccessibilityService** — it gets notified every time you open a different app, and sees which app came to the front.
2. When the app in front is one you've blocked, the watcher **covers the screen** with a full-screen "Blocked" overlay (using the *draw over other apps* permission) and/or **sends you back to the home screen**.
3. A small **settings app** (the part you tap to open) lets you choose which apps to block, set schedules, set time limits, and set a PIN.
4. Rules + usage counts are saved in a local database so they survive restarts.

This is all standard Android — no root, no hacks. The cost is that the user (you) must grant a couple of "powerful" permissions once during setup.

---

## Tech stack (beginner-friendly, all free)
- **Android Studio** (the official tool to build Android apps) on your Windows PC
- **Kotlin** — the modern Android language (cleaner/shorter than Java)
- **Jetpack Compose** — build screens with simple code instead of drag-and-drop XML
- **Room** — tiny built-in database to store blocked apps / schedules / limits
- **WorkManager** — runs the "reset daily limits at midnight" job
- Test on a **USB-connected phone** (your real Android) or the built-in **emulator**

**Project location:** `C:\Users\smh_7\Desktop\AppBlocker`

---

## The pieces we'll build (architecture)
| Component | Job |
|-----------|-----|
| `BlockerAccessibilityService` | The watcher. Detects foreground app, decides block/allow, triggers the block screen. |
| `BlockScreenActivity` / overlay | Full-screen "This app is blocked" page shown over the blocked app. |
| `AppPickerScreen` | Lists installed apps (via `PackageManager`) so you tick which to block. |
| `RulesScreen` | Set per-app mode: hard block / schedule / daily limit. |
| `ScheduleEngine` | Checks current time vs your blocking windows + Pomodoro focus sessions. |
| `UsageTracker` | Counts minutes used per app (via `UsageStatsManager`); enforces daily limits. |
| `PinLock` + Device Admin | PIN to change settings; makes the app hard to uninstall/disable. |
| `BlockerDatabase` (Room) | Stores blocked apps, schedules, limits, PIN, daily counters. |
| `SetupWizard` | First-run screen that walks you through granting the 2 permissions. |

**Permissions we'll request (one-time setup):**
- Accessibility access (the watcher) — required
- "Draw over other apps" / `SYSTEM_ALERT_WINDOW` (the block screen) — required
- Usage Access / `PACKAGE_USAGE_STATS` (for daily limits) — required for feature 3
- Device Admin (optional, for the "hard to uninstall" part)

---

## Feature → implementation map
1. **Hard block** — AccessibilityService sees blocked package in front → show block screen + go home.
2. **Schedule / focus** — `ScheduleEngine` marks an app blocked only inside its time windows; a **Focus button** starts a Pomodoro timer that blocks everything on your list for N minutes.
3. **Daily limits** — `UsageTracker` adds up minutes; once an app passes its limit, it joins the blocked set until midnight reset (WorkManager).
4. **Hard to bypass** — PIN required to edit rules or stop a focus session; Device Admin so the app can't be uninstalled without the PIN; a warning if accessibility gets turned off.

---

## Build roadmap (milestones — we do these in order, one session each)
- **M0 – Setup:** Install Android Studio + JDK, enable Developer Mode/USB debugging on your phone, create the empty `AppBlocker` project, run a "Hello" screen on your phone to confirm the toolchain works.
- **M1 – App list:** Screen that shows all installed apps and lets you tick ones to block; save the list to Room.
- **M2 – The watcher + hard block:** Build `BlockerAccessibilityService` and the block screen. Ticked apps get blocked. (This is the core — once this works, you have a real blocker.)
- **M3 – Schedule & Focus mode:** Time windows + Pomodoro focus button.
- **M4 – Daily time limits:** Usage tracking + midnight reset.
- **M5 – Anti-bypass:** PIN lock + Device Admin + tamper warnings.
- **M6 – Polish:** App icon, nicer block screen, build a shareable signed APK you can reinstall anytime.

Each milestone ends with the app installed on your phone and working before we move on.

---

## Verification (how we'll know each step works)
- Run on your real phone over USB after every milestone; I'll tell you exactly what to tap.
- **M2 test:** add (say) Instagram to the block list → open Instagram → block screen should appear within ~1 second.
- **M3 test:** set a 1-minute focus session → confirm everything on the list is blocked, then auto-unblocks.
- **M4 test:** set a 2-minute daily limit on an app → use it 2 min → it blocks; confirm it frees up after the midnight reset job (we can trigger the job manually to test).
- **M5 test:** try to change a rule → PIN prompt; try to uninstall → blocked by Device Admin.

---

## Open questions for later (not blockers now)
- Do you want a **dark, minimal** look or a **friendly/colorful** one for the block screen?
- Should focus sessions be **un-stoppable once started** (max strictness) or **PIN-stoppable**?
- Any specific apps you already know you want to block first (for M2 testing)?

We can answer these when we reach the relevant milestone.

## First action after approval
1. Save the `appblocker_project.md` memory + index entry so "appblocker" always reloads this.
2. Start **M0**: I'll give you the exact Android Studio download + setup steps for Windows.
