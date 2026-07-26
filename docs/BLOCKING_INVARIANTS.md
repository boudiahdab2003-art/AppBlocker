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
  - `aboutUs()` **fails closed** (an unreadable screen answers "ours") for the invariant-6 reason:
    a wrong yes is visible and waitable, a wrong no is invisible and total.
  - **Two under-blocking risks this created, both still live and worth a future sweep.** Android
    only builds nodes for *rendered* rows, so our row is genuinely absent from a long scrollable
    list until scrolled to — caught, in theory, only by the content-event re-check falling through
    to the text path. And the text walk is budget-capped; it was raised to 800 nodes / 8000 chars
    because a settings list can be long, but a cap is still a cap. Neither is covered by a test,
    because the watcher has none.

### Not yet swept

- the UI's own live state: `resumeTick` re-reads, and the several screens that cache
  service/prefs state in `remember` blocks. Sweeps four and five touched only the adult-pack gate;
  the pattern (a `remember` holding a decision that time or the service can invalidate) is
  everywhere and is worth a sweep of its own. **Best remaining candidate.**
- `UsageTracker` beyond `addInterval`: the caching layers (`usedTodayCache`, the per-day
  memoisation) and `sessionStatsToday`'s interval merging.

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
