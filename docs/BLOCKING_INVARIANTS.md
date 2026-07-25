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
  forward lifted the lockout. Now anchored via `SessionClock` in `data/KeywordLockout.kt`, which
  also folds the triggering word in (one less parallel map).
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

### Not yet swept

- schedule evaluation (`Schedule`, `TimeWindow`, Wi-Fi/location conditions)
- `UsageTracker` (its day rollover and the session reconstruction from usage events)
- the web/keyword scan itself (`WebContentFilter`, `extractVisibleText`, browser URL reading)
- the updater and `UpdatePause`
- `ProtectionWatchdog` / `ServiceHealth` — note it checks event liveness only, which is why the
  rule-flow bug above was invisible to it. Worth asking what else it cannot see.

### Lesson from this sweep

`KeywordLockout.remaining()` read the clocks internally, so CI failed on a test that *could not*
pass: unit tests run with `isReturnDefaultValues = true`, so `SystemClock.elapsedRealtime()`
returns 0 while `System.currentTimeMillis()` is real — every lockout looks post-reboot and the
monotonic branch is unreachable. `SessionClock` has a `...At(now)` seam for exactly this. **Any
new time-dependent logic needs that seam or it cannot be tested here.**

## Standing guidance

- **Batch findings into one release.** Five releases in one day (v1.94→v1.98) was too many, and
  came from shipping each fix the moment it was ready. Audit, collect, then release once.
- **Extract before fixing, when logic has broken twice.** `CoverGate` came out of the
  double-block work and made its rules testable. Do the same for the next repeat offender —
  the natural candidate is "given an event and what's on screen, should the cover change?",
  which is decidable from plain values and would lock in invariants 1–4.
- The owner is non-technical: don't ask him which code to change. Ask only about behaviour
  he can judge (e.g. "should two opens ten seconds apart count once or twice?").
