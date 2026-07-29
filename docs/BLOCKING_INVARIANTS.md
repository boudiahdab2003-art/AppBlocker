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
9. **Elapsed time is measured monotonically.** Anything of the form "has N passed since X" uses
   `SystemClock.elapsedRealtime()` — in the service, via `stopwatchNow()`. The wall clock is only
   for genuine calendar facts (schedule windows, day stamps, notification throttles), and a
   deadline that must survive a reboot goes through `GuardedDeadline`/`SessionClock`. The user can
   move the wall clock; a negative interval reads as "no time has passed", which silently freezes
   whatever the timer was guarding. This has now been the cause of three separate sweeps' findings.
10. **An empty or failed answer is not data.** Every package/asset query in this app swallows its
    errors into an empty collection, and "no browsers", "no launcher", "no adult words" is never
    true. Adopting one silently fails *open*. Adopt a result only when it is non-empty, keep the
    previous value, and record the failure (`refreshPackageSets`, `WebContentFilter.get`).

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

Outside the watcher the same method transfers: enumerate the *primitives the feature is built
from*, not the feature. For the updater (sweep 12) that was every `openConnection`, every write to
a fixed path, every version comparison, and every platform call with a minSdk floor — four greps,
six findings.

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

### Swept in the fifth "bug hunt" (25 Jul 2026)

Two targets: the web/keyword scan, and the previous sweep's own prescription — **grep for the
primitive, not the feature.** The grep is what paid, and it found the clock bug a *third* time.

- every in-memory timer in the watcher — **1 bug, 6 sites, and a working bypass of Strict Mode.**
  `lastGuardBounceAt`, `lastBlockAt`, `webScanQueuedAt`, `dismissedAt`, `lastCountedAt` and
  `exitStartedAt` were all wall-clock stopwatches. A backward clock change makes each interval
  negative, which reads as "no time has passed", so each froze in the state that does nothing:
  no cover raised, no scan on a busy page, no re-block, no re-count, and the exit watcher holding
  its cover forever (invariant 7). A forward change lapsed them all at once — the v1.95
  double-block returning.
  - **The bypass:** `strictRemaining()` is clock-proof, so a Strict session can't be shortened —
    but `handleStrictSettingsGuard` *returns `true`, claiming it bounced, without bouncing* while
    its throttle is live. Get bounced once, set the clock back (Date & time is not a guarded page),
    and the Accessibility toggle is reachable. Nothing else defends that toggle.
    *(No longer true as of 26 Jul 2026 — see "The off-switch guard" below. This line sat here as a
    known gap for a day and a half before the owner walked through it.)*
  - Fixed via `stopwatchNow()`, whose KDoc is the extraction: it names what each timer guards and
    states the boundary between the three clocks. Note the *newer* throttles (`isLauncherPkg`,
    `imePackage`, `refreshCurrentLocation`) already had this right — only the older cover
    machinery was wrong, which is why a feature-shaped search never found it.
- the Shorts check — **1 bug.** `isShortsOnScreen()` returned a plain `Boolean`, and `scanShorts`
  *removes* the cover on false. Both routine ways of failing to read the screen — a null root, and
  our own cover reporting as the active window — answered false, so the cover came off while the
  user was still on a Short and the next scan put it back: a flicker with a watchable gap each
  cycle. Now `Boolean?`, null changing nothing. Invariants 1 and 4, in the one scan never swept.
  - Exhausting `MAX_NODES` deliberately still answers **false**, not null: the screen *was*
    readable, the walk is breadth-first and the reel markers sit high in the tree, whereas null
    there would risk stranding a Shorts cover over ordinary YouTube — visible over-blocking, and
    harder to escape. Decided, not defaulted.
- `UsageTracker.addInterval` — **1 bug, a dated hang.** A local day is 25 hours on the DST
  fall-back day, so an interval can sit past `dayStart + 24h`; the hour index was coerced into
  0..23 while `hourEnd` came from the *coerced* hour, putting that end behind the cursor. The step
  went negative (corrupting a bucket), then exactly zero, and the loop never terminated — Insights
  and the coach hanging on a pegged core, once a year, in late October. Now clamped, with a
  "never take a non-positive step" guard. Made `internal` and **tested** (`UsageTrackerTest`, four
  cases with `timeout` asserting termination) — the only finding of this sweep that is testable.
- `WebContentFilter.check` layering, the `lastWebText` dedup, `shouldScanPkg` and the scan's
  suppression gates — clean.

