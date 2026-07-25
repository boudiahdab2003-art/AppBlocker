# Blocking invariants, and how to hunt for bugs in them

The blocking watcher (`service/BlockerAccessibilityService.kt`) is where essentially every
real bug in this app has lived. On 25 Jul 2026 nine were fixed across v1.95–v1.98; six were
reported by the owner hitting them, three were found by auditing. This file exists so a future
session starts from what was learned instead of re-deriving it.

## Why bugs concentrate here

- The watcher has **no test coverage and currently can't have any**. Every test in
  `app/src/test/` covers pure logic (`BlockDecision`, `CoverGate`, `SessionClock`, `TimeWindow`,
  `WebContentFilter`, …). The service's ~1500-line state machine has none, and
  `unitTests.isReturnDefaultValues = true` means Android calls return dummies, so it cannot be
  tested as written.
- **Cloud sessions can't run the app.** The owner's phone is the only real oracle. That is why
  the audit method below matters: it is the only way to find bugs *before* he hits them.
- **Under-blocking is invisible.** The owner notices over-blocking instantly (a block screen
  where it shouldn't be). He will *not* notice a block screen that failed to appear. All three
  audit findings were under-blocking. Assume the reported-bug list is biased and incomplete.

## The recurring bug shape

Every one of the nine reduced to one of two mistakes:

1. **Two sources of truth that drift.** A flag tracking something the system already knows.
   - `shortsCovering` duplicated what the overlay knew → 3 bugs (v1.98).
   - `BlockOverlay.isAppBlock` not cleared on `remove()` → stale (v1.96).
2. **Assuming you know what is on screen when you don't.** Acting on a package without
   confirming, or conflating two things that look alike.
   - window-state events trusted as proof of foreground → flashing (v1.97).
   - our own *cover* vs our own *UI* — same package, opposite meanings (v1.96, `OwnUi`).
   - "can't tell" treated as "still there" → covers parked on the home screen (v1.96).
   - a transient surface (shade/IME) treated as a real app switch (v1.98).

When auditing, look for these two shapes rather than for symptoms.

## Invariants the code now depends on

Break one of these and blocking misbehaves. They are not all enforced by tests.

1. Never raise a cover for a package the window tree positively contradicts. Unreadable tree
   counts as "yes, block" — an app must not escape by hiding its tree (`stillOnScreen`).
2. Our own cover and our own activity are the same package but opposite meanings. Only `OwnUi`
   distinguishes them.
3. A transient surface — System UI, system dialogs, the current keyboard — appears *over* an
   app; it is not a foreground change (`isTransientSurface`). Settings and the dialer are real
   destinations and must still take a cover down.
4. "Can't tell whether the user left" ≠ "the user is still there" (`ExitView.BLIND`).
5. One open = one recorded attempt, however many times the cover is redrawn (`CoverGate`).
   Do **not** lengthen `COUNT_COOLDOWN_MS` to hide a re-raise — that hides a cause.
6. In Allowlist mode the essentials (launcher, keyboard, dialer, System UI, Settings,
   ourselves) are never blocked by *any* layer, including a keyword lockout.
7. Home/the launcher is always honoured — the user must never be trapped under a cover.
8. Derive state from the overlay where possible; don't mirror it in a flag.

## Device quirks these invariants exist for

- Gesture-nav Home on HyperOS often emits **no accessibility event at all**, so the foreground
  cache silently goes stale. Most of the nine trace back to this.
- `rootInActiveWindow` can report **our own non-focusable cover** as the active window.
- The notification shade, volume dialog and heads-up notifications genuinely become the active
  window while the user has not left the app.

## The audit method (this is what "bug hunt" means)

Do not read the file narratively. Enumerate call sites by *kind*, then check each against the
invariants above. What worked:

```
grep -n "showBlockScreen("      # every cover RAISE
grep -n "overlay.remove()"      # every cover REMOVAL
grep -n "lastForegroundPkg = "  # every foreground ADOPTION
grep -n "recordOpen"            # open counting
grep -n "AttemptCounter.record" # attempt counting
```

For each site ask: *what does this assume about what's on screen, and who owns the thing it is
changing?* The removals are where the bugs were — a raise that is wrong is visible, a removal
that is wrong is not.

### Swept so far (25 Jul 2026)

- cover raises (6 sites) — clean
- cover removals (12 sites) — **3 bugs**, all one cause, fixed in v1.98
- foreground-cache writes (3 sites) — clean
- open counting, attempt counting — clean

### Swept in the first "bug hunt" (25 Jul 2026, merged as `a9282c9`, not yet released)

- keyword-lockout state — **1 bug**: a bare wall-clock deadline, so winding the device clock
  forward lifted the lockout. Now anchored via `SessionClock` in `data/GuardedDeadline.kt` (named
  `KeywordLockout.kt` until the fourth sweep found the second instance), which also folds the
  triggering word in (one less parallel map).
- session / timer bookkeeping — clean. One suspected `SessionClock.elapsed` weakness was a
  **misdiagnosis**: capping the post-reboot wall path changes nothing (`elapsed >= total` fires
  either way) and `remaining` is equally exposed, since a forward jump zeroes `wallEnd - nowWall`.
  Post-reboot the wall clock is the only information there is. **Do not "fix" this again.**
- the counting stores — **1 bug, wider than it looked**: day-stamps (`yyyy*1000 + dayOfYear`) were
  being *subtracted* in five stores. Fine for equality, meaningless across a year boundary, so
  pruning wiped everything through January and `MoodStore` read history from stamps that never
  existed. `dayGap`/`stampDaysAgo` added. Blocking unaffected — daily open limits use equality.
- service lifecycle / rule flow — **1 bug, the worst found so far**: the
  `combine(rules, focus, keywords, schedules).launchIn(scope)` flow had no retry. One throw ended
  it permanently; the safety net logged it and blocking carried on with stale rules forever, while
  the watchdog reported healthy because it only checks that events arrive. Now `retryWhen` with
  backoff.

### Swept in the second "bug hunt" (25 Jul 2026)

- `ProtectionWatchdog` / `ServiceHealth` — **2 bugs.**
  - The swallowed errors were **written and never read by anything**. `recordError`'s own comment
    promised "a recurring bug is visible rather than invisible", and no screen showed it — the
    quiet failure it warned about. This is precisely the gap that let the rule-flow bug hide.
    The Profile screen now shows a row whenever the count is above zero, with the last message and
    a tap to clear, so a problem becomes reportable ("it says 12 errors").
  - `checkAndNotify` was **unguarded**, and runs from the app's own resume effect as well as the
    boot receiver and worker — so a throw from posting a notification (OEM notification managers
    do) would crash the app on open. The thing that warns you blocking has stopped must not be
    able to take the app down. Now `guarded`, and the usage-stats read inside `state()` treats
    failure as "can't tell", which already means never a false STALLED.
- schedule evaluation — **1 bug.** A Wi-Fi schedule naming a specific network can *never* match
  without location access: Android returns `<unknown ssid>` instead of the name, which silently
  compares unequal. The editor said so only in grey small print and never checked whether the
  permission was actually granted; it now warns properly and offers the grant. Enforcement
  recognises the placeholder explicitly — there is nothing better to do there, since blocking on
  an unreadable name would block on *every* Wi-Fi.
- `timeWindowContains` / `scheduleWindowContains` — clean, including the v1.93 overnight
  previous-day fix and zero-length windows. `USAGE_LIMIT` / `LAUNCH_COUNT` thresholds — clean.
  `ProtectionState` thresholds and `ProtectionScheduler` — clean.

### Swept in the third "bug hunt" (25 Jul 2026)

- `UpdatePause` and the protection-status reporting — **1 bug, and the worst-consequence one
  found.** Every update switches *all* blocking off until the user taps Reactivate (deliberate —
  see `UpdatePause`), and the only thing that said so was a banner on the Blocking tab. The
  watchdog reported OK, the Profile hero pill said "Protection active", and no notification
  fired. So after every release the app blocked nothing **and actively insisted it was healthy**,
  until the owner happened to open one particular tab — a state reached four times on the day it
  was found. `ProtectionState` gained `PAUSED` (ranked below `OFF`, above `STALLED`), the watchdog
  notifies via `ProtectionNotifier.notifyPaused`, and the pill carries the real state.
  - Same shape as the second sweep's two findings, found by the same question. It is not that the
    pause was wrong; it is that **the pause was invisible to every mechanism meant to report it.**
  - A bug this change would otherwise have *introduced*, worth remembering: every notification
    built its `PendingIntent` with `requestCode = 0`, and **PendingIntent matching ignores
    extras** — so with `FLAG_UPDATE_CURRENT` the new alert would have silently rewritten where the
    other alerts' taps went. Each now passes its own notification id.

### Swept in the fourth "bug hunt" (25 Jul 2026)

- the adult-word pack's 24-hour off delay — **1 bug, the same one as the first sweep's, in the
  app's most deliberately hardened protection.** `adultPackOffRequestedAt` was a bare epoch-millis
  stamp compared against `System.currentTimeMillis()`, so winding the clock forward a day skipped
  the whole cooling-off the v1.84 changelog promises ("the pack keeps protecting you for another
  24 hours"). The strongest protection was the cheapest to switch off.
- **Because the same mistake had now been made twice, the fix generalised rather than
  duplicated** — the standing guidance below, applied. `KeywordLockout` became
  `data/GuardedDeadline.kt`: `word` → `note`, plus a `starting(durationMs, boot, note)` factory
  and an `extraMs` parameter, so the gate derives both of its moments ("the 24h wait is over" and
  "the follow-up window has lapsed too") from one record instead of two comparisons. Anything else
  needing a deadline the user shouldn't be able to skip belongs there, not in a third copy.
- Why it was missed twice: both instances were **local re-implementations of an idea that already
  had a hardened home** (`SessionClock`). Neither was near the code that got fixed, and neither
  used its vocabulary — so grepping for the concept found nothing. The generalisable move is to
  grep for the *primitive* (`System.currentTimeMillis()` compared against a stored value) rather
  than for the feature.

### Not yet swept

- `UsageTracker` (its day rollover and the session reconstruction from usage events)
- the web/keyword scan itself (`WebContentFilter`, `extractVisibleText`, browser URL reading)
- the updater itself (`Updater`, version comparison, download/install) — `UpdatePause` is done
- the location condition (`inLocation`, `ensureLocationUpdates`, fix freshness)
- the UI's own live state: `resumeTick` re-reads, and the several screens that cache
  service/prefs state in `remember` blocks. The fourth sweep touched only the adult-pack gate;
  the pattern (a `remember` holding a decision that time or the service can invalidate) is
  everywhere and is worth a sweep of its own.
- the remaining `System.currentTimeMillis()` deadline comparisons, now that two of them were
  bypassable. Notification throttles are fine (a wrong clock only re-notifies); anything the user
  benefits from skipping is not.

### A pattern worth generalising from the second sweep

Both watchdog bugs were the same shape: **a safety mechanism that can fail silently, or take down
what it protects.** When auditing, ask of anything defensive — the watchdog, `guarded`,
`ServiceHealth`, the update pause — *"if this itself broke, would anyone ever know?"* That question
found more than reading the blocking logic did.

### Lesson from the first sweep

`GuardedDeadline.remaining()` (then named `KeywordLockout`) read the clocks internally, so CI failed
on a test that *could not*
pass: unit tests run with `isReturnDefaultValues = true`, so `SystemClock.elapsedRealtime()`
returns 0 while `System.currentTimeMillis()` is real — every lockout looks post-reboot and the
monotonic branch is unreachable. `SessionClock` has a `...At(now)` seam for exactly this. **Any
new time-dependent logic needs that seam or it cannot be tested here.**

## Standing guidance

- **Batch findings into one release.** Five releases in one day (v1.94→v1.98) was too many, and
  came from shipping each fix the moment it was ready. Audit, collect, then release once.
- **Extract before fixing, when logic has broken twice.** `CoverGate` came out of the
  double-block work and made its rules testable; `GuardedDeadline` came out of the same clock
  bypass appearing twice. Do the same for the next repeat offender — the natural candidate is
  "given an event and what's on screen, should the cover change?", which is decidable from plain
  values and would lock in invariants 1–4.
- **Grep for the primitive, not the feature.** Both clock bypasses were local re-implementations
  of something `SessionClock` already did safely, so no search for the *concept* found them. A
  search for the raw ingredient (`System.currentTimeMillis()` against a stored number) would have.
- The owner is non-technical: don't ask him which code to change. Ask only about behaviour
  he can judge (e.g. "should two opens ten seconds apart count once or twice?").
