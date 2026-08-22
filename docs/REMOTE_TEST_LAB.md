# Testing on a phone nobody here owns

`docs/DEVICE_MATRIX.md` names four brands the app claims to support and has never been run on.
This is how a row in that table stops being a guess, using **Samsung Remote Test Lab** — free,
real Galaxy hardware, a Samsung account and no credit card
(<https://developer.samsung.com/remote-test-lab>).

The same sheet works on any brand's cloud device, and on a friend's phone plugged into a cable.

## The constraint, first, because it shapes everything else

A session is **~15–20 minutes on a clock**, on a device that is **wiped afterwards**, out of a
daily credit budget. There is no exploring, no reading code mid-session, no "let me just try".
Every step is a rehearsed subcommand of a driver script (`rtl.sh`, kept outside the repo because
it holds local APK paths):

```
S=<serial> ./rtl.sh  facts | setup | alive | seed | probe | suite | who | watch | blocklog | shot
```

**Everything below was rehearsed against an Android 15 emulator before any device was booked**,
and every trap in it was found during that rehearsal rather than on the clock. That is the point
of rehearsing: the first four attempts at a block test all produced "no block", and not one of
them was the app's fault.

## Booking one, in practice

Measured on the first real session (2026-08-22), because none of this is on the sign-up page:

- **The owner has to sign in himself** (Samsung account). Being signed in on `developer.samsung.com`
  is **not** enough — Remote Test Lab gates separately and says so in a modal that also swallows
  clicks. Go straight to `/remotetestlab/devices` afterwards.
- **20 credits a day; an hour costs 4.** Sessions run up to **two hours**, and unused time is
  refunded if you end early. There is far more time than the "20 minutes" folklore suggests — but
  the device is still wiped afterwards, so the run sheet still applies.
- The viewer opens as a **popup**. Only **one client instance per device**, so an automated session
  needs that popup closed first; closing it ends the session, the device does a ~2 minute "Getting
  ready to restart", and the reservation survives it.
- **ADB is real**: side menu ▸ *Remote Debug Bridge* ▸ Connect, after running Samsung's **RDB**
  client (a 30MB zip containing a Node-packaged `rdb.exe`). ⚠️ **Run it with no arguments** —
  `--help` makes it sit there silently. A healthy start prints `RDB server listening on port: NNNNN`,
  and Connect then puts `localhost:<port>` in `adb devices`.

⚠️ **RTL will not install an app that declares a `DeviceAdminReceiver`** — *"This admin app
installation is not allowed"*, over **ADB as well** as through their installer. This app declares one
for Prevent-uninstall, so a session needs a throwaway build with that `<receiver>` removed from the
manifest, and Prevent-uninstall itself cannot be exercised there.

⚠️ **The device screen dozes**, and then nothing is in the foreground and every block test measures
nothing at all. `settings put system screen_off_timeout 1800000` and `input keyevent KEYCODE_WAKEUP`
before anything else; confirm with `dumpsys power | grep mWakefulness`.

## Before booking the device

1. `git pull`, then build both APKs — the app and the instrumentation:
   `gradle -p . :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest`.
   Debug flavour deliberately: `run-as`, the shell, and the test APK only exist there.
2. Build a **second app APK with `DEBUG = true`** in `BlockerAccessibilityService` and install
   *that* one. Then `git checkout` the flag back to `false` immediately, before it can reach a
   release — it logs every URL visited.
3. **Check the two APKs differ (`md5sum`).** Gradle has handed back a stale APK before, and a
   result from the wrong binary is worse than no result.

## Arming the device — the three traps that make a block test lie

A block test on a freshly flashed device fails for reasons that have nothing to do with blocking.
`rtl.sh seed` does these in this order because **any other order silently disarms the app**:

1. **The after-update pause.** A *re-install* sets `update_paused=true` and **all blocking stops**
   until someone taps "Reactivate blocking" — by design (`UpdatePause`). A *fresh* install does
   not, because the pause only arms when `lastSeenVersionCode != -1`. `pm clear` restores exactly
   that state, so it is the first thing done and the reason a mid-session re-install must be
   followed by another `pm clear`.
2. **`pm clear` prunes the accessibility setting.** The framework removes our component from
   `enabled_accessibility_services` a moment *after* the clear returns, so switching the service
   on before that lands is undone with no error. It is switched on at the **end**, never the start.
3. **`am force-stop` does not keep the process down.** The framework rebinds an enabled
   accessibility service within seconds, and the rebound process **opens the database before the
   seed is written over it**. Room then serves the file it opened while `sqlite3` reads the new
   one — the rows are visibly present and the watcher has none of them. So the service is
   *disabled* first, and the file is replaced only once `pidof` says the process is really gone.

The seed itself is a **pushed database file**, not `INSERT` statements: retail phones have no
`sqlite3` binary. Same APK both ends, so Room's identity hash matches. Delete `-wal`/`-shm` after
replacing the file or they replay over it. And `run-as` cannot read `/sdcard` under scoped
storage — let the *shell* open the file and pipe the bytes into `run-as` on stdin.

## Reading the result — what actually proves a block

- **`mCurrentFocus` never proves anything.** The cover is `FLAG_NOT_FOCUSABLE`, so the blocked app
  stays "focused" while fully covered. This is written down in two other places in this repo and
  it still cost four wrong conclusions during the rehearsal.
- **An app block writes no logcat line.** `DEBUG` logs `blockReason …` *before* the decision, so
  a silent log looks identical to a refusal to block.
- **`rtl.sh blocklog` is the instrument.** It prints the app's own record —
  `time|kind|ownUi|rootOk|why|counted` — which is the same format the owner's bug reports arrive
  in (`data/BlockLog.kt`). A working app block reads `app false match quick true`; a word block
  reads `word false n/a word true`.
- A screenshot is for showing a human what the screen looked like, not for deciding whether
  something happened.
- **Grep both `BLOCK:` and `URL BLOCK`** for web blocks. Different punctuation, different layers;
  filtering for one silently drops the other.

## The run sheet, in priority order

`setup`, `alive`, `seed`, `probe` are cheap and answer most of the table. Then:

| # | What | Settles | Where the fix goes |
|---|---|---|---|
| 0 | `probe` — `DeviceProbeTest` | uninstall screen, keep-alive deep link, `<queries>` visibility | named in the failure message |
| 1 | Blocked site in **the OEM browser** | whether the address bar is really readable — the claim that is wrong in the *silent* direction | `service/ScreenText.kt`, `service/PackageSets.kt` |
| 2 | Strict Mode on → attempt uninstall | whether the guard actually bounces it here | `service/GuardPackages.kt` |
| 3 | `who` on the OEM's accessibility page | the class fast-path, which is AOSP/Xiaomi-shaped | `service/AppInfoScreen.kt` |
| 4 | Tap the keep-alive button | whether it lands on the OEM battery page or falls back silently | `data/DeviceVendor.kt` |
| 5 | OEM overlay panel over a blocked app | that it is not recorded as leaving the app | `BlockerAccessibilityService` overlay packages |
| 6 | Blocked **app** (not site) | cover timing on real hardware | — |
| 7 | Screenshot the diagnostics screen | what the owner would be asked to send | — |

## Other things the rehearsal established

- **`alive` needs an app the watcher actually scans.** A swipe on the launcher produces no line at
  all, by design (launchers are never scanned), which reads as death. A browser is always scanned.
- **`health_last_event_at` is throttled to one write a minute** (`ServiceHealth`), so read-swipe-read
  inside a minute proves nothing. Its **age** is the instant verdict instead: a living service can
  never have a stamp older than ~60s, and a dead one's is frozen where it died.
- **Chrome on a clean emulator sits on `FirstRunActivity`** and never loads a page, so a web-block
  test there measures nothing. Dismiss first-run, or test the word path through another app.
- The **full `suite`** is not reliable on a software-rendered AVD — the app process was killed
  part-way with no FATAL in the log, the same shape `build.yml` documents for a renderer death.
  On a real device run `probe` first; treat a `suite` crash there as a finding, not as noise.

## Check the orientation before you believe a failure

**The first Galaxy session reported five rendering failures. None of them was a bug.** The lab
device was lying on its side, so the tests ran in a 832 x 384dp window instead of 384 x 832dp, and
four assertions that require a phone-portrait viewport could not be met. Setting the emulator to
`wm size 2340x1080` reproduced all three layout failures exactly — same assertions, same line
numbers — and `wm size 1080x2340` made them all pass.

So: **record `dumpsys window | grep mRotation` next to any failure**, and force portrait at the
start of a session. A screenshot whose *content* is rotated while the framebuffer is portrait is
the tell.

The other two failures were real defects, and both were in the tests rather than the app:

- `RepairScreenTest` reached the branch it cared about **by relying on the emulator's accessibility
  service not running** — so it passed because the app was broken on the machine running it, and
  failed on the first phone where blocking actually worked. The branch is now passed in explicitly.
- `SetupAdviceTest` used `performScrollTo` on a card inside a **`LazyColumn`**, which never composes
  what is off screen. It passed only because the gate emulator is tall enough to compose that card
  on open. It now scrolls the list.

Both would have failed on any healthy phone or any shorter phone. Neither needed a Galaxy — but it
took a Galaxy to find them.

**Landscape was then checked properly, and the app is fine in it.** The whole suite runs green at
832 x 384dp. A sixth case (`nameFieldAndSaveSurviveTheKeyboard`) did fail during the investigation,
but only on an emulator where an artificial **tall display cutout** had been switched on for an
earlier experiment — with the cutout off it passes, and with it on the viewport guard skips it
honestly. Nothing was locked to portrait as a result: the app is deliberately built for the owner's
tablet (`Modifier.pageWidth`), and a blanket `screenOrientation` would have pinned that upright to
fix a defect that does not exist.

**What did come out of it: the cover is now measured sideways.** `BlockScreenMatrixTest` had only
ever used portrait shapes, and the cover is the one screen that cannot be pinned — the watcher draws
it over whatever app is in front, so a game or a video in landscape gets a landscape cover. Two
landscape sizes are now in its `screens` list and all four cases pass at them.

⚠️ Note what that does and does not prove. The "no way out" check is **structural**: since v1.118 the
button is pinned as a sibling of the scroller, so it is laid out inside the root at any height — an
absurd 2340x420 still passes. The landscape sizes are exercised (the scroller-height case is
size-dependent and runs at them) and they would catch a regression that puts the button back inside
the scrolling column, which is the regression they exist for. They are not a general "it looks right
sideways" assertion.

## What a green session does not prove

**Whether the phone kills the blocker after a few days.** Samsung ships "Put unused apps to sleep"
switched on, and that is the single likeliest thing to switch blocking off — no twenty-minute
session will ever see it. That needs the app in a real pocket, and the thing that catches it is
the stalled alert, not this sheet.

**A "claim unproven" row is not a passing row.**