**Considered and deliberately left** (so the next sweep doesn't re-litigate them):

- `extractVisibleText` adds `rootInActiveWindow` on top of the `windows` roots, so when `windows`
  is populated the active tree can be walked twice, burning half the 400-node budget. Harmless on
  this device (`windows` is empty without `flagRetrieveInteractiveWindows`, which is deliberately
  not set) and not worth changing scan coverage for.
- `adultDomains`/`adultKeywords` match page text with plain `contains`, not whole-word, so a page
  merely mentioning a listed domain can be blocked. That is *over*-blocking, which the owner sees
  and can report — his call, not a silent defect.
- `MAX_NODES`/`MAX_TEXT` cap how deep any scan reads, so text far down a large page is never
  examined. A real limit, but the intended battery trade-off rather than a bug.

### Swept in the sixth "bug hunt" (25 Jul 2026)

Primitive grepped this time: **a failure or empty result treated as authoritative data** —
`runCatching { … }.getOrDefault(emptySet()/emptyList())` and friends. Chosen because it had
already caused two findings (the launcher set in sweep 3, the Wi-Fi SSID in sweep 2) without
anyone enumerating the rest. Both findings below **fail open**, which is the invisible direction.

- `WebContentFilter.get` — **1 bug.** Every list is read through `runCatching`, so a failed asset
  read produced an *empty* list, which matches nothing while the adult switches still say ON. That
  empty filter was then cached in `INSTANCE` **and** pinned by the service's `by lazy`, so one
  transient failure disabled the adult layers for the rest of the process's life. No crash, no
  warning, and the one protection with a 24-hour cooling-off doing nothing.
  - Not hypothetical here specifically: the in-app updater installs a new APK over a service that
    keeps running, and an asset path replaced under a live process is exactly when reads fail —
    which is also when `UpdatePause` switches every other layer off, leaving this one alone.
  - `readLines` now returns `null` on failure (distinct from a legitimately empty file), `get`
    refuses to cache a partial load, the service resolves `filter` per use instead of holding a
    `lazy`, and the first failure is recorded in `ServiceHealth` — visible on Profile since sweep 2.
- `browserPackages` / `launcherPackages` direct assignment — **1 bug.** `isLauncherPkg` already
  refuses to trust an empty answer (sweep 3's fix), but the two sites that assign the sets outright
  — `onServiceConnected` and the package-change receiver — did not. An empty browser set makes
  every browser an ordinary app: no adult site list, no blocked-app websites, no
  unsupported-browser block, and during the after-update pause no scanning at all. Nothing else
  re-detects browsers, so it stays wrong until the next install or removal. Now both sets go
  through `refreshPackageSets()`, which adopts a result only when it is non-empty.
  - **This is the "enumerate every instance" guidance failing in real time:** sweep 3 fixed the
    rule in one function and left the sibling assignments alone, three sweeps ago.

**Considered and deliberately left:**

- `currentImePackage` returns null on failure — but it is read live on every use and self-heals on
  the next read, and its failure direction is *over*-blocking (the keyboard not rescued in
  Allowlist mode), which the owner can see and report.
- `AppVersion.code` returns `-1`, but `UpdatePause.checkVersionChange` already early-returns on a
  negative, so a failed read cannot spuriously arm the pause. Correct as written.
- `findEssentialPackages` starts from a hardcoded set, so a failed dialer resolve can never empty
  it. Correct as written.
- The UI's `remember { }`-without-`resumeTick` caches in `BlockEditorScreen`/`BlockingScreen`/
  `KeywordsScreen`: checked, and `strictActive` reaches all of them as a live flow from
  `AppRoot`, so Strict starting mid-edit does lock the controls. The remaining staleness is
  cosmetic (a toggle read on entry), not a blocking hole.

### Swept in the seventh "bug hunt" (25 Jul 2026)

Primitive grepped: **data with an age, trusted without checking that age.** Every other cached
reading in the app has a TTL (`usedTodayCache` 15s, `cachedImePkg` 10s, the tips cache, the
watchdog's own `lastEventAt`). Exactly one did not.

- the location condition — **1 bug, both directions.** `lastLocation` was set once and trusted
  forever. `considerLocation`'s guard only stops an *older* fix overwriting a newer one; it does
  nothing when **no** new fix arrives, which is the normal state of a stationary phone. So a fix
  taken at the blocked place kept blocking everywhere the user went afterwards (visible — "it
  blocks me at work"), and a fix taken away from the place meant returning to it never started
  blocking (invisible, therefore never reported).
  - The rule moved into `locationFixUsable` in `BlockDecision.kt` — pure, unit-tested
    (`LocationFreshnessTest`), and measured in `elapsedRealtime` nanos per invariant 9, because a
    location's age must not be measurable with a clock the user can move. A fix dated in the future
    is unusable rather than infinitely fresh.
  - **The ceiling needed a second change to be safe.** `requestLocationUpdates` used a 25 m
    displacement filter on *both* providers, so a stationary phone received nothing and the fix
    only stayed current via `refreshCurrentLocation` — which is API 30+ only, and minSdk is 24. On
    an older phone the ceiling alone would have converted "stale but probably right" into "no
    blocking at all". NETWORK now updates on time alone (`minDistance = 0f`; cell/Wi-Fi derived, so
    cheap), giving a heartbeat on every API level. GPS keeps its filter — that radio is the
    expensive one.
  - `stopLocationUpdates` now also clears the position: having stopped tracking, keeping one meant
    a schedule re-enabled later decided from where the phone was before it was switched off.
- the updater — **clean, and now pinned.** `isNewer` is correctly numeric per component, so
  `1.100 > 1.99`. Worth stating because the version numbering crossed exactly that boundary in this
  release and *every* fix reaches the phone through this one function: a string comparison would
  have silenced the updater permanently. Four boundary cases added to `UpdaterTest`.
- the Location schedule editor — clean. It already warns when background location is missing, and
  is in fact what the Wi-Fi warning was modelled on (sweep 2). The sibling-instance lesson had
  already been applied here.

**Considered and deliberately left:**

- When the ceiling trips, `inLocation` answers false (not blocked) — the same as no fix. Blocking on
  an unknown position would block the app *everywhere*, which is the wrong failure for a
  place-scoped rule. But note this is a **silent** stop: a Location schedule whose fixes have dried
  up simply does nothing, and nothing says so. The permission case is warned about in the editor;
  the "permission granted but no fixes arriving" case is not. That is the next thing worth building
  here, and it needs UI, not a bug fix.

### Swept in the eighth "bug hunt" (25 Jul 2026)

Primitive grepped: **work that outlives the state it was started for.** Enumerate every
`postDelayed` and every `scope.launch` against every cancellation site, then ask what each one does
if it completes *after* the thing it was about to act on is gone.

Visibility is genuinely sound — everything shared between the main thread and the background scan
is `@Volatile`, including `BlockOverlay`'s fields. The bug was lifecycle, not memory model.

- the Shorts scan's lifetime — **1 bug, two symptoms, and the sibling asymmetry again.**
  `webScanJob` is cancelled in three places; `shortsScanJob` was cancelled in **none** (only
  self-superseded), and `scanShorts` raised its cover with **no main-thread re-confirmation** — the
  guard `scanWebContent` has always had.
  - Swipe Home while a scan is in flight → the cover lands **on the home screen**, and an attempt is
    counted. Visible, and matches symptoms reported repeatedly.
  - Lock the phone while a scan is in flight → `onScreenOff` does its whole cleanup, then the
    resumed coroutine raises a cover with nothing left to take it down: a stranded cover on unlock.
    **That is the v1.98 bug arriving by a different route** — fixed then for the flag path, still
    open on the coroutine path.
  - Fixed with both halves, because cancellation alone is racy (a coroutine past its last suspension
    point still completes): re-confirm `lastForegroundPkg == YOUTUBE_PKG && stillOnScreen(...)`
    inside the Main block, *and* cancel the job where the web scan cancels its own — on screen-off
    and on leaving YouTube. `onScreenOff` also now cancels `shortsScanRunnable`, the one queued
    callback it was missing.
  - Note which half catches which symptom: `onScreenOff` nulls `lastForegroundPkg`, so the cache
    check catches the lock case; `stillOnScreen` asks the window tree, so it catches an app switch
    whose window event never arrived — the HyperOS quirk this whole file exists for.

**Considered and deliberately left:**

- `handler.postDelayed({ … }, 1500)` in `handleStrictSettingsGuard` is an anonymous lambda, so it
  can never be cancelled and is the only scheduled callback not wrapped in `guarded`. Harmless as
  written — `overlay.remove()` wraps `removeView` in `runCatching`, and its post-teardown effect
  (taking down a non-app-block cover) is what you'd want anyway. Worth naming it if it ever grows a
  side effect; an uncancellable callback is a liability even when today's body is safe.
- `onDestroy` calls `scope.cancel()`, so every background scan dies with the service. Only the
  *screen-off* path needed per-job cancellation.

### Swept in the ninth "bug hunt" (25 Jul 2026)

Method: **diff the three block entry points against each other.** `handleAppBlock` has been audited in
six sweeps; `handlePurchaseBlock` and `handleStrictSettingsGuard` in none. Sibling asymmetry has been
the highest-yield shape in this file, and both siblings were unexamined.

- the deferred-confirmation path loses the event's class name — **1 bug.**
  `confirmForegroundRunnable` called `onForegroundChanged(actual, null)`. Trusting the window tree
  over the event is correct about the *package*; discarding the **class name** was accidental, and
  the tree cannot supply one. Consequence, ranked by how the three checks cope:
  - `handlePurchaseBlock` is className-**only** (`className?.lowercase() ?: return false`), so an
    in-app purchase sheet that lost the race with the window tree was **never covered** — and
    nothing re-checks purchases afterwards (`recheckRunnable` only re-runs `handleAppBlock`), so it
    stayed uncovered for as long as the sheet was open. Invisible, and `blockPurchases` is opt-in so
    it would never have been noticed.
  - `handleStrictSettingsGuard` survived the identical race, for two reasons worth copying: it is
    deliberately still called *inside* the gate with the real className, **and** it has an
    on-screen-text fallback (`guardScreenIsDangerous`) beside its className fast path — added
    because "OEMs name these activities unpredictably".
  - Fixed by carrying the className through the deferral, keyed to the package it described so one
    app's class can never be applied to whatever else turned out to be in front.

**Considered and deliberately left:**

- **Purchase detection is still className-only, with no second chance.** The right fix is the Strict
  guard's shape — a text fallback scoped to `com.android.vending` — but that needs Play's actual
  sheet strings, which cannot be verified from a cloud session, and a wrong marker would cover the
  Play Store's ordinary app pages. Over-blocking the Play Store is the visible, annoying failure, so
  guessing here is worse than the gap. Needs a look at the real sheet on the device.
  `PURCHASE_HINTS` ("acquire", "purchase", "billing") does match Play's real billing activity
  (`…finsky.billing.acquire.SheetActivity`), so the common path works.
- The content-changed fast path passes `event.className`, which for a content event is the *view's*
  class, not the activity's — harmless (it simply never matches a purchase hint) but worth knowing
  before trusting className on that path.

### Swept in the tenth "bug hunt" (25 Jul 2026)

Different method: **audit the day's own changes.** Sweeps 5–9 plus the coach upgrade all shipped
unreviewed in one session, and new code is where bug density is highest. This is also the only sweep
whose findings are self-inflicted, which is the point — the method has to work on the person using it.

- **1 bug, mine, from the coach upgrade.** Raising the read ceiling to 120s while both call sites
  still did `repeat(2)` unconditionally tripled the worst-case wait: ~4.5 minutes of "Thinking…"
  before the user saw anything, up from ~90s. The retry's purpose is transient blips (a 5xx, a
  truncated response); a **timeout** is the one failure where retrying is both slowest and least
  likely to help, since the far end already had the full window. Now `retryWorthwhile` skips the
  retry only for timeouts, and the ceiling is 90s (still 3× the expected reply time).
- Two slips caught *while writing that fix*, both worth recording because they are the shape of
  thing that ships silently:
  - `return@repeat` **continues** the loop rather than abandoning it, so the first version of the
    fix was a no-op. Replaced with an explicit `while` + `break`.
  - The first `retryWorthwhile` read `t != null && …`, which would have stopped retrying the case
    the retry exists for: `runCatching` treats a blank reply or an unparsable tips array as
    *success holding null*, so the exception is null there. The null case must answer **true**.
- Verified clean in my own work: `lastLocation` has exactly one decision reader (`freshLocation`),
  so sweep 7's ceiling has no bypass; nothing in the service compares a `stopwatchNow()` field
  against the wall clock; `pendingClassName` cannot be applied to the wrong package.

**Considered and deliberately left:**

- `CoachChatViewModel.coachModel()` reads prefs synchronously during composition and won't recompose
  if the model changes mid-session. It is a diagnostic line in a dialog opened after a reply, so it
  is always fresh enough in practice; making it reactive would cost more than it is worth.
- The advice ledger is a `StringSet`, which is unordered and deduplicates. Sorting by day stamp
  recovers the ordering that matters, and two identical pieces of advice on one day collapsing into
  one is the desirable outcome anyway.

### Swept in the eleventh "bug hunt" (25 Jul 2026)

Method: diff the **five counting stores** against each other, then the **five schedule types**
against each other. Sibling sets, which is where nearly everything has been found.

- **Usage-limit schedules silently never block without usage access — 1 bug, and the third
  instance of a class already fixed twice.** `usedMinutesToday` returns **0** when
  `todaySnapshot` is null (no `PACKAGE_USAGE_STATS`), so every app looks like zero minutes, the
  limit is never reached, and the schedule does nothing. The editor never checked. Worse, that
  permission is presented as *optional* elsewhere in the app (`Permissions.kt` marks it
  `essential = false`, because it otherwise only powers Insights) — so a user has every reason to
  skip it, and no way to learn it silently disabled a schedule they set.
  - The Wi-Fi type got this warning in sweep 2; the Location type already had one. This was the
    third sibling and had nothing. Fixed with the same component shape and a deep link to
    `ACTION_USAGE_ACCESS_SETTINGS`.
  - Note `LAUNCH_COUNT` genuinely does **not** need it — `LaunchCounter` counts accessibility
    events, not usage stats. So of the five schedule types, three depend on a permission and now
    all three say so.

**Verified clean, so a later sweep can skip them:**

- The counting stores diff clean. `AttemptCounter` is the one that doesn't prune, and that is
  correct: its `total_` counts are lifetime by design, and growth is bounded by the number of
  distinct targets ever blocked.
- `LaunchCounter` inflation via the notification shade — **checked and already fixed.**
  `onForegroundChanged` early-returns on a transient surface at the very top (before the cache
  update *and* `recordOpen`), and its comment names the open limit explicitly. v1.98's claim that
  the shade "no longer adds a phantom open" is accurate.
- `usedMinutesToday`'s 15s cache is keyed on `elapsedRealtime` and self-heals across midnight
  within the TTL, since `todaySnapshot` re-queries from the new `startOfToday()`.

### Swept in the twelfth "bug hunt" (26 Jul 2026) — the updater

First sweep of the updater. Method: enumerate the primitives an updater is made of rather than
reading it narratively — every `openConnection`, every write to a fixed path, every version
comparison, every API call with a minSdk floor. Six findings, none of them ever reported, which
is exactly the profile of this area: **a broken updater looks like "nothing happened"**, and
"nothing happened" is not something the owner reports as a bug.

- **A complete download is not an APK — 1 real bug.** `Updater.download` checked the byte count
  only, and only when the server declared one (`Content-Length` is optional), and never checked
  the HTTP status at all. A captive portal — hotel/café Wi-Fi — answers *every* request with a
  complete, correctly-sized 200 login page, which was written to `update.apk` and handed to the
  installer. Fixed by requiring `HTTP_OK` and checking the file begins with the ZIP local-file
  header. The magic-byte check is the only integrity check that *always* runs.
  - `Updater.looksLikeApk(ByteArray)` is `internal` **so it can be tested** — the same reason
    `addInterval` was made internal in sweep 5. It is the only testable part of this path.
- **Granting install permission dead-ended — 1 real bug.** `downloadAndInstall` returned silently
  after opening the permission screen. Coming back: the once-per-launch prompt was gone (already
  dismissed, `checkedOnce` set), the state still read `Available`, so the tap had visibly done
  nothing. The release is now held and resumed from `onResumed()`, wired to the existing
  `resumeTick` in `AppRoot`. **This one only bites on a phone that has never installed an update**,
  which is why it survived a year of the owner updating successfully.
- **The after-update pause could be lost — same shape as ever.** `checkVersionChange` wrote the
  new version code *before* arming the pause; a process death between the two prefs writes left
  the version recorded and the pause unarmed, permanently (the next start sees
  `last == current`). The Strict-clear two lines below **already** used "write the durable intent
  first" and said so in its comment. Sibling asymmetry, in adjacent lines of the same function.
- **`canRequestPackageInstalls()` is API 26; minSdk is 24** — a `NoSuchMethodError`, not a
  `false`. Latent (the owner is on 15) but it is a real crash on any 7.x device, and `lint` does
  not run in the Build check so nothing would have caught it. Guarded.
- Two smaller ones: the modal download dialog had **no Cancel** (nothing to tap on a stalled
  transfer — the fix needs `ensureActive()` in the copy loop, or Cancel would hide a transfer
  that kept running), and the downloaded APK was **never deleted**.

**Considered and left:** `latest()` reports "couldn't reach the update server" when the release
simply has no `.apk` attached, so a half-failed publish is indistinguishable from a network
failure; and its two retries have no delay between them, so a transient blip is unlikely to be
ridden out. Both cosmetic beside the above.

**Verified clean:** the FileProvider hand-off. `app/src/github/res/xml/file_paths.xml` really does
declare both `external-files-path` and `files-path`, so `download`'s fallback to internal storage
when external is unmounted can genuinely be served — the comment claiming this is accurate. The
provider is correctly confined to the `github` flavour along with `REQUEST_INSTALL_PACKAGES`.

**Still open — a question for the owner, not a code fix.** The update row in Profile and the
launch prompt are **not** locked during Strict Mode (`enabled` there ignores `locked`, unlike
every other protective row), and installing an update deliberately **ends** a running Strict
session (`UpdatePause`). So whenever an unreleased version exists, a Strict session can be ended
in two taps from inside the app. `UpdatePause`'s KDoc argues it is not an escape hatch because
"reinstalling the same APK" won't do it — true, and it does not address the case where a genuinely
newer release *is* available, which for this repo is often.

### The off-switch guard (26 Jul 2026) — not a sweep, a reported relapse

Not found by auditing. The owner turned the accessibility service off in Settings on a bad day and
browsed everything he had blocked. Worth recording as its own entry because **the hole was already
written down in this file and read as a note rather than a bug** — sweep 5 above ends with
*"Nothing else defends that toggle."*

- **What was actually wrong:** `handleStrictSettingsGuard` opened with `if (!strict) return false`.
  Outside a Strict session nothing guarded the Accessibility page, and disabling that service
  disables *every* block in the app — apps, keywords and websites alike, since all of them are
  enforced from the watcher. Strict Mode was a lock on a door standing in an open field.
- **Fixed** by `OffSwitchGuard`: the guard now also runs whenever the owner has it on (default on),
  with the escape modelled on the adult pack's cooling-off — a typed gate, then a two-hour
  clock-proof wait, then a 15-minute window. `strict ||` still comes first, so Strict cannot become
  weaker than it was.
- **Second bug found while fixing it, never hit by anyone:** `GUARD_TEXT_MARKERS` was English-only
  and the fallback *fails silently* when it doesn't match — on a phone whose Settings are not in
  English the whole text path was dead, leaving only the className fast-path on HyperOS, the build
  whose class names the code itself calls unpredictable. Arabic markers added, and the guard text
  now folds through `WebContentFilter.normalizeArabic` so one spelling matches the variants. This is
  the "under-blocking is invisible" rule with a new edge: **a defence keyed on English UI text is
  invisible-by-default on a translated device**, and no amount of using the app would reveal it.
- **The generalisable question**, and the one sweep 5 should have asked: for each defence, *what
  turns it off, and what defends that?* Strict guarded the toggle; nothing guarded Strict's absence.
  The equivalent question is still open for the VPN layer if it ships.
- **Third finding, from the owner asking what it actually blocked (v1.103).** The widened guard
  inherited Strict's rule that the *kind* of page is enough — `STRICT_GUARD_HINTS` matches a class
  name with no check that the page concerns AppBlocker. Bounded and opt-in under Strict; but
  always-on by default it meant **every app's App-info page and the whole Accessibility section**
  were bounced, so force-stopping a frozen app cost a 2-hour wait. Nobody asked for that, and it
  went unnoticed because the release was described in terms of what it *defended*, not what it
  *cost*. Narrowed via `aboutUs()`; **Strict deliberately keeps the broad rule**, being a bounded
  lock the owner sets on purpose.
  - Worth naming as a shape: **widening a defence's trigger also widens its collateral**, and the
    collateral is the part you don't think to describe. When a Strict-only rule is promoted to
    always-on, re-ask what it forbids on an ordinary day, not what it protects on a bad one.
  - `aboutUs()` originally **failed closed** (an unreadable screen answered "ours"), reasoning that
    a wrong yes is visible and waitable while a wrong no is invisible and total. **That was wrong,
    and the owner hit it the same day** — see below.

### The fail-closed that pointed the wrong way (v1.104)

Reported as "the block screen was flashing in wrong places". `aboutUs()` runs on the
**window-state** event — the instant the window is being built, when `rootInActiveWindow` is most
often null. "Unreadable" is therefore not a rare failure at that moment, it is the *common case*,
so answering "ours" meant **any** app's App-info page could raise a cover and bounce, depending on
a race. Intermittent over-blocking, which is what flashing is.

The lesson is not "fail open instead". It is that **fail-closed reasoning has to name the moment
the check runs.** "A wrong yes is cheap" was true in the abstract and false here, because the
check fires precisely when the information is missing. Nothing was lost by waiting: the page had
just opened, no toggle could have been reached, and the content-event re-check runs milliseconds
later with a populated tree and catches a real off-switch page through the text path.

`aboutUs()` now returns `Boolean?`, null changing nothing — the **third** appearance of that
primitive after `isShortsOnScreen()` (sweep 5) and `protectionState`'s `usedMinutes` (sweep 2).
Worth generalising: when a screen-reading check can't read, the answer is null, not a guess. Grep
for `rootInActiveWindow` and ask of each caller what it does when the tree is empty.
  - **Two under-blocking risks this created, both still live and worth a future sweep.** Android
    only builds nodes for *rendered* rows, so our row is genuinely absent from a long scrollable
    list until scrolled to — caught, in theory, only by the content-event re-check falling through
    to the text path. And the text walk is budget-capped; it was raised to 800 nodes / 8000 chars
    because a settings list can be long, but a cap is still a cap. Neither is covered by a test,
    because the watcher has none.

### "Mentions us" was never the right signal (v1.105)

Reported as "I use other accessibility apps too" — the guard bounced the **whole** Accessibility
section, not just AppBlocker's entry, behind a two-hour wait. TalkBack is in that section.

The 1.103 narrowing asked *does this page mention appblocker?*, which fixed App-info pages (they
name one app) but could never fix this one: **Android's Accessibility list names every installed
service**, so the list satisfies the same test our own entry does. The signal was not weak, it
was wrong — present on both pages by construction.

`aboutUs()` now asks *does it name us and no other service?*, matching against the labels of the
installed accessibility services (`findOtherAccessibilityLabels`, cached beside the launcher and
browser sets and refreshed on the same package events). Same empty-answer rule as those two: an
empty lookup keeps the previous set, because an empty one makes the list unrecognisable and
silently restores the bug.

Two things worth carrying forward:
- **Ask what a signal is true of, not just what it is true for.** Three attempts at this guard
  each failed on that: class-name-only matched every app's pages, "mentions us" matched the list,
  and the fail-closed read matched every unreadable moment.
- **Accepted weakening, chosen twice by the owner:** if an OEM build puts the on/off switch
  directly in the list, the service can now be turned off without the guard firing. Tapping into
  our entry still bounces, so the common route is defended.

### Not yet swept

- ~~The rest of `BlockOverlay`.~~ **Swept in the eighteenth hunt** — all eleven readers traced, the
  set-before-`addView` window confirmed unreachable, one latent trap (`onClose`) recorded. See that
  entry. It is no longer the best candidate.
- **The new best candidate: the AI coach's persistence and the report queue's disk format.**
  Sweep 17 swept the serializers in `data/`; the coach's own stores (`CoachProfile`, the advice
  ledger, `MoodStore`) were only glanced at in sweep 10, and `BugReportQueue` has been the source
  of a bug on two separate days.
- ~~The Insights-side of `UsageTracker`'s bucket queries.~~ **Not cosmetic — the owner hit it the
  same day it was written down here.** See the sweep-fourteen entry below: dismissing it as "off by
  part of a day at the edges" understated it, because for *today* the edge is the whole of
  yesterday. The lesson is about the note, not the code: an imprecision worth writing down is worth
  bounding, and "cosmetic" was a guess about size that nobody had measured.
- The rest of the UI's live state. Sweep thirteen took the `remember`-blocks pass and found the
  one that mattered (`KeywordsScreen`'s phase), but only audited the blocks that gate a
  *protection*. `BlockEditorScreen` and `BlockingScreen` cache a dozen settings each and were read
  quickly rather than reasoned through; both refresh on `LaunchedEffect(perms)`, which is a
  resume-tick in disguise, so they looked sound.
- Every `remember` that survives backgrounding. Finding 3 turned on recomposition *stopping* while
  the app is in the background, which makes a `LaunchedEffect` ticker an unreliable cleaner. Any
  other place that leans on a ticker to correct stale state has the same hole.

### Swallowed errors now leave the device (v1.103)

`guarded` still records to `ServiceHealth`, and now also queues a redacted report that
`BugReportSender` posts to a private issue tracker. This partly answers the standing question
below — *"if this itself broke, would anyone ever know?"* — for the one case where the answer was
reliably **no**: an error the watcher swallowed on a phone whose owner cannot see under-blocking.

Three things a future sweep should check rather than assume:
- **The reporter must never be load-bearing.** A failure inside `BugReportSender` is swallowed and
  deliberately **not** reported, or a dead endpoint becomes an infinite loop. If someone later
  wraps the sender in `guarded`, that loop is exactly what they will create.
- **The redaction is an allow-list and must stay one.** `BugReport` never reads
  `Throwable.message`; `BugReportTest` fails if it does. The temptation to "just include the
  message, it's usually fine" is the whole risk, on an app whose keyword list is adult words.
- **Dedup keys off a stack frame**, so a bug reporting from a *changing* line would slip past it.
  The per-day cap is the backstop; it has no test because the queue needs a `Context`.

### Swept in the thirteenth "bug hunt" (29 Jul 2026) — transient surfaces, and one stale phase

Two greps, three findings, all of one shape: **something that is on screen being read as evidence
about something else.**

`grep -n "rootInActiveWindow"` — eleven reads in the watcher. Nine were already guarded. Two were
not, and both had the same hole: a *transient surface* (the shade, the volume dialog, a heads-up
notification, the keyboard) is a readable window that says nothing about what is underneath it.

1. **The re-check tick raised covers on the strength of a transient window.** `recheckRunnable`
   refuses to *reconcile* the cache to a transient surface — correct, and commented as such — and
   then two lines later used `actual != null && actual != packageName` as proof that the cached app
   was still in front. After a missed gesture-nav Home event (the documented HyperOS quirk that
   most of the original nine trace back to), pulling down the shade on the home screen, or opening
   a keyboard in another app, covered the **stale** package until the next event tore it down.
   Milliseconds, over an app that is not blocked. This is a strong candidate for the second cause
   of the flashing the owner has reported twice and that no amount of reading had explained.

2. **The exit watcher read one as "they left".** `exitView` returned `LEFT` for any readable
   non-ours window, so a notification arriving in the ~2.5 s after "Got it" ended the watch and
   dropped the cover while the blocked app sat behind the shade. Now falls through to `BLIND`,
   which is the answer that case has always deserved. Self-healing either way (the app's next
   window-state event re-blocks), which is exactly why it was invisible.

`grep -n "remember {"` across `ui/` — the "not yet swept" candidate this file has been pointing at.

3. **`KeywordsScreen` re-derived the unlock phase and got it wrong.** `offReady` was
   `offRequest != null && untilUnlock <= 0L` — it never asked whether the window had since
   **closed**, so a request whose 24 hours were served days ago still read as "you may switch the
   adult pack off now". A 30-second ticker cleared lapsed requests, which mostly hid it; but
   recomposition stops while the app is backgrounded, so coming back to that screen after a missed
   window handed back a live switch for up to 30 seconds more. `OffSwitchGuard.phase` is the same
   state machine, complete and unit-tested (`a lapsed window is not an open door`), and the screen
   now calls it. Two copies of one state machine, one of them incomplete — bug shape #1, in a
   protection whose whole design is that it is expensive to switch off.

**Second pass, same day — the readings the app treats as facts about itself.** Two more, from
`grep -n "getOrDefault(\|?: 0\|?: emptyList()"` across `service/` (invariant 10: an empty or failed
answer is not data):

4. **`usedMinutesToday` answered 0 for "couldn't read it".** That number is what a daily limit is
   compared against, so an empty `queryUsageStats` — access revoked mid-day, the stats database
   rotating around midnight, an OEM throttling the call — read as "you have used nothing today"
   and the limit silently stopped existing, cached for 15 seconds at a time. Fixed by leaning on a
   property of the quantity: **minutes used today only ever go up**, so a lower reading is a failed
   read wearing a number and the previous figure stands. Day-stamped, because without that
   yesterday's total would stick and block the app all day — the opposite mistake, and a worse one.

5. **`AccessibilityUtil.isEnabled` compared spellings, not identities.** Android's enabled-services
   setting holds either `pkg/pkg.Class` or `pkg/.Class` depending on what wrote it; the check
   string-matched `flattenToString()`, which is always the long form. On a build that stored the
   short one, a perfectly healthy watcher reads as **off**: the checklist keeps asking for a
   permission already granted, the watchdog keeps announcing that blocking has stopped, and every
   bug report carries `serviceOn=false` about a service that is demonstrably running. No evidence
   this is happening on the owner's phone — it is fixed because it is the first fact I would reason
   from in a report, and a lying diagnostic is the theme this release already had to fix twice.

**Third pass — auditing the same day's own changes**, which is where the sixth finding was:

6. **A gate written for a door that does not exist.** Adding the typed gate to Prevent uninstall, I
   gated the Setup-checklist row too, on the reasoning that it was "a second, free door". It is
   not: both call sites (`PermissionsScreen.PermCard`, `OnboardingScreen.EssentialStep`) draw the
   Grant button under `if (!perm.granted)`, so that screen can only switch device admin **on**. The
   branch was unreachable — and had it ever been reached, a full-screen `FrictionGate` emitted from
   inside a card in a scrolling column would not have drawn as a screen at all. Removed, with the
   reason written where the next person will look. The real improvement from that change survives:
   `toggleDeviceAdmin` is split into `enableDeviceAdmin`/`disableDeviceAdmin`, so the checklist can
   no longer turn the protection **off** by passing a state it never checked.

   The lesson is about the audit, not the feature: *a claim about a second call site is a claim to
   verify, not to assume from a grep hit.* The grep found `toggleDeviceAdmin` in two files; only
   reading the surrounding `if` showed what the second one could actually do.

**Fourth pass — swept and clean, recorded so they are not re-derived:** `UsageTracker`'s history
caches (`cachedPastDays`, `lastWeekAppMinutes`) already refuse to memoise an all-zero or empty
result, which is invariant 10 applied by someone who had learnt it; `dailyMinutes`' only callers
pass a literal 30, so its `days - 1` arithmetic has no reachable edge; `CoverGate`'s suppression and
counting rules, `OwnUi`'s activity lifecycle, `AttemptCounter`/`LaunchCounter`'s day rollovers, and
every remaining day-stamp arithmetic site (all going through `dayGap`, itself a past bug) held up.
The rule flow's `retryWhen` and the per-entry `mapNotNull` in `SettingsStore.keywordLockouts` are
both the "one bad row must not lose the rest" shape, already correct.

**A known limit, deliberately not fixed:** daily limits and daily open counts are *calendar-day*
facts (`todayStamp()`, `startOfToday()`), so winding the device clock back a day resets both. Every
*duration* in the app is clock-proofed through `GuardedDeadline`/`SessionClock`, but "today" cannot
be — a monotonic notion of a day would punish someone whose clock was genuinely wrong and then
corrected. Worth knowing it is a hole; not worth the cure.

Not a finding, but worth writing down: `shouldScanPkg` starts `if (pkg == packageName) return false`,
so the keyword scanner can never read our own screens. The Blocked-words screen lists the owner's
own blocked words, and had that line been missing, opening it would have blocked the app with its
own list — a tidy explanation for "flashing inside the app itself" that turns out **not** to be the
cause. Ruled out, so nobody re-derives it.

### Swept in the eighteenth "bug hunt" (29 Jul 2026) — the auto-updater, hours old

Method: sweep 10's — **audit the day's own changes** — pointed at the silent self-updater, written
today and never run on a phone. It is the highest-risk new code in the app: it replaces the
running APK without anyone watching, and every one of its failure modes is invisible by
construction. Four findings, all mine, all from this morning.

The primitive that produced two of them: **a write that has to outlive the process that makes it.**

1. **`apply()` on the one flag written to survive a process kill — the worst of the four.**
   `SilentInstaller` sets `autoInstalled = true` immediately before committing the install, with a
   comment explaining that anything written *after* the commit would never be written at all,
   because the process is killed the moment the replacement lands. It then wrote the flag with
   `SharedPreferences.apply()` — which returns immediately and flushes to disk on a background
   thread. A kill is not an orderly exit, so the flush is simply dropped. The new version then
   reads "nobody marked this as automatic", arms the after-update pause, and switches **all
   blocking off** — silently, on a phone whose owner never asked for an update and has no reason
   to open the app and find out. The comment named the hazard correctly and the code did not
   defend against it. Now `commit()`.
   - Generalises: **"written before the kill" is a claim about durability, and `apply()` does not
     provide it.** Every other prefs write in this app is fine as `apply()` — including
     `UpdatePause`'s, whose ordering argument survives losing *both* writes because the work is
     re-runnable. This one is the single write whose loss flips the outcome.

2. **A race that clears the same flag before it is read.** `InstallResultReceiver` cleared
   `autoInstalled` on *every* status, including `STATUS_SUCCESS`. But success is delivered to the
   **new** process, and `ACTION_MY_PACKAGE_REPLACED` — which is what makes `UpdatePause` read the
   flag — arrives around the same moment with no ordering between them. Lose the race and the flag
   is cleared before it is read: same outcome as finding 1, by a different route. Success now
   returns without touching it; `UpdatePause` consumes it, being the only place that knows the
   read happened.
   - Two independent paths to one silent failure, in ~120 lines written in one sitting. That is
     the profile of the area, not bad luck: the correct behaviour here is *nothing visible
     happening*, so both bugs look exactly like success.

3. **`PackageInstaller` sessions leaked on the failure path.** `createSession` succeeded and a
   later step (staging space, an unreadable APK) threw; nothing abandoned the session. Sessions
   persist and an app may hold only so many, so a persistent failure would fill the cap over
   successive releases until `createSession` itself threw — auto-update stopping for good, with
   nothing to see. Now abandoned in the catch.

4. **Two downloaders, one fixed path.** `Updater.download` has always written `update.apk`, which
   was safe while the only caller was the owner tapping Update. `AutoUpdateWorker` made it two:
   the six-hourly check can be streaming into that file at the moment he taps Update, and the two
   writers interleave into a file that is neither release. Sweep 12's magic-byte check does not
   catch it — the first four bytes are still a ZIP header — so the corruption would reach the
   installer. A `Mutex` around the download serialises them; the second caller rewrites from the
   start, which is right for both.
   - Worth naming: **adding a second caller to a single-caller resource is a change to that
     resource**, even when its own code is untouched. Sweep 12 enumerated "every write to a fixed
     path" and found this one correct — it was, until today.

**`BlockOverlay` — swept, and the standing candidate is confirmed harmless.** This file has listed
"the rest of `BlockOverlay`" as the best remaining candidate since sweep 13. `show()` does set
`counterKey` and `isAppBlock` before the `addView` that can throw, so a failed show leaves both set
with nothing on screen. All eleven readers were traced: nine pair the flag with `isShowing`, and
the two that do not (the Strict guard's 1500 ms safety net, and the `isAppBlock && !shouldBlock`
teardown) both end in `overlay.remove()`, which is a no-op when no view is attached. It is still
two sources of truth with a window between them; it is not reachable as a bug today, and it is no
longer the best candidate. **One latent trap found instead, not a bug:** `onClose` is a `show()`
parameter but is only ever wired at *inflation*, and the view outlives many blocks — so a caller
passing a per-block closure would silently keep the first one forever. Both call sites pass
`::onCoverDismissed`, so nothing is wrong now; the parameter simply promises more than it delivers.

### Swept in the seventeenth "bug hunt" (29 Jul 2026) — every hand-written serializer

Prompted by the bug found the same day in `BugReportQueue`, whose encoder listed eight fields
while the report had ten, so `context` and `recentBlocks` were dropped on the way to disk and
**every report ever sent arrived without them**. That suggested a kind to enumerate:

```
grep -rln "fun encode|private fun decode|joinToString(\"|\")|split('|')" data/
```

Four serializers, checked field-by-field against their data classes. One more miss:

- **`GuardedDeadline` has six fields and `encode` wrote five** — `note` (the *word that caused a
  keyword lockout*) was dropped, so it survived only in memory and every restore — every reboot,
  app update and OEM kill-and-revive — turned "“casino” was found here" into the generic "A blocked
  word was found here".

  **This one was deliberate, and the tests said so.** `the word is deliberately not persisted`
  asserted the omission, with the reason: a user keyword can contain the format's `'|'` separator,
  and a note in the middle of a pipe-delimited record corrupts every field after it — including the
  numbers the lockout itself is read from. I changed it before reading them, and the build caught
  me. The change stands because the *reason* is now answered rather than ignored: the note goes
  **last** and decode rejoins everything past the sixth field, so a separator inside it lands
  harmlessly in the note's own text. The two tests were rewritten to assert that, rather than
  deleted.

  **Lesson, and it is the second time today**: a test that asserts an absence is documenting a
  decision. Read the tests for the thing you are about to "fix" — earlier the same day I claimed a
  second door to the uninstall switch existed without reading the `if` around it. Both were
  assertions about the codebase made without checking, and both cost a round trip.
- `BlockArrangement` (5 fields), `Goals` (6), `BlockLog` — all complete, and `BlockArrangement`
  notably handles fields added later with `getOrElse` rather than positional indexing.

**The shape to remember: a field added to a data class and not to its serializer.** It cannot be
seen in memory — the object is complete right up until it is stored — and it produces a silent
partial loss rather than a crash. Both instances found today were fields added *after* the
serializer was written, by someone (me) who did not think of it as a schema. Every one of these
pairs now has a round-trip test, which is the only thing that catches it.

### Swept in the sixteenth "bug hunt" (29 Jul 2026) — the PIN, boot recovery

**The PIN was asked once per task and never again.** `LockGate` held `unlocked` in a
`rememberSaveable`, which survives backgrounding *and* process-death restore — so on a phone where
AppBlocker sits in recents, the lock opened once and stayed open for days. Its own description says
"lock your settings so blocks can't be removed on a whim", and the whim arrives hours later, by
which point the gate was already open. Now re-locks after `PinStore.RELOCK_AFTER_MS` away.

The interesting part is the tension, which is why the rule is a tested function rather than a
constant: **this app sends the user out to system screens constantly** — accessibility, device
admin, battery, app-info — and re-locking on every return would make setup miserable and teach him
to switch the PIN off. Two minutes covers a trip to Settings; it does not cover picking the phone
up again in the evening. `ON_START`, not `ON_RESUME`, so a permission dialog over the app doesn't
count as leaving. A negative elapsed (restore across a reboot, monotonic clock restarted) re-locks:
asking for a PIN that wasn't needed costs seconds, skipping one that was costs what it protects.

Swept and found sound: `BootReceiver` handles BOOT_COMPLETED and MY_PACKAGE_REPLACED and re-arms
both the watchdog schedule and an immediate check; `ProtectionScheduler.ensureScheduled` is called
from `MainActivity.onCreate` as well as boot, so the periodic check is re-armed on every app open
and `KEEP` leaves a running cycle alone.

Not fixed, deliberately, and worth a decision rather than a patch: **the Quick Settings tile is a
PIN-free, guard-free pause.** `QuickBlockTileService.onClick` flips `quickBlockPaused` in one tap
from the shade, refusing only during a Strict session — no PIN (it never opens the app), no
off-switch guard, no wait. Functionally it is the same door as the relapse that started this whole
line of work, and cheaper: one tap instead of a walk through Settings. It only matters if the tile
has actually been added to the shade, which is a manual step, so the owner was asked before
anything is changed — the fix is either to gate it or to drop the tile, and that is his call.

### Swept in the fifteenth "bug hunt" (29 Jul 2026) — schedules, and new-app auto-blocking

Both areas were on the "never swept" list; the owner picked them. Four findings, and the two that
matter are the same shape: **a protection that reports itself as on while doing nothing.**

1. **A TIME schedule can be saved in a state where it can never fire.** The day chips are free
   toggles, so `daysMask` reaches 0; and the window is half-open, so `start == end` contains no
   minute. Either one saves without complaint and sits in the list looking enabled. Save now
   refuses both, and the editor says which. `timeScheduleCanFire` is pure and tested. Deliberately
   does *not* reinterpret `09:00–09:00` as "all day" — guessing intent is a different way to be
   wrong, and the editor suggests `00:00–23:59` instead.

2. **New-app auto-blocking rides on one broadcast with no retry.** `PACKAGE_ADDED` is not
   delivered to an app in the **stopped** state — what a force stop produces, and v1.109
   deliberately stopped guarding Force stop. Also lost if the DB write fails or the process can't
   start. `NewAppWatcher.catchUp` reconciles installed apps against a stored baseline on every
   service start; the receiver stays the fast path.

   **The interesting part is the failure direction.** A wrong baseline blocks every app on the
   phone at once — far worse than the hole. So `newlyInstalled(baseline, current)` returns nothing
   when the baseline is **null** (never looked): first run teaches, second enforces. And the
   baseline is refreshed even while the setting is OFF, or switching it on after a year would treat
   a year of installs as new. Both properties have tests, and the null case is the first one.

3. An exception in the receiver's coroutine had no handler above it — a bare `CoroutineScope`, so
   a database hiccup crashed the process in the background, minutes after an install, with nothing
   connecting the two. Every other background path in this app is wrapped; this one was missed.

4. The receiver upserted a **fresh** `AppRule`, so reinstalling an app silently reset the mode and
   daily limit the owner had chosen. `AppRuleDao` gains a single-row `get` so both paths keep an
   existing rule.

Worth noting for the next sweep: findings 1 and 2 were both reachable from the *editor and the
manifest*, not from the watcher — the file that has absorbed almost every sweep so far. The
watcher is well-trodden ground now; the untested edges are increasingly elsewhere.

### Swept in the fourteenth "bug hunt" (29 Jul 2026) — two sources for one number

Reported, not audited: **five hours of screen time on a day he had not been awake five hours**,
while the hour-by-hour chart beside it looked right. That pairing is the whole diagnosis, and it is
the recurring shape in a new place — **two sources of truth for one quantity**:

| Figure | Built from |
|---|---|
| Day chart (correct) | `queryEvents(dayStart, now)` — the event stream, scoped to today |
| Headline total (inflated) | `queryUsageStats(INTERVAL_DAILY, …)` — Android's pre-aggregated buckets |

`queryUsageStats` returns whole buckets that merely **overlap** the requested range, each carrying
its full period's total, so "midnight → now" can include time from before midnight. It cannot
express a partial day. This file's own `totalMinutesInRange` KDoc already said as much — written
for the coach's yesterday comparison, while the headline number stayed on buckets.

The reach is the part worth remembering: the same figure decided whether a **screen-time goal** was
blown, was the "screen time so far today" line the **coach** reasoned from, and — through the range
variant — fed `ProtectionWatchdog`'s STALLED threshold, where inflation raises a false "blocking has
stopped" alert. A wrong number on a stats screen is cosmetic; the same number wired into four
decisions is not.

Second defect found alongside: three separate copies of the same event walk had drifted — two
summed overlapping app intervals while `sessionStatsToday` merged them. So an hour of the chart
could hold more than sixty minutes. There is now one walk (`walkForeground`) and one merge
(`mergeIntervals`, lifted out of the copy that was right), and the merge is unit-tested — the first
test coverage this file has had beyond `addInterval`.

**The follow-on sweep, which is the part worth copying.** Having moved the *headline* onto events,
the first question asked was "what else reads the broken source?" — and the answer was every
per-app figure on the same screen, including `usedMinutesToday`, **which is what a daily limit is
compared against**. On buckets, yesterday's time could sit in today's per-app figure, so an app
could be blocked in the morning against a limit the owner had not spent. Over-blocking, on the
blocking path, reached by fixing something else. (And the monotonic guard added earlier that day —
"a lower reading is a failed read" — would then have pinned the inflated figure for the rest of the
day: a safeguard is only as good as the number it protects.)

Generalises to: **fixing one consumer of a bad source is half a fix.** The half-done state is worse
than the original, because the corrected number and the uncorrected one sit next to each other and
neither is obviously the liar. Finish by grepping the source, not the symptom.

Recorded so nobody "fixes" it: per-app minutes can now sum to MORE than screen time. Two apps
overlapping for a minute is one minute of phone use and a minute for each app — different
questions. There is a test saying so.

Left alone deliberately: the 30-day history and week-over-week trends still come from bucket
queries. Events do not reach back that far, and a bucket covering a *whole* day is roughly what the
bucket holds anyway — the error is in partial days, which means today, and today now comes from
events even inside that chart.

### Strict Mode's broad rule is gone, and a fix that reached one call site (v1.110)

**The broad rule.** `handleSettingsGuard` ran two rules: the narrow three-screen one v1.109
settled on, and — during Strict — `byClass || guardScreenIsDangerous()`, where the *kind* of page
was enough. Between `STRICT_GUARD_HINTS` (`accessibilit`, `installedappdetails`,
`appinfodashboard`) and `GUARD_TEXT_MARKERS` (which held `accessibilit`, `uninstall`, `force stop`
alongside the device-admin words), that bounced the entire Accessibility section and every app's
App-info page. The owner reported it as "Strict blocks the whole Settings".

The comment defending it said over-blocking is affordable in Strict because a session is opt-in and
ends by itself. That is wrong in the way this file keeps finding: **Strict is the mode he runs when
he most needs the phone to still work**, so it is the worst place to be careless, not the safest.
Both modes now run the same three-screen rule. What Strict keeps is *unconditionality* — it bounces
regardless of `OffSwitchGuard.armed`, so neither the guard switch being off nor an open unlock
window stands it down — not a wider net.

Both constants are **deleted**, per the v1.106 lesson. The one case they genuinely covered that the
narrow rule did not is a device-admin screen with a generic MIUI class name; that is now
`AdminScreens.MARKERS` — device-admin wording only, out in `data/` where it can be tested, and its
test asserts what it must **not** match (the accessibility list, an App-info page), because that is
the property that was lost rather than the one that was missing.

`guardScreenText()` is now read once per decision and threaded through the three checks, which used
to walk the node tree up to three times for one event, at a budget of 800 nodes.

**The fix that reached one call site.** `StrictModeScreen.ensureDeviceAdmin` was its own copy of the
ADD_DEVICE_ADMIN launch and never learnt what `toggleDeviceAdmin` learnt in v1.108: to stamp
`AdminPrompt.requested()`. So starting a Strict session could not switch uninstall protection on —
the v1.107 bug, alive on a second path for two releases, invisible because the first path worked.
It now delegates, and `toggleDeviceAdmin` is split into `enableDeviceAdmin` / `disableDeviceAdmin`
so no caller can reach the dangerous direction by passing a state it didn't check.

Worth generalising: **a duplicated call site is a place a fix does not reach.** When a bug is fixed
by teaching one function something, grep for other launchers of the same intent/screen before
closing it — the copy will not fail loudly, it will just still be broken.

### The reporter answered the standing question with "no" (v1.110)

The first real bug report the owner sent arrived carrying version, SDK and device — and **nothing
else**. No settings context, no block log. The two things built specifically to diagnose the
flashing were both absent, and the report looked exactly like a healthy quiet one.

`BugReportSender.appContext` was a single `runCatching` around a ten-call `mapOf(...)`, defaulting
to `emptyMap()`. **One throw among ten reads emptied all ten, silently.** That is the pattern
three sections down — *"if this itself broke, would anyone ever know?"* — reproduced inside the
tool built to answer it, which is the funniest and worst possible place for it.

Fixed by reading each field under its own `runCatching` and emitting `fieldErrors=n` when any
failed, and by making `BugReport.body()` always print the Recent-blocks section with an explicit
`(none recorded …)` marker. **A missing section and an empty section render identically in a
GitHub issue and mean opposite things** — "no cover appeared" versus "the log is broken" — and
that ambiguity cost a whole round trip while the flashing went undiagnosed.

Generalises to: *any* aggregate built from N independent reads on an error path should degrade to
N−1 fields, never to zero, and should say how many it lost. And a diagnostic that can render
"nothing" must distinguish the two nothings.

Still open after this: **the flashing itself.** Six guesses at the guard each broke something the
owner needed; no seventh guess is in v1.110 on purpose. The block log is the instrument, and this
release is what makes it arrive. Note the owner changed phones — Xiaomi 25080RABDG on **SDK 36**,
where the earlier reports were 2312DRA50G on SDK 35 — so a new OEM build is itself a live
candidate and the log lines should be read against that.

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
- **Grep for the primitive, not the feature.** All three clock findings were local
  re-implementations of something `SessionClock` already did safely, so no search for the *concept*
  found them. A search for the raw ingredient (`System.currentTimeMillis()` against a stored
  number) found the third one immediately — **this is the highest-yield move in the file so far.**
  Generalise it: pick a primitive the code gets wrong somewhere, and grep every use of it.
- **When one instance is found, enumerate all of them before fixing.** Sweep 1 fixed the keyword
  lockout alone; sweep 4 then found the adult gate, and sweep 5 found six more. Three releases for
  one mistake. Had the first sweep grepped the primitive, all eight would have gone out together.
- The owner is non-technical: don't ask him which code to change. Ask only about behaviour
  he can judge (e.g. "should two opens ten seconds apart count once or twice?").
