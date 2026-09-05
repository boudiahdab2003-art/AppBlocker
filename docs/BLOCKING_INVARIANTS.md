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
10. **A Strict deadline may only move later — and a finished one may never move at all.**
    `focus_state` has four writers: `FocusViewModel.start`, the two that zero the row when a
    session expires (`FocusViewModel`'s ticker and the watcher's `focusClearRunnable`), and
    `FocusDao.extend`. That last one exists so "add more time" is possible, and it is written as a
    single `UPDATE … SET end = end + :delta … WHERE :delta > 0 AND (end > 0 OR realtimeEnd > 0)`
    rather than a read-modify-write in Kotlin. The reason is the race with the two clear paths, and
    it is asymmetric: losing it one way drops an extension the user asked for; losing it the other
    way **resurrects a session that has already ended and unlocked their blocks**. Doing the
    arithmetic inside SQLite closes the window instead of narrowing it, and `+ :delta` (never
    `= :newEnd`) makes shortening inexpressible rather than merely absent. Both end columns move
    together or neither does — `SessionClock` reads the monotonic one within a boot and the
    wall-clock one after a reboot. The start anchors are never touched: the post-reboot path caps
    remaining at `end - start`, so raising the end raises the cap by the same amount, while
    re-anchoring the start would shrink it and thereby *shorten* a session. Proven by
    `FocusExtendTest` (the write) and `SessionClockTest` (the choice of fields).
11. **An empty or failed answer is not data.** Every package/asset query in this app swallows its
    errors into an empty collection, and "no browsers", "no launcher", "no adult words" is never
    true. Adopting one silently fails *open*. Adopt a result only when it is non-empty, keep the
    previous value, and record the failure (`refreshPackageSets`, `WebContentFilter.get`).

    **Update, 26 Aug 2026: it applies to an answer that has not ARRIVED yet, and that was the one
    place it had never been applied — the rules themselves.** `rules` is filled by a Room Flow
    collected in `onServiceConnected`, so between binding and the first emission it is an empty
    map. `decideBlock` read that as "nothing is blocked" and `handleAppBlock` took the "take the
    cover down" branch: every app open, every cover removed, for as long as the database took.
    Every rebind hits it — boot, update, revive — and **Xiaomi's Second Space rebinds the service
    on every switch**, which is why the owner reported it as *"slow after switching between
    spaces"*. It was not slow. There was no blocking. `RuleSnapshot` keeps a plain set of blocked
    package names, written on every emission and read synchronously on connect, and `rulesLoaded`
    stops any cover coming down before Room has spoken. The shape to grep for: **a field whose
    empty value is indistinguishable from its not-yet-populated value, read by something that
    treats empty as an answer.** An asynchronous source has that shape by construction.
12. **A protection may not hang on recognising one vendor's spelling.** Invariant 11's sibling,
    and the same failure with a narrower cause: not an empty *answer*, but a lookup that only
    knows one name for the thing it is looking for. Website blocking read the address bar as the
    single literal `"$pkg:id/url_bar"` — Chrome's — so in Brave, Edge, Samsung Internet or
    Firefox it found nothing, and "no address" reads as "no site to block". A whole layer, off,
    in every browser but one, for the app's entire life (13 Aug 2026). Where a vendor string is
    unavoidable, match a *family* (`isOmniboxId`, suffix-matched) and put a vendor-agnostic tier
    behind it (an editable, host-shaped node). Same rule as 4, applied to a lookup instead of a
    window read: a failed identification is a failed measurement, never a confident "no".

    **Update, 14 Aug 2026: the vendor-agnostic tier was itself vendor-shaped.** "Any *editable*
    host-shaped node" assumes the address bar is a field you can type into — true of Chrome, and
    false of Mi Browser, whose address is a **label** in a bottom bar that opens a separate editor
    when tapped. So all three tiers missed it and every site block was silently off there, found
    only because the owner screenshotted instagram.com open with Instagram blocked. The lesson is
    not "add another id": it is that **each fallback tier inherits an assumption from the browser
    it was written against**, and the assumption gets less visible as the tier gets more general.
    Tier 4 drops editability and replaces its safety argument with two structural rules — the node
    must not be inside a `WebView` (that is the page, not the chrome) and the candidates must agree
    on exactly one host (a toolbar shows one address; a suggestion list shows several). When
    writing tier 5, ask what tier 4 quietly assumed.

    **Tier 4 is verified, which almost nothing in this area is.** After v1.130 the owner's phone
    reported Mi Browser as *read here* rather than *assumed* on the diagnostics screen — the same
    screen that had been hiding the hole by merging the two. So the mechanism works on at least one
    real label-style toolbar, and `com.miui.browser`'s seed entry is now backed by a measurement
    instead of a family resemblance. The OEM browsers added beside it (Huawei, Oppo/OnePlus, Vivo)
    are **not** verified and remain exactly the kind of claim this file keeps warning about.

13. **One set, two consumers, opposite failure costs — make it two sets.** `browserPackages` fed
    both the web scan and the "block unsupported browsers" switch, and those want opposite
    mistakes: scanning must over-include (a browser left out is silently unfiltered, invisible),
    blanket-blocking must under-include (an app wrongly included is *blocked outright*, which made
    Coinbase, WPS Office and SHAREit unusable). One list cannot be tuned for both, and three
    attempts at tuning it produced an under-block and then an over-block. Split it:
    `findBrowserPackages` stays generous, `findRealBrowserPackages` is strict. **And decide the
    strict one by self-declaration, not inference** — `CATEGORY_APP_BROWSER`, the default-browser
    role, a known package. Both inference attempts ("handles an https link", "handles one with no
    host restriction") were defeated by ordinary apps on the owner's phone, and which of the two
    reasons applied was not decidable off-device.

14. **A guard on a page must know which direction the user is moving.** `handleSettingsGuard`
    bounced AppBlocker's own accessibility page whenever the guard was armed, and never asked
    whether the service was currently *off* — so switching it **on** started the watcher, which
    immediately saw its own off-switch page and fired `GLOBAL_ACTION_HOME` at somebody who had
    just turned protection on. It could only ever fire in those seconds (before that the service
    is not running to see anything), which is why it survived so long: the owner's phone was set
    up before he armed the guard, and it took a fresh install on his tablet to surface it.
    **The blast radius was every new user** — the accessibility disclosure sends all of them to
    that page — and nobody would report it; they would conclude the app was broken. Fixed with
    `OffSwitchGuard.justEnabled`, scoped to that one screen: uninstall, device-admin removal and
    the App-info page during Strict are not part of enabling the service and keep bouncing
    throughout. The general shape to watch for: **a protection that fires on a location rather
    than on an action will eventually fire on the user doing the right thing.**

15. **A protection gated on a package list protects only the phones on the list.** Invariant 12
    again, one level up: not a lookup that knows one spelling, but a *guard* that never runs.
    `handleSettingsGuard` returns early for any package outside `GUARD_PACKAGES`, and
    `uninstallConfirmation` does the same for `INSTALLER_PACKAGES` — and both lists held exactly
    three names: AOSP's, Google's and MIUI's. So on a Samsung, Oppo, Vivo or Huawei, Strict Mode's
    uninstall guard saw a package it did not recognise, answered "not a threat", and the uninstall
    went through mid-session. Nothing on screen said the guard had been skipped, and the owner's
    own phone is the one brand that was covered, so no report could ever have surfaced it (14 Aug
    2026). Both lists now live in `service/GuardPackages.kt` with the OEM installers added and
    `GuardPackagesTest` pinning them — including the subset rule the extraction exposed: an
    installer missing from `GUARD` is unreachable, so it is dead code that looks like protection.
    **The direction that stayed narrow matters as much.** The OEM battery/security centres
    (Samsung Device Care, ColorOS safecenter, Huawei systemmanager) were deliberately *not* added:
    none of them hosts our accessibility page, our App-info page or a device-admin screen, so they
    buy nothing — while exposing `deviceAdminRemoval`'s "deactivate" text fallback to pages that
    are nothing but app names and toggles. Widening *what is read* is cheap; widening *what counts
    as dangerous* is what made Strict "block the whole Settings" for seven releases.

16. **Strict Mode must lock every *weakening* control — including one whose effect only lands
    after the session ends.** Profile's "Guard the off-switch" row was left usable during Strict on
    the reasoning that "the guard can only be turned *on* or have a wait started, and Strict guards
    these pages by itself regardless of this row". Both halves are true and the conclusion is still
    wrong: a request served before the session, or during it, could be *completed* mid-session, and
    lowering the guard then leaves it disarmed the instant Strict expires. Nothing looks wrong while
    the session runs — `handleSettingsGuard` short-circuits on `strict` and keeps bouncing the pages
    — so the session silently doubles as the two-hour wait and the off-switch is simply open when it
    ends. The escape was *pre-arranged* rather than prevented, which is worse than one you have to
    sit through. Now decided by `OffSwitchGuard.tap`, which refuses both weakening directions during
    Strict and still allows `TURN_ON`, because refusing to let someone *arm* a protection is the
    v1.127 mistake (invariant 14) in a new place. **The shape to grep for: a control judged safe by
    what it does while a session runs, rather than by what it leaves behind when the session ends.**

17. **A diagnostic flag must mean exactly one thing.** `BlockLog`'s `rootOk` was documented as
    "the cover was raised from a stale cache" — a bug — and computed as
    `rootInActiveWindow?.packageName == packageName`, which is equally false when the tree cannot
    be read at all. But an unreadable tree is a *deliberate* reason to block (invariant 1) and
    happens routinely mid-transition. So the one flag meant both "this cover landed on the wrong
    app" and "this cover was correct", and report #5 — *"the app blocked claude idk why"* — arrived
    with two `rootOk=false` entries that could have been either. **An instrument that cannot
    distinguish the bug from the intended behaviour is not evidence**, and worse than none, because
    it invites a fix aimed at whichever meaning the reader assumed. Now three values
    (`match`/`other`/`blind`), and every cover also records a `BlockWhy` code saying which layer
    raised it — the second thing that report needed and could not say. The shape to watch for: a
    boolean whose `false` branch is reached by two paths with opposite meanings.

18. **A cover belongs to the layer that raised it, and only that layer may take it down.** Three
    different layers raise covers — the app-block path (*is this whole app blocked?*), the page
    scans (*is this page blocked?*), the guard and the purchase check — and until 18 Aug 2026 only
    the first one's covers had an owner. `overlay.isAppBlock` was the whole of it, so a page or word
    cover was nobody's property and three separate places felt free to remove it:
    `handleAppBlock` on `reason == null` (the browser is not a blocked *app*, so that fires on every
    window event it emits), the stray-window guard in `onForegroundChanged` (which only protected
    app covers, so a background package's event adopted the foreground and the cover went with it),
    and — the invisible half — the web scan's `lastWebText` dedup, which **outlived the cover it was
    taken for**: once a wrongly-removed page read as "already handled", it was never covered again.

    The reported symptom was Shorts opened in a *browser*: that cover was keyed
    `CoverGate.SHORTS_KEY`, which is the YouTube-player scan's ownership marker (`shortsCovering` is
    derived from it alone), so `scheduleShortsScan` removed it as a stray on every content event
    while the web scan re-raised it — flashing several times a second with the page usable in
    between. Two owners, one key: the invariant-8 mistake wearing the recurring "two sources of
    truth" shape. Fixed by giving every cover an owner (`BlockOverlay.ownerPkg`), by making the two
    removal rules pure and tested (`CoverGate.ownedByScan`, `CoverGate.strayWindowEvent`), and by
    keying the browser Shorts cover as the page block it is.

    **The general shape: `isAppBlock` was doing double duty as "is this cover important".** Ask of
    any protection whether the thing that can undo it is answering the same question that raised
    it — and note which direction the asymmetry runs. A blocked *word* survived all three of these
    because it also arms a 30-minute lockout, so the app-block path starts agreeing; a blocked
    *site* arms nothing, which is exactly why the fragile layer was the one nobody could see
    failing.

19. **A lock belongs on the write, not on the button — and "weakening" means what actually gets
    weaker.** Two halves of one change (20 Aug 2026), and the second is only safe because of the
    first.

    *The owner's request:* let a blocked word be removed mid-Strict **when it is a website that is
    already blocked** — `instagram` while the Instagram app is on the block list, whose
    `SOCIAL_DOMAINS` entry blocks `instagram.com` regardless. Invariant 16 says lock every
    weakening control; the question is whether this one weakens anything. It does not, and the
    reason has to be checked in invariant 16's own terms — *what does it leave behind when the
    session ends?* The coverage cannot be retracted during the session (an app can't be un-blocked
    mid-Strict, the Blocklist/Allowlist chip is frozen, `enforcing` short-circuits on Strict so no
    pause reaches it), and after the session the removal is a single tap anyway — so unlike the
    off-switch guard, nothing is *pre-arranged*. `StrictEdits.coveredBy` decides it, and the
    direction of the test is the whole rule: the **site** word must match whole-word inside the
    **user** word, which is what proves the site block is the broader one. `instagram` covers
    `instagram.com`; it does not cover `insta`, which matches addresses `instagram` never would.
    Sound *and* complete — if the site word does not sit inside the user word, then the user word
    is itself an address the removal would unblock — and pinned against `checkUrl` in
    `StrictEditsTest`, so the rule and the matcher cannot drift.

    *What made it safe:* until this change the **only** thing stopping a mid-Strict removal was a
    disabled button, and both editors already walked around it. `BlockEditorScreen` stages its
    lists and diffs them on Save, so a word added on the other screen after that editor was
    opened-and-edited read as a deletion; `AppListViewModel.commitQuickBlock` diffs the staged
    selection against live rules, so an app blocked by `NewAppWatcher` after the editor opened read
    as an un-tick — and **an upsert that clears `isBlocked` removes protection exactly as
    effectively as a delete**. Both guards now live in the writers (`setKeywords`,
    `commitQuickBlock`), which read the Strict row fresh and refuse the weakening direction
    whatever set they are handed. That is what makes a *conditional* exception trustworthy at all:
    a guard a caller can bypass cannot express "except when".

    **The shape to grep for: a rule enforced where the user taps rather than where the data
    changes.** Every staged editor in this app is a place where those are not the same moment.

    This corrects the twentieth sweep below, which enumerated the deletes and concluded the UI gate
    held. It missed both staged-write paths, and `purgeTemplateWordsOnce` — a third ungated
    `dao.delete` caller. That one is one-shot, flag-guarded and has already run, so it is left
    alone; but "every caller is gated" was not true when it was written.

20. **An address list may only be matched against an address — and "no address" is two different
    answers.** Reported 20 Aug 2026: *opening Chrome*, before a single letter was typed, raised
    "That search or page looks like adult content" and locked the browser for thirty minutes.

    *The first half.* `adult_domains.txt` is a list of hostnames and `adult_keywords.txt` says in
    its own header that it matches "a URL or search text" — it is deliberately a plain substring so
    glued forms like `freeporntube` hit. Both answer **which site is this?**, and `check` was
    running them over the whole page text, which answers a different question: **what does this
    screen mention?** A browser start page is built out of the user's own history — most-visited
    tiles, the suggestion list, recently closed tabs, the Discover feed, and (measured) Google's
    *trending searches*, which are not even his. One word anywhere in that lot read as a visit.
    This is v1.105's "mentions us was never the right signal" in a third place, and the word pack
    had already learnt it from the other side when it dropped "pornography"/"porno" as the
    vocabulary of *talking about* porn. The pack keeps the page-text job, because it is curated and
    whole-word matched for exactly that; the two address lists now match the address.

    *The second half, which is the deeper one.* `check`'s `url: String?` had `null` meaning both
    *"the toolbar is hidden, I could not read it"* (fullscreen video — must fall back to page text
    or scrolling becomes a bypass) and *"the bar is right there and empty"* (a start page — there
    is no page at all). Only the first meaning existed. This file listed that exact parameter under
    "Not yet swept" — *where else does a null answer mean "no" instead of "don't know"?* — so it
    was a predicted bug that arrived before anyone went looking. `BrowserAddress` is the third
    answer: `At` / `Blank` / `Unreadable`, where **`Blank` is a successful measurement and
    `Unreadable` is a failed one**, and only the failed one may fall back.

    **Two things here were only findable by driving a real browser, and both would have shipped a
    fix that changed nothing.** First, on Chrome's new tab page the toolbar can carry *no*
    `url_bar` node at all — the address moves into the page as a fakebox (`isStartPageId`), which
    reads as "no bar", i.e. the old behaviour. Second, and worse: when the bar *is* there and
    empty, Chrome hands its placeholder out as the node's own `text` **and** `hintText`
    ("Search or type web address"), and tier 1 accepted it before anything asked whether the bar
    was empty. So the app had been treating the placeholder as the site the user was on — it was
    sitting in `diag_host` on the diagnostics screen the whole time, which is the second time that
    screen has been the thing that could have said so. Emptiness is now decided **before** the text
    is accepted. The general shape: *ask whether the field is empty before asking what it says* —
    a placeholder is text, and every "is there a value here" test that reads the value first will
    eventually accept one.

    Same tier discipline as invariant 12 governs who may answer `Blank`: only the id-matched tiers,
    because tiers 3 and 4 identify the bar *by its text* and to them an empty bar and no bar are
    the same observation. And the memory is bound by it too — `rememberedBrowserAddress` forgets on
    `Blank` instead of answering from the last page, which is what used to make a new tab inherit
    the site just left for ten minutes.

    **Reported again 21 Aug 2026, from the other end of the same node — *"blocking my browsers
    without opening any blocked link"*, while typing a search in Chrome.** The fix above took the
    tiles, the suggestion list and the feed away from the matcher. It left the address bar itself
    trusted whole, and Chrome does **inline autocomplete**: type `yo` and the node's text is
    already `youtube.com`, with `utube.com` selected. The completion comes out of his own history
    — it is the same "where you have already been" the start-page fix was about, moved inside the
    one node that fix still believed. Read whole, the site layer answered *"which site is this?"*
    with a site he had never opened, and the cover came up mid-search.

    So, two rules, and they are the same rule pointing in two directions:

    - **A field being edited reports the phone's guess as well as the user's input — match the
      part the user typed.** `typedPortion` slices at the selection, and only on the shape a
      completion actually has: a selection that *starts after something typed*. A selection
      starting at 0 is a select-all (what Chrome does when you tap the bar on a loaded page) and
      is the real address. No selection, a collapsed cursor, indices that don't parse, a browser
      that reports none of it — all keep the full text, because that is the blocking direction.
      The general shape, next to invariant 20's own: *ask whether the field is empty before
      asking what it says* — and then ask **how much of what it says is the user's**.
    - **A tier that can name the address bar outranks one that only recognises it by shape — in
      both directions.** The rule above settled who may *answer* `Blank`; nothing stopped tiers 3
      and 4 from **overriding** one. The walk carried on after `blankBarSeen` was set, and
      `extractBrowserAddress` tests `read.text` first, so a single host-shaped label in the chrome
      — a bookmark row, a "recently closed" entry, a most-visited tile — put the start page back
      in front of the site layer through a different door. `looseAddress` is where that is now
      decided, and deciding it *there* rather than in `extractBrowserAddress` is what carries it
      into the undebounced path: `extractSettledBrowserUrl` reads only the text and had never
      learnt about `Blank` at all.

    A fix landing in one half of a two-path design is the third thing to take from this. `check`
    got `BrowserAddress` and `scanBrowserUrl` did not, because the fast path takes a `String?` —
    the same parameter, with the same two meanings crushed into it, one call away from the file
    that had just been corrected.

21. **State that must outlive the process may not live in the process.** Reported 21 Aug 2026
    (report #6): *"after the update I got a blocking screen idk why"* — one `why=guard` line, 26
    seconds before he wrote it, with `protection: PAUSED` proving v1.133 had just landed.

    The off-switch guard reads an installer screen naming AppBlocker as *"do you want to uninstall
    this app?"*, matched by **package** rather than wording so it holds in any language. Installing
    an update opens the same installer and shows the same name, so `InstallPrompt` stands the guard
    down while we know we asked for it — five minutes, chosen (its KDoc says so) to cover the *"app
    installed / Open"* screen that follows the install.

    It never reached that screen. The stamp was a `@Volatile private var`, and **installing our own
    APK is Android killing our process.** The service came back with the field at 0, read the
    post-install screen as a removal, covered it and fired HOME. The exemption for an install was
    destroyed by that install: the one event it was written to survive.

    The shape to grep for is *knowledge with a lifetime shorter than the thing it describes*. Two
    places in this app already carry the lesson and say so — `SettingsStore.setAutoInstalled` uses
    `commit()` rather than `apply()` because "the process is about to be killed", and `UpdatePause`
    writes its "durable intent, written BEFORE anything is attempted" for the same reason. This was
    the same situation with neither. It is now prefs + `DeviceBoot.count`, read through the
    `SessionClock` rule (monotonic within a boot; a boot change means expired), with the decision
    split into a pure `openAt` so it can be tested at all.

    **And the asymmetry is deliberate: `AdminPrompt` stays in memory.** Activating device admin does
    not replace our APK, so the process that stamped the request is still the one being asked.
    These two read as twins, so that difference is written at the join or it gets tidied away.

    *The second half, which is invariant 4 wearing different clothes.* Even with the stamp gone, one
    more thing had to fail: `uninstallConfirmation` separates install from uninstall by the
    **activity's** class name, and `AccessibilityEvent.getClassName()` means two different things —
    a window-state event carries the activity, a content or scroll event carries the *view* that
    changed. Three of the guard's four call sites are the latter, so the class it was handed read
    `android.widget.FrameLayout`, the install test found nothing saying "install", and the decision
    fell through to "an installer screen naming us" — which a post-install screen satisfies exactly
    as well as a removal dialog. **A reading that says nothing was resolved in the bouncing
    direction.** `namesAnActivity` names that difference and the watcher now remembers the last
    activity class per package, which is what `pendingClassName` had already been doing one path
    over — *"the re-read can confirm the package but can never recover the class"* — for the purchase
    check and nothing else.

22. **The gate and the evidence must describe the same window.** Reported 21 Aug 2026 (report #7):
    *"i dont know why it blocked"* — `26s ago guard why=guard`, raised over a **Claude
    conversation** while the owner was reading this app's own `strings.xml` in it.

    `handleSettingsGuard` builds one decision out of two different windows. The gate — `if (pkg !in
    GUARD_PACKAGES) return false` — is the **event's** package. Everything after it is read from
    `guardScreenText()`, which is `rootInActiveWindow`: **whatever is really on screen**. Nothing
    tied them together, and the stray-event call sites said so as though it were a safety
    property: *"it reads the real screen rather than trusting this event's package."* It does both.
    The real screen supplies the evidence; the event still supplies the gate.

    So a background Settings window announcing itself (it lives in Recents, and MIUI is generous
    with these) opened the gate, and the text came from the app actually in front. The
    accessibility-page test is `text.contains("appblocker uses this to detect")`, chosen because
    Android renders our own service description *"there and nowhere else"* — true of Settings, and
    not true of a chat window in which that sentence is being quoted. **This is v1.105's "mentions
    us was never the right signal" a fourth time**, and the first time it has bitten the guard.

    The fix is invariant 1, which every other cover path already honours and this one never did: a
    window tree that positively names a *different* package is a contradiction, so decline.
    Unreadable stays "can't tell" and changes nothing — `guardScreenText` already answers `""`
    there, and the content-event re-check follows with a populated tree.

    Report #5 — *"the app blocked claude idk why i was in claude code"* — is very probably this,
    from before the log could name the rule. It was filed as unexplained and stayed that way.

    *And the log could not say which screen.* `why=guard` was one code for four: our accessibility
    page (v1.127's shape), an uninstall confirmation (report #6), a device-admin removal (v1.107's),
    and App-info during Strict (force-stop protection). Four failure modes, four opposite fixes, one
    label — so report #7 could not point at any of them. That is the ambiguity `BlockWhy` was
    invented to remove for report #5, applied one level too shallow, and it is invariant 17 again.
    Now `guard-service` / `guard-uninstall` / `guard-admin` / `guard-appinfo`, with `guard` kept for
    entries written before the split. **`BlockLog` truncates `why` to 16 characters**, which is a
    quiet way to rebuild the same ambiguity, so the vocabulary is length-checked in a test.

23. **The way out of a cover must match the scope of what it covered.** Asked by the owner as a
    how-do-I, which is usually how a design fault arrives: *"when i get blocked from chrome for
    opening insta how can i correct it if i keep getting blocked and change the page"*.

    A page cover covers **one page** inside an app that is not blocked. `scanWebContent` says so
    where it decides the lockout — *"A blocked WEBSITE is gentler: cover the page so the site stays
    blocked every visit, but don't lock the whole browser"* — and a site hit adds no lockout, so
    the browser really is not locked. Then "Got it" fired `GLOBAL_ACTION_HOME` and threw him out of
    it anyway, onto a home screen from which the only way back was the tab still showing the
    blocked site. The loop is the app's, not his.

    So the enforcement was scoped and the exit was not, and the exit is what the user actually
    experiences. It gave away at dismissal exactly the distinction v1.132 split `word` from `site`
    to preserve. `CoverGate.exitFor` decides it now: a page cover goes BACK, an app cover goes
    HOME, and a Shorts cover goes BACK **and then** HOME.

    ⚠️ **That third answer was an unexplained exception for a year, and the exception was a bug.**
    This entry used to end "and the Shorts cover keeps its own path" without ever saying what
    scope that path matched — and the path was `overlay.remove(); GLOBAL_ACTION_HOME`. A Shorts
    cover covers a *surface inside* an app that is not blocked, so HOME is the wrong scope for the
    same reason it was wrong for a page. Worse, it was actively harmful: HOME fires
    `onUserLeaveHint()`, which is precisely the signal that hands a playing video to a
    picture-in-picture window, so "Got it" on a Short *created* a floating Short over the launcher
    — reported 30 Aug 2026 as *"the short even when you are in home screen you can watch the
    short"*. HOME also only backgrounds YouTube, leaving the reel on its back stack, so re-opening
    resumed onto the same Short. One wrong action, both halves of the complaint.

    The dismissal is now a `when` over the `Exit` enum, which the compiler requires to be
    exhaustive — so a fourth kind of cover cannot inherit HOME by falling through an `else`, which
    is exactly how this one did. **The general form: an exception in a rule that never states what
    it is an exception *to* is not an exception, it is an unexamined case.**

    Two things worth keeping straight for whoever edits this next:

    - **The old "no BACK" note is about a different moment.** `scanWebContent` records that BACK
      *"races with the just-launched activity and dismisses it"*. True at **block** time, against an
      activity still opening. The exit fires on a deliberate tap seconds later with nothing
      launching. Both comments now say which moment they mean.
    - **Failure is detected, not predicted.** Android will not report whether BACK went anywhere,
      and a page opened straight into a fresh tab has no history. So a second "Got it" in the same
      app within `BACK_RETRY_MS` leaves instead — one extra tap in the worst case, against a loop
      with no exit at all.

    Not a bypass in either direction: the scan re-runs on whatever page BACK lands on, and another
    blocked page covers again.

24. **An alert whose whole job is to be noticed may not inherit the habits of routine ones.** Asked
    for as *"when the accessibility is turned on and not working can you make the notifications
    much more persisting and floating so i see them"* — the STALLED state, the worst failure this
    app has, and one he has actually hit (`foundDead: 1` in report #7).

    v1.132 built the alert. Three things then stopped him seeing it, and every one was a sensible
    default borrowed from a message that means something far less serious:

    - `setOnlyAlertOnce(ongoing)` — for this alert that is `true`, i.e. *never sound or peek for
      this id again*. Right for a routine ongoing notification, and it capped this one at **one
      float, ever**.
    - `stalledPosted`, an in-memory *have I posted this* boolean, which stopped it re-posting at
      all within a process. The question that matters is *when did he last see it*.
    - `force`, passed by `AppRoot` on every app open precisely to defeat throttling, was declared
      `@Suppress("UNUSED_PARAMETER")` and read by nothing.

    Two platform facts belong with it. **A channel cannot be made louder once it exists** —
    `createNotificationChannel` ignores an importance raise — so sharing one channel across all
    three protection alerts meant a single downgrade, by him or an OEM, would silence this one
    permanently with nothing on screen to say so; a new id is the only repair. And **re-posting an
    id that is already showing updates it silently**, so floating again means cancel-then-post.

    The shape to carry forward: *when an alert's purpose is different in kind, not degree, it needs
    its own channel, its own throttle and its own alert-once rule.* Sharing them is how the loud
    one inherits the quiet one's manners.

    **And one thing the app cannot do, which is stated in the app rather than worked around.** MIUI
    keeps "Floating notifications" as a separate per-app permission, off by default. While it is
    off, none of the above floats. `DeviceVendor`'s Xiaomi advice says so and `RepairScreen` links
    to the page — the same honesty as *"Android doesn't let an app switch its own blocking back
    on"*, which is already on that screen.

20. **A grace released by one kind of event will meet a user who never generates it.** The
    post-"Got it" dismiss grace suppresses a re-cover so the departing app's straggler events
    cannot re-block during the transition. It was released by exactly one thing: a *different
    package* coming to the foreground. That is sound reasoning about the case it was written
    against — HOME lands, another app is in front, nothing is left to absorb — and it is silent
    for the case the owner actually relapsed through on 25 Aug 2026: **HOME is swallowed on
    HyperOS, he never leaves the app, he simply opens the next screen of it.** Same package
    throughout, so the release never fired and `DISMISS_GRACE_STUCK_MS` ran its full eight
    seconds with him reading the page it was meant to cover.

    **And a fix elsewhere quietly turned the rare case into the normal one.** v1.135 made a page
    cover's "Got it" step BACK rather than HOME, deliberately keeping the user *in* the browser
    (it was a fix for being thrown out of the browser and straight back onto the blocked page).
    From that release on, "still in the same app" was the expected outcome of every website
    block — so a window written for a swallowed HOME started applying to every page block, and
    both web scanners sat out eight seconds inside the browser where the block had just happened.
    Nobody would report that: it is an under-block, and the whole point of this file is that he
    cannot see those.

    The rule is now released by *movement*, not by a package change alone: `graceSpentBy` also
    takes where the user was and where they are now — the window class for an app cover, the host
    for a page cover — and `currentDest()` picks the matching unit so a class name is never
    compared against a host. Null on either side never releases, so "we could not tell" cannot
    degrade into releasing on every event, and the genuinely stuck case still holds the full
    window. `scanBrowserUrl` reads the address *before* consulting the grace, because on a page
    cover the new host is the only evidence available and it used to sit behind the very check it
    had to answer.

    **The general shape, and it is invariant 14's twin:** invariant 14 is a protection that fires
    on a *location* rather than an action, so it eventually fires on someone doing the right
    thing. This is a protection *released* by one specific event, so it eventually fails to
    release for someone doing the wrong thing. Both come from naming the trigger after the
    scenario in front of you. Ask of any timed suppression: **what ends it, and what is the user
    doing if that never happens?**

25. **A cost paid per node is paid four hundred times, and a wait that protects one exit is
    charged to every exit.** Two shapes, one lesson: on this app's hot path, the unit a cost is
    charged in matters more than its size.

    `insidePage` asked "page content or the browser's own furniture?" by walking *up* to thirteen
    parents, each a binder round trip into the browser — reasonable-looking code, and correct.
    But it ran per candidate node inside a four-hundred-node walk, on every content and scroll
    event, and only in the tier no browser but Mi Browser reaches. The walk already descends from
    the window root, so the answer was known before the question was asked; carrying one boolean
    down the queue replaced up to ~5,200 IPCs per window with a field read. **The bug was not the
    climb. It was climbing inside a loop that already knew the answer** — and it stayed invisible
    because it is not wrong, only expensive, and nothing here measured expense.

    Retiring the thirteen-hop cap also closed a silent under-block: running out of hops answered
    "inside the page", the refusing direction, so a toolbar label nested deeper than thirteen
    levels was discarded and its browser read as unreadable. A bound chosen for cost had become a
    correctness rule nobody had reasoned about. `PageScope` is pure and tested; `soleHost` still
    does the actual guarding.

    The second shape is `DISMISS_GRACE_STUCK_MS` again, from the other side. Eight seconds is the
    right price for a swallowed HOME. It was charged to BACK too — which never makes that trip —
    so the exit that keeps the user in the app deliberately paid the window built for the exit
    that fails to leave it. Note what the fix is *not*: the constant did not move. What changed is
    that the grace now knows which move was actually made, `viaBack` recorded from the branch that
    fired rather than inferred from the counter key, because a page cover falls back to HOME once
    BACK has failed and then needs the long window like anything else.

    Ask of any per-event cost: **what is the unit — once per block, or once per node?** And of any
    wait: **which exit was this priced for, and who else is being charged it?**

    > **Numbering note, for whoever sweeps next.** This list has *two* invariants numbered 20 —
    > the address-list one above and the dismiss-grace one that opened this section — and 21-24
    > sit between them. Not fixed here: "invariant 20" is referenced from five source files and
    > three places in this document, meaning different ones, so renumbering is its own careful
    > change and does not belong inside a performance pass. These two were added as 25 and 26 to
    > avoid making it worse.

26. **An instrument that thresholds an interval has thrown the interval away.** `SilenceLog` is
    handed the exact milliseconds since a dismissal and keeps one boolean: was it late. That was
    the right call for the question it asks, and it meant that when the owner said *"the blocking
    isn't fast enough"* — twice, months apart — **there was no number anywhere on the phone that
    could agree or disagree with him.** Every instrument here recorded whether something happened;
    none recorded how long it took. This file has said "the before/after measurement is still
    owed" since 26 Aug 2026 and had no way to pay it.

    `BlockLatency` is the dial: buckets, not an average, because an average is exactly what hides
    a tail, and the tail is what he feels. Counts only, same rule as `BlockLog`. The stopwatch
    needed no new bookkeeping — `webScanQueuedAt` already recorded when a burst began, for the
    debounce cap; it simply had never been *used* as a start time.

    The companion to the `SilenceLog` question. That one asks: *if this quietly stopped working,
    what number would move?* This one asks: **if this quietly got slower, what number would move?**
    Both had the same answer, and the answer was none.

27. **A protection that punishes may only act on an answer it has, about a thing it has watched
    working.** Every rule before this one decides whether to cover a *screen*. `NetworkFilter` is
    the first that decides whether to shut **every browser on the phone**, off a reading of a
    system setting that can be absent, stale, or mid-change — so the cost of being wrong stops
    being a false-positive cover and becomes a phone that cannot reach the internet and cannot be
    told why.

    Invariant 4 says a failed measurement is not permission. Its mirror had never needed stating,
    because nothing until now could *charge* for one: **a failed measurement is not a punishment
    either.** `CANT_TELL` is a distinct state from `OFF` for exactly this, and the "off since"
    anchor is cleared whenever the reading is unavailable, so a flight or a dead spot can never
    quietly pay down the wait that ends in a consequence.

    Three more refusals, each a way this could have left him stuck rather than protected:

    - **An unvalidated network is never judged.** A captive portal resolves nothing until you log
      in and breaks a private-DNS filter by design; shutting the browsers there leaves him unable
      to reach the login page *and* unable to fix the filter. The screen-reading layers still
      cover that network — only this one stands down.
    - **A reading must hold still.** The state churns on every handover, and a protection that
      misfires daily is one that gets switched off.
    - **Armed, not assumed.** The guard defends only a filter it has observed working on this
      phone. Without that, the update shipping the feature reads an honest `OFF` on every phone
      that never set one up and takes the browsers away from someone who was never told why —
      with the screen that explains it reachable only through the hole it just made.

    **The shape to grep for: a new layer whose failure mode is not "it didn't block" but "the
    phone stopped working", judged by the same standards as a layer that only raises a cover.**

28. **A switch that reads "on" is not a protection; the thing it points at is.** Android's Private
    DNS can be on and filtering nothing — "automatic" mode, or any of the popular resolvers that
    encrypt lookups without blocking a single site. Reading the switch would have called that
    protected and defended it, which is worse than not having the feature: it puts a green light on
    an open door. So `FilterState` distinguishes `FILTERING` from `ON_BUT_UNKNOWN`, membership of
    `KNOWN_FAMILY_RESOLVERS` is the whole protection, and the bar for that list is *"this operator
    publishes it as an adult filter"* — never *"this is a good resolver"*. `security.cloudflare-dns.com`
    and `dns.adguard-dns.com` are deliberately absent for that reason.

    Same family as the accessibility toggle that says "on" over a watcher the phone killed
    (`ProtectionState.STALLED`) and the `soleHost` rule that refuses when candidates disagree:
    **the app must read the thing, not the claim about the thing.**

29. **A value cached behind a conditional tick is not refreshed — it is frozen.** `netFilterDown`
    was recomputed inside `recheckRunnable`, which looked like a 30-second poll and is not one:
    that runnable only re-arms while `recheckMatters` holds — a rule, a lockout, a schedule or a
    cover on the current app. On a plain browser with no rule on it, it never runs. So switching
    Private DNS off in Settings and opening a browser would have cost nothing at all, which is the
    single case the whole feature exists for. The code read as a poll, the tests were green, and
    the feature was inert.

    Found by the owner asking the right question — *"so how do you know if i turned it off if i
    wont turn it from the app"* — rather than by anything in the suite, and the suite could not
    have found it: every pure function was correct, and what was wrong was **where they were
    called from**. Same family as invariant 20's silent windows and the deaf-watcher problem: the
    defect is not a wrong answer, it is an answer that stopped being asked for.

    Now a `registerDefaultNetworkCallback` — changing Private DNS *is* a link-properties change, so
    it arrives as an event within moments and costs nothing while nothing changes — plus a
    throttled read on the `blockReason` path so a **missed** callback costs a minute rather than a
    reboot. OEM connectivity stacks drop callbacks, and this layer's failure is silent by nature:
    nothing looks wrong, the browsers simply stop being defended.

    **The shape to grep for: a field described as "refreshed periodically" whose refresh sits
    inside a callback that is itself conditional.**

30. **A grace measured from an event the check itself causes will forgive every time.**
    `protectionState` refuses to call an unbound watcher dead until `SERVICE_BIND_GRACE_MS` (20s)
    has passed since **our process started** — a sound rule against crying wolf in the moment
    Android takes to bind us. What it missed is who starts that process. The 15-minute worker, the
    boot receiver and the quick-settings tile all run in a process **WorkManager had to start in
    order to run them**, so `msSinceProcessStart` is one or two seconds and the grace swallows the
    answer. The one check written to catch a watcher that never came back was, in precisely the
    case it was written for, certain to forgive it and wait another fifteen minutes.

    It was also about to corrupt a measurement. `OK` is not a passive verdict in that branch: it
    **clears the alert, cancels the stalled repeat and closes the open `OutageLog` episode**. A
    cold-started check landing during a real outage would have ended the episode while blocking
    was still down — a recovery that never happened, recorded as fact.

    Fixed with `bindPending`, a separate predicate rather than a fifth `ProtectionState`, so every
    screen reading the four states is untouched: it answers "what should the watchdog do next",
    not "what is true". While it holds, the watchdog does nothing at all and re-checks in 45s.

    Same family as invariant 11 (an answer that has not arrived is not an answer) and invariant 29
    (an answer that stopped being asked for), pointed at time rather than data. **The shape to
    grep for: a threshold measured against a clock that the act of measuring resets.**

31. **Instrument the interval, not the crossing — a second time.** `ServiceHealth.foundDeadCount`
    counts outages and keeps nothing else: no start, no duration, no what-preceded-it. So when the
    owner said blocking stops "frequently, more than my other blockers" (28 Aug 2026), there was
    no value anywhere on the phone that could agree with him, disagree with him, or say whether a
    fix had changed anything. That is **invariant 26 repeating itself on a different dial** —
    `SilenceLog` kept a boolean where it had been handed an interval; this kept a count.

    Recorded as a recurrence rather than folded into 26, because the repeat says something 26 did
    not: this mistake is neither rare nor obvious at the time. Both instruments looked complete
    when they were written, and both answered the question being asked that week.

    ⚠️ **The Second Space explanation is retired.** `RepairScreen`, `DeviceVendor.spacesWarning`
    and the device-quirk entry below all named a space switch as *the* cause. The owner ruled it
    out on 28 Aug 2026 — he rarely uses it now and blocking still stops — while confirming the
    symptom exactly (the switch still reads ON; only an off/on toggle revives it) and confirming
    that Auto-start, "No restrictions" and a Recents lock are all already set. **Naming the wrong
    cause confidently is worse than naming none**: it sends someone to fix a setting that was
    never the problem, and it stops anyone looking further. Cause currently UNKNOWN. `OutageLog`
    exists to find it, and `Episode.aliveButDeaf` is the discriminator that splits the candidates:
    the process was killed and never rebound, or it was alive the whole time and stopped being
    delivered to.

32. **An instruction must not outlive the decision it asked for.** Invariant 21 said state that
    must outlive the process may not live in the process. This is its mirror, and it cost the
    same feature twice.

    `UpdatePause` writes a durable *intent* (`updatePausePending`) and later turns it into a
    *decision* (`updatePaused`). Those were two `apply()` calls with a Room database open between
    them — `strictSessionRunning` reads the focus row — running from a broadcast receiver, whose
    process Android may kill the moment `onReceive` returns. Surviving half-applied left the
    intent armed after the decision had landed.

    On its own that is harmless. What made it a bug is that **the Reactivate tap cleared only the
    decision**, and `checkVersionChange` re-reads the intent on *every* `onServiceConnected` — so
    the next boot, update, space switch or revive re-armed the pause. Blocking switched itself off
    again, minutes or days after the owner had switched it back on, with the accessibility switch
    still reading ON and nothing on screen having changed. It is the best match in the codebase
    for "it stops working, just like that".

    Both flags now move in one `commit()` ([SettingsStore.writeUpdatePause]) and the tap goes
    through `UpdatePause.reactivate`, which clears the pair. **The shape to grep for: a persisted
    intent with more than one writer, where one of the writers only knows about half of it.**

33. **The end of a window has to be wired to something.** A rebind — boot, update, Second Space,
    revive — spends a moment with no rules, and invariant 11's update made that window survivable
    for HARD blocks via `RuleSnapshot`. **Nothing was wired to the moment it closed.** The rule
    flow's first emission set `rules`, `schedules` and `rulesLoaded` and stopped; no re-check tick
    could be waiting either, because `recheckMatters` decides by reading the very state that had
    not arrived. So a schedule or a daily limit on an app opened inside the window did not fire
    until the user left the app and came back — which for a scheduled app is the whole block.

    Same family as 29 (an answer that stopped being asked for) and 30 (a grace measured against a
    clock the measuring resets): not a wrong answer, a question nobody asked a second time. Closed
    by `redecideAfterRulesArrived`, with `pkgToRedecide` pure and tested.

    **Grep for: a flag that flips from "not ready" to "ready" with no handler on the transition.**

34. **A rule that fails safe for one caller may fail open for the next one.** `refreshPackageSets`
    let `realBrowserPackages` be empty deliberately, and said why: an empty set "costs only the
    blunt block, never filtering". True when it was written. Since v1.139 and v1.142 that same set
    gates the **danger-zone hour** and the **DNS-filter browser shutdown** — `BlockDecision`'s two
    top layers, both requiring `isRealBrowser`. One swallowed PackageManager failure at connect
    therefore switched off two whole protections for the life of the service, silently.

    The set may still legitimately be empty. It may no longer be *assumed* empty on the strength
    of one reading: `isRealBrowserPkg` re-detects, throttled, the way `isLauncherPkg` has since
    v1.96. **Grep for: a comment justifying a permissive default by naming its cost, where the
    list of callers has grown since.**

35. **A loop that re-arms itself from inside its own error handler is one throw from gone.**
    `recheckRunnable` re-posted itself as the last line inside `guarded("recheck")`;
    `heartbeatRunnable`, forty lines away, re-posts *outside* its guard. One swallowed throw and
    the 30-second mid-use loop ended for good, taking limit crossings, schedule starts, Pomodoro
    flips and stale-cover releases with it — until the next foreground change happened to re-arm
    it. The re-post now lives outside the guard, and a swallowed error re-arms rather than stops:
    a repeating error costs a wake every 30 seconds and is reported on the way past, where the
    alternative was mid-use blocking silently ending.

    **Grep for: `postDelayed(this` inside a `guarded {` or `runCatching {` block.**

36. **A job outlives the moment it was started for, so every one of them is cancelled on
    screen-off.** `shortsScanJob` was the one thing `onScreenOff` never stopped, so locking the
    phone mid-Shorts let a scan finish *after* the whole cleanup had run and raise a cover with
    nothing left to take it down — stranded over whatever was on screen at the next unlock (v1.98).
    The Shorts *exit* added 30 Aug 2026 is the same shape with a sharper edge: left running, it
    would go on pressing BACK into a phone that had just been locked.

    `onDestroy` needs no equivalent list — every job is launched on `scope`, and it calls
    `scope.cancel()`. Screen-off is the case that has to name them one by one, because the service
    keeps running and nothing structural catches an omission. **`CodeShapeTest` now enforces it:
    every `@Volatile private var …Job: Job?` in the watcher must appear with `?.cancel()` inside
    `onScreenOff`.** Adding a job and not listing it there is precisely the mistake it catches, so
    a new job belongs in `onScreenOff` — never in the test's exceptions.

37. **Waiting to be told is not the only way to know.** Every liveness signal this app had was a
    *push*: an event arrived, or it did not. An absence is weak evidence — an idle phone produces
    exactly the same silence as a dead watcher — so the "bound but deaf" detector needed two hours
    **plus** fifteen measured minutes of use **plus** usage-stats permission before it dared speak.
    `ProtectionState`'s own comment called those *"hours during which the app cheerfully reported
    Protection active and blocked nothing."*

    The watcher can simply **ask**: on a lit, unlocked screen, `rootInActiveWindow` is a question
    only a service the framework is still talking to can answer. Five failures in a row is about a
    quarter of an hour, needs no permission, and is direct evidence rather than an inference from
    nothing happening.

    Three rules make it safe, and each is an older invariant applied here:
    - **Every "can't tell" passes** — screen off, keyguard up, no `PowerManager`. Invariant 4.
    - **It costs nothing when healthy.** The probe runs only inside the heartbeat branch that has
      already seen several minutes of silence, so a working phone makes no extra binder call at
      all. The owner's "keep the battery as it is" is met by construction, not by argument.
    - ⚠️ **A successful nudge must never clear the streak.** The nudge fires on the same
      three-minute schedule as the probe watching it, so letting it reset the count would mean the
      count could never reach two. That is invariant 30 exactly — *a threshold measured against a
      clock the act of measuring resets* — and it is the second time that shape has appeared in
      this file's own repair machinery.

    **The standing question this comes from, worth asking of any check: is it waiting to be told
    something it could go and ask?**


44. **The thing that knows a fault is over must be what ends it.** `OutageLog.end` had exactly one
    caller, `ProtectionWatchdog.checkAndNotify`, and that had six of its own — boot, the worker,
    the alarm, the notification listener, the tile, the app's resume. **Not one of them was the
    watcher.** So an episode stayed open until an *outside* observer happened to run, and all of
    those but the 25-minute alarm are WorkManager jobs — including `scheduleStalledRepeat`, the
    five-minute re-check — on a phone reporting `workerSilent: 140` and climbing.

    `outageMin` was therefore not how long blocking was down. It was **how long until something
    noticed it was back**, and the difference went into the total the owner is shown: three
    episodes on 4 Sep 2026 ran 15, 36 and 55 minutes against detection times of 2, 7 and 10.

    `onServiceConnected` firing *is* blocking coming back, known instantly and for free, and the
    heartbeat hearing an event again *is* the end of a deaf spell. Both now close the episode
    themselves (`ProtectionWatchdog.noteWatcherAlive`, `EndedBy.REBOUND` / `HEARTBEAT`).

    ⚠️ **It must not re-read the state to confirm what it already knows.** `checkAndNotify` would
    land on the `bindPending` grace — the process is seconds old at exactly that moment — and
    defer to a `scheduleRecheckSoon` WorkManager job, which is the throttled path the change
    exists to stop depending on. Same family as invariant 11: *do not ask an async source for a
    fact you are already holding.*

    **The standing question: when this ends, what is the first thing that knows — and is that what
    stops the clock?** An instrument whose resolution is coarser than the thing it measures
    reports its own latency as the phenomenon, and it looks like data the whole time.

    Two shapes to grep for: **a duration whose end is decided by a poller**, and **a measurement
    taken by everything except the component that owns the fact.**

45. **A fallback may not be written only by the component it is a fallback for.** Four snapshots
    (`RuleSnapshot`, `StrictSnapshot`, the keyword set, `ScheduleSnapshot`) defend the window
    between a bind and Room’s first emission, and every one of them was written from a single
    place: the accessibility service’s own `combine` collector. The watcher on the owner’s phone
    dies around thirty times a day, so anything he changed while it was dead — a newly blocked
    app, a Strict session, a word — reached Room and nothing else, and the next bind enforced the
    previous state through the exact window the snapshot exists to cover. `NewAppWatcher` and
    `PackageInstallReceiver` write rules from outside the service entirely.

    Fixed by `Snapshots`, registered from `BlockerDatabase.get` and driven by Room’s
    `InvalidationTracker`: the snapshot is now a consequence of **writing**, not of watching, so
    a future writer cannot forget it — there is nothing to remember. `CodeShapeTest` compares the
    watched-table list against the tables the refresh actually reads, so a fifth snapshot cannot
    be added quietly to the old shape.

    **The standing question: if the component that writes this value is the one that fails, what
    is left holding it?** Same family as invariant 11 and the report-sanitiser check — a value
    built correctly in one place and silently absent one step later.

    A second half of the same day’s finding, worth its own line: **a count of entering a
    defended window is not a fault.** `unreadyDecisions` was reported `good = false` while the
    Diagnostics screen, in a different file, called the same number "moments survived, not
    moments lost" — the correct sibling forty lines away, again. The counter is now split:
    `UNREADY_BLIND` (the window entered with an empty snapshot) is the fault; the plain count is
    a number.

46. **A summary of a measurement inherits the measurement’s caveats.** Invariant 44 split outage
    durations into ones the watcher timed itself and ones a poller closed, put the distinction on
    every line of the episode list — and left the one-sentence total ("unprotected for 19 h 34 min")
    as a flat figure with no note on it. That sentence is the one the owner reads and the one that
    got quoted back at him. `EndedBy.SELF_TIMED` and `OutageLog.Totals.timedMs/timedCount` now name
    the share that is a measurement; the fact says "treat it as a maximum" while none of it is.

    **The standing question: when a number is qualified, what else prints it?** Grep for the value,
    not the caveat — the caveat is exactly what the second copy is missing.

    Also here: **`reboundWake`**, the instrument that decides whether the ~15-20 min recovery is
    fixable. Warm = our process was already awake when Android rebound the watcher, so something
    woke it and the reconnection followed (a lever the app can pull itself); cold = the bind
    created the process and Android acted alone. Recorded only on a `REBOUND` that closed a real
    episode — on every bind it would be dominated by boots and updates, which are not recoveries.
    Same rule as `recordReviveOutcome` behind `canObserveEvents()`: a state where the answer is
    not knowable is not evidence.

47. **A null system service is not an answer about the thing it reports.** `canObserveEvents` and
    `probeScreen` each worked out "is this screen state judgeable" for themselves, four lines
    apart, and both got the same half wrong: a null `PowerManager` correctly meant *cannot tell*,
    while `km?.isKeyguardLocked != true` read a null `KeyguardManager` as **not locked** and went
    on to judge. The KDoc directly above already said *"every can't-tell passes"*. One rule, two
    copies, the wrong one in the same expression as the right one — `CoverGate` and
    `profileRowIsBad` were both extracted for this exact shape.

    It gates `recordReviveOutcome`, so it decides `revivesHelped` — the number that showed the
    self-repair is real. Scoring `futile` because a service was missing is the failure that
    KDoc exists to prevent, arriving through the door beside the one it was watching.

    `screenIsJudgeable(interactive, keyguardLocked)` is now the only copy, both nulls reach it
    **unflattened** (flattening at the call site was the bug), and `CodeShapeTest` fails the
    build if anything in the watcher reads those two properties outside the accessors that pass
    them straight through.

    **The standing question: for every `?:` and `?.x != y` on a system service, is the fallback
    an answer or an admission?** Enumerate the nulls in one expression together — the sibling is
    where the inconsistency lives.

    Found in the same pass, in code an hour old: `timedCount` counted a self-timed episode whose
    duration was unknowable (`-1` across a reboot) as a measurement, so the report could have
    said "36 min over 3 of them" with only two ever measured. `OutageLog.countsAsTimed` is the
    rule, and it had **no guard at all** until reintroducing the bug showed nothing went red.

⚠️ **Invariants 39-43 are not transcribed here.** They live as KDoc on their own checks in
`CodeShapeTest` / `SilenceLogTest` and are enforced there; this list stopped being updated at 37
during the 2 Sep sweep. Read the test file for those numbers before assuming a gap means an unused
number.


## Device quirks these invariants exist for

- Gesture-nav Home on HyperOS often emits **no accessibility event at all**, so the foreground
  cache silently goes stale. Most of the nine trace back to this.
- `rootInActiveWindow` can report **our own non-focusable cover** as the active window.
- The notification shade, volume dialog and heads-up notifications genuinely become the active
  window while the user has not left the app.

### Quirks of phones that are *not* the owner's (14 Aug 2026)

The engine is brand-neutral — foreground detection is standard accessibility events, the cover is a
standard overlay window, and the launcher/dialer/keyboard/browser lookups all ask the system rather
than naming packages. What was not brand-neutral was everything written *around* it:

- **Samsung sleeps background apps by default** ("Put unused apps to sleep"), which switches
  blocking off silently. Every other big OEM has an equivalent under a different name and in a
  different place. The advice and the deep link now come from `data/DeviceVendor.kt`, keyed on
  `Build.MANUFACTURER`, with a generic fallback that deep-links nowhere. Guessing wrong there costs
  a paragraph, never a blocking decision — which is why matching a vendor string is acceptable in
  that file and not in this one.
- **Android 13+ blocks Accessibility for sideloaded apps** until the user picks "Allow restricted
  settings" from the App-info ⋮ menu. Android greys the toggle out and explains nothing, and the
  onboarding wizard walks every new user of the `github` build straight into it. Explained in
  `RestrictedSettingsNote`, shown only while it can be true (sideloaded build, SDK ≥ 33,
  accessibility still off).
- **The phone's own browser was blanket-blocked on three brands.** On Huawei, Oppo/OnePlus and Vivo
  the built-in browser is usually the default, which puts it in `findRealBrowserPackages` with no
  list's help — and it was in neither readable list, so "block unsupported browsers" blocked it
  outright *permanently*: a blocked browser sits under our own cover, its address bar is never read,
  and `addReadableBrowser` never fires, so it can never earn its way out. Seeded in
  `KNOWN_READABLE_BROWSERS` now, along with `com.miui.browser`, which had the same gap on the
  owner's own phone while its global sibling did not.
- **Samsung's floating panels** (Edge panel, Game Booster, capture toolbar) are drawn over an app
  like the shade is, and were not in `TRANSIENT_SURFACES`. Added; see the note there.
- **Split screen and DeX are not handled, deliberately.** The cover is full-screen, so blocking one
  app in a split covers both halves (over-block), and a blocked app in the *unfocused* half may
  never be identified at all (under-block). Fixing it means changing both how the cover is sized and
  how the foreground app is decided — the two hottest pieces of the watcher — and it cannot be
  measured without a device that does it. Recorded rather than patched blind (14 Aug 2026).
- **`handlePurchaseBlock` requires `com.android.vending`,** so "block in-app purchases" does nothing
  on a Huawei with AppGallery. Adding `com.huawei.appmarket` would change nothing without knowing
  its purchase activity names — `PURCHASE_HINTS` are Google's class names — so the package name
  alone would only *look* like coverage.
- **Package visibility filters an explicit component, not just a query.** `startActivity` on
  `com.miui.securitycenter/…AutoStartManagementActivity` throws on Android 11+ unless that package
  is declared in `<queries>`, so `openAutostart` fell through to the app-details page and the button
  never went where its label said — for this app's whole life, on the owner's own phone. The
  manifest already documented the identical trap beside its `ACTION_DIAL` entry; nobody connected
  the two. Declared now, and `DeviceVendorTest` pins the Kotlin half. **A JVM test cannot read the
  manifest, so adding a brand means editing two files** — the manifest is the one that gets
  forgotten, and forgetting it fails silently.
- **The phone can be asked.** Profile ▸ *What the blocker sees* ▸ "This phone" resolves
  `ACTION_DELETE` to name the installer this phone actually uses and checks it against
  `GuardPackages.INSTALLERS`, so an OEM we guessed wrong is *stated* rather than silently
  unguarded. That is the cheap substitute for owning five phones, and the first place to look when
  someone reports blocking not working on a brand nobody here has.
- ⚠️ **SUPERSEDED as an explanation (28 Aug 2026) — see invariant 31.** The symptom below is
  exactly right and still happens; the *cause* named here is not the owner's. He rarely uses
  Second Space now and blocking still stops, with every keep-alive setting already granted. Read
  what follows as one way the watcher dies, not the way. `OutageLog` is what will name the real
  one.
- **A space switch kills the watcher, and Android's toggle keeps saying "on" (20 Aug 2026).**
  Reported by the owner: after switching to Xiaomi's Second Space and back, blocking does nothing
  while Settings ▸ Accessibility still shows AppBlocker enabled, and only toggling it off and on
  revives it. The switch is not lying about itself — `ENABLED_ACCESSIBILITY_SERVICES` records the
  user's **choice**, not whether the service is bound — but `AccessibilityUtil.isEnabled` reading
  it as "protection is on" was. Switching space stops every app in the space you left; HyperOS
  does not reliably rebind the service on the way back.
  **The app now asks a second question.** `BlockerAccessibilityService.isConnected()` is a static
  set in `onServiceConnected` and cleared in `onDestroy`, and it is conclusive because there is no
  `android:process` in the manifest — watcher, activity, tile and worker are one process, so any
  of them can read it. `protectionState` reports STALLED on `enabled && !connected` once the
  process has been up past `SERVICE_BIND_GRACE_MS`, which is the only false-positive window (the
  moment before Android binds us on a cold start).
  Before this, the *only* detector was "no events for two hours **and** fifteen measured minutes
  of use **and** usage-stats granted" — so the app reported "Protection active" for hours after
  every occurrence. **Under-blocking that reports as healthy is the failure this document exists
  for**, and it had been sitting in the one place nobody thought to distrust: the permission
  check.
  **It cannot be repaired from inside the app.** Writing that setting needs `WRITE_SECURE_SETTINGS`
  (adb/system only) — the rule that stops a malicious app enabling its own accessibility service.
  Self-*disabling* to force an honest toggle was considered and rejected by the owner: a false
  positive would switch off his own protection and would hand Strict Mode an exit. So the response
  is an ongoing notification and `ui/RepairScreen.kt`, which deep-links to our own accessibility
  page and confirms on resume that the toggle took.
- **Cloned apps cannot be blocked, on any brand.** Samsung Secure Folder / Dual Messenger, Xiaomi
  Second Space / Dual Apps, App Clone elsewhere: the clone runs as a **different Android user**, and
  an accessibility service receives no events from another user. This is not fixable from inside the
  app, so Setup names the feature and says so — an unblocked app the user believes is blocked is
  the invisible under-block in its purest form.

## Rules that check themselves — `CodeShapeTest`

⚠️ **Read this before adding another invariant to the list below.**

The 29 Aug 2026 sweep found four bugs whose rule was *already in this file*, and two of them had a
correct sibling implementation forty lines away in the same source file. That is this document's own
diagnosis turned on itself: *"the rule had been written down as a fact about one screen and never
grepped for."* Writing invariant 36 changes nothing if invariants 9 and 29 did not stop 32-35.

So the four shapes from that sweep are enforced by `app/src/test/java/com/appblocker/CodeShapeTest.kt`,
which reads the shipped `.kt` files and fails the build when a shape returns:

- a `Runnable` re-posting itself **inside** its own `guarded {}` / `runCatching {}` (invariant 35),
- either update-pause flag written outside `SettingsStore.writeUpdatePause` (invariant 32),
- wall-clock **subtraction** anywhere in `ServiceHealth` (invariant 9 — storing an instant is fine,
  measuring an interval with one is not),
- `in realBrowserPackages` read raw instead of through `isRealBrowserPkg` (invariant 34).

Each was proved by putting the bug back and watching the check go red. **The first version of the
runnable check did not fire** — the regex anchored to the end of the opener instead of scanning back
to the brace — which is the same lesson as the block-screen matrix test that first ran against empty
layouts: *a check that cannot fail is worse than none, and the only way to know is to break the code
on purpose.*

**When one of these fails, do not widen it.** Same rule as `BugReportTest`'s key tripwire: the check
exists because the mistake looks reasonable while you are making it. If a genuinely correct new case
does not fit, add it to that check's allow-list with the reason written next to it.

**The standing question for every future invariant: can this be a check instead of a paragraph?**
Not all of them can — most of this file is judgement, and judgement does not compile. But the ones
with a greppable shape should be, and each entry below that ends with "the shape to grep for" is a
candidate somebody has already done the hard half of.

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

### Swept in the "cover ownership" pass (18 Aug 2026) — every cover removal, again

The first hunt swept cover *removals* and found three bugs with one cause. This pass asked a
different question of the same sites — **not "is this removal correct?" but "whose cover is it?"**
— and found the first sweep had implicitly answered it for app covers only.

- browser Shorts borrowing `CoverGate.SHORTS_KEY` — **the owner's report**: raise/remove ping-pong
  between the web scan and `scheduleShortsScan`, i.e. a cover flashing several times a second while
  blocking nothing. Now keyed `"web"` (with `why="shorts"` still in the log).
- `handleAppBlock`'s unconditional `overlay.remove()` on `reason == null` — took down page, word,
  purchase and guard covers raised over the app that was still in front. The rule already existed
  in this function for the Shorts cover alone, spelled out by name, which is what a general rule
  looks like when it is noticed for one caller.
- the stray-window guard stopping at `overlay.isAppBlock` — the one kind of cover with no rule of
  its own to re-raise it was the one kind left unguarded.
- `lastWebText` outliving the cover — a dedup that turns any spurious removal into a permanent
  under-block. **This is the one that mattered**: the flash is what the owner reports, the silence
  afterwards is what he cannot.
- Not found, deliberately left: the purchase and guard covers now also survive until their owner
  leaves the foreground. The guard keeps its own 1.5s safety-net removal, so nothing is stranded.

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

### Swept in the twentieth "bug hunt" (11 Aug 2026) — every mutating write, and hours-old code

Two greps. One finding, and one sweep that came back clean and is worth recording *as* clean,
because it is the sweep somebody will otherwise keep re-running.

**`grep "@Delete\|@Insert\|@Upsert\|DELETE FROM\|UPDATE "` over the DAOs — every mutating
write in the app, eleven of them.** The question asked of each: *can this be reached with a value
that weakens protection while Strict Mode is running?* Only three can remove protection at all —
`AppRuleDao.delete`, `BlockedKeywordDao.delete`, `ScheduleDao.delete` — and every caller is
gated:

- `ScheduleCard` (`BlockingSchedules.kt`) — `canDelete = onDelete != null && !strictActive`, and
  `toggleEnabled = !strictActive || !schedule.enabled`, so a schedule can be turned **on** during
  Strict but not off, and the swipe-to-delete gesture is off entirely.
- `ScheduleEditorScreen` — the bin only renders when `existing != null && editable`, where
  `editable = existing == null || !strictActive`.
- `KeywordsScreen` — the remove button is `enabled = ed` where `ed = !strictActive`.
- `BlockEditorScreen` — keyword removal `enabled = ed`; app deselection refuses the *weakening*
  direction specifically (`if (strictActive && (if (allowlist) on else !on)) return`), which is
  the correct asymmetry and not the blunt "no edits" it would be easy to write.
- `commitQuickBlock` uses `upsertAll` and never deletes, so `save()` cannot remove a rule the UI
  refused to let the user remove.

This is the shape v1.110's "a fix that reached one call site" was, so it is now enumerated rather
than assumed. **Clean — do not re-derive it; extend the list instead if a new writer appears.**

One inconsistency noted and deliberately left: `BlockEditorScreen`'s adult-pack, auto-block-new-apps,
purchases and unsupported-browser switches are gated on `ed` outright, so during Strict they cannot
be turned **on** either. Everywhere else strengthening is allowed mid-session. It errs toward more
blocking, so it is not a bug — but it is a UX inconsistency and the reason is recorded here rather
than rediscovered.

**The extend feature, hours old (sweep nineteen's precedent: audit your own newest code).**
`FocusViewModel.extend` recorded the added minutes to `StatsStore` *before* and independently of
the SQL — and `FocusDao.extend` is deliberately a no-op for an expired row. A session can expire
between the button being drawn and the tap landing, or while the duration picker sits open, so
Insights could report Strict minutes that never happened. Two sources of truth drifting, the
shape this file opens with. `extend` now returns the row count and the statistic is recorded only
when it is above zero; `FocusExtendTest` pins the count.

Also checked and sound: the watcher re-arms `focusClearRunnable` from the same Room flow on any
write, so an extension pushes the auto-clear out with it and does **not** leave enforcement ending
at the old time; and the runnable re-checks `strictRemaining()` at fire time, so even a stale post
cannot end a live session.

### Reported, not swept (13 Aug 2026) — reading one vendor's view id

*"Why isn't Instagram blocked on Brave?"* Because website blocking could only read **Chrome's**
address bar, and had never been able to read any other.

The trace: blocking a site (`instagram.com` because the Instagram *app* is blocked) is matched
against the address bar **only**, never the page text — deliberately, so an article that merely
mentions Instagram doesn't cover the screen (`WebContentFilter.check`: *"Site keywords are
deliberately skipped with no URL"*). Reading the address bar was one literal lookup,
`"$pkg:id/url_bar"`, written and verified against Chrome. Any browser spelling its toolbar
differently returned nothing, and the site layer then did nothing at all.

**The invariant this adds (#12): a layer that depends on recognising one vendor's spelling must
degrade to a safe default, never to silence.** It is the can't-tell-is-not-a-no mistake (#4)
pointed the other way — there, an unreadable screen answered "not on Shorts" and a cover came
off; here, an unreadable address answered "no site to block" and no cover ever went up. Both are
a failed *measurement* reported as a confident negative.

Two things make it the file's own worst case. It is a **pure under-block**, so the owner could
only ever find it by trying a specific site in a specific browser — he had it for the app's whole
life. And the app **knew**: `SUPPORTED_BROWSERS = setOf("com.android.chrome")`, and
`BlockDecision`'s comment names Brave as the example. The knowledge existed as a mitigation
(the "Block unsupported browsers" switch, default **off**) instead of as a fix, so the default
configuration was the unprotected one.

`ScreenText.omniboxText` now tries three tiers — the exact id, then any `isOmniboxId` view id
(suffix-matched, so a fork keeping Chromium's layout under its own package is read the same way),
then any **editable** node whose text is host-shaped. Editability is what makes the last tier safe:
a link on the page reading "instagram.com" is not editable, so the page-mention over-block the
URL-only rule exists to prevent still cannot happen. `isOmniboxId` is pure and pinned in
`WebContentFilterTest`; the node walk needs a device and is verified by hand.

`SUPPORTED_BROWSERS` was deliberately **left alone**. Moving Brave into it would remove the blanket
block for anyone who has the switch on, trading a guaranteed block for one that depends on a lookup
that a future Brave release could break. The two are independent; the safety net stays up until the
tiers are confirmed working on a real device.

### The Brave report, finished (13 Aug 2026) — a hidden toolbar is not an absent site

The 13 Aug entry above fixed the wrong half first, and it is worth recording *why*, because the
method failed here in a way it usually doesn't. The address-bar lookup really was Chrome-only and
really was a hole — but it was not what the owner had hit, and shipping it as the answer cost a
release to disprove. **Reasoning from the source identified a defect; it did not identify the
defect being reported.** The two are not the same thing and were treated as if they were.

What it actually was, and the owner is the one who found it: *"the address bar is not fixed like
Chrome's, it shows sometimes."* Browsers slide the toolbar away as you scroll. With it off screen
`extractBrowserUrl` returns null, and `WebContentFilter.check` reads a null URL as **skip the site
layer entirely** — so scrolling down a blocked site switched the site block off. Chrome escapes it
only by accident of timing: its toolbar is up when a page loads, so the block lands before there
is anything to scroll.

**This is invariant 4 in a new place** — a hidden toolbar is a failed measurement, not evidence
the user has left the site — and it is the third distinct bug from the same root as the stranded
Shorts cover and the Chrome-only lookup. Fixed by remembering the last address read
(`rememberedUrl`), bounded three ways so it can never become a phantom block: cleared on every
foreground change, replaced the instant a different address is read, and expired by
`URL_MEMORY_MS`.

**The lasting fix is neither of those two, it is `DiagnosticsScreen`.** Four different faults —
not a browser, unreadable toolbar, no live site words, blocking paused — all present as *nothing
happening*, and there was no way to tell them apart on the phone. Every guess-and-ship round in
this entry existed because the app could not be asked. It can now.

### Reported, not swept (26 Aug 2026) — a relapse, and two of its three causes were silence

The report was *"the blocking wasn't fast enough"*, with three specifics: the Xiaomi browser,
switching spaces, and Instagram going straight to another Instagram page. Latency was the wrong
frame for two of the three.

- **Instagram → Instagram, and every website block**: the dismiss grace released only on a package
  change. Invariant 20 — the eight seconds, and why v1.135 turned its rare case into the common
  one.
- **Switching spaces**: `rules` empty until Room's first emission, read as "nothing is blocked".
  Invariant 11's update — a rebind is a window with no enforcement in it, and Second Space rebinds
  every switch.
- **UPDATE, 26 Aug 2026 (later the same day): the tier-4 cost below was removed, but still not
  measured.** `insidePage`'s ancestor climb is gone (invariant 25) — the reading of the code that
  the bullet below refuses to act on turned out to be right about *where* the cost was, and the
  fix took ~5,200 binder round trips per window out of every scan in that tier. That is a
  measurement of the code, not of the phone. **Whether it is what he felt is still unproven**, and
  Mi Browser still cannot be installed on a stock emulator. What has changed is that the phone can
  now answer: `BlockLatency` records how long each block took, so the next report carries a number
  instead of a feeling. Ask for one before assuming this is closed.

- **The Xiaomi browser: NOT INVESTIGATED, and deliberately not guessed at.** Mi Browser is read
  only by `omniboxRead`'s tier 4 (400-node walk per root, plus an ancestor walk per candidate),
  which no other browser pays, and every content and scroll event schedules it. That is a reading
  of the code, not a measurement, and Mi Browser cannot be installed on a stock emulator image.
  Nothing was changed there. The working theory is that most of what he felt in that browser was
  the eight-second hole above, since Mi Browser is where he browses — **which is a theory, and
  this file's own standing rule is that a theory is unproven until an experiment settles it.**

Also in the same pass, from his own plan rather than a defect: **auto-blocking newly installed
apps was about to shoot down the other blockers he intends to install as backup layers.**
Protection apps are now recognised structurally — an accessibility service, a device-admin
receiver or a VPN service, the only three ways to enforce anything about other apps — and are
excluded from the *automatic* path only (`NewAppRules`, `isProtectiveApp`). A newly installed
browser is still auto-blocked; that one really is the escape hatch.

**What is verified and what is not.** The three rules are pure functions with 17 new unit tests,
each watched failing before the fix. The *wiring* is not covered — the watcher has no test
coverage and cannot have any as written — and an end-to-end timing run on the emulator did not
complete: Chrome ANR'd repeatedly on a freshly cleared profile, and an `am force-stop` of our own
app cleared `enabled_accessibility_services` outright. What that session did establish is that the
service binds, receives events, recognises Chrome as a browser with the new `<queries>` entries in
place, and raised a real cover. The before/after measurement is still owed.

### Swept (26 Aug 2026, later) — the latency pass, under a battery budget

He came back the same day with the part v1.139 did not answer: *"the time between the blocked word
found and the app being locked should be faster, shorter."* Asked which gap, he said **all three**
— content to cover, cover to app locked, "Got it" to next block — and set the constraint that
shaped everything: **"keep the battery as it is."**

That rules out the obvious lever. Cutting `WEB_SCAN_DEBOUNCE_MS`/`WEB_SCAN_MAX_WAIT_MS` means
reading the screen more often, which is precisely what he declined. So the pass looked for two
other things only: **work done twice**, and **waiting that buys nothing**. Everything shipped makes
the app do *less* per event than before, not more.

Ruled out first, and worth recording: `notificationTimeout="0"` in
`res/xml/accessibility_service_config.xml`, so Android imposes no per-event delay. Every
millisecond in the path is ours.

- **The tier-4 ancestor climb** — invariant 25. The largest single item, and it lands on the one
  browser he actually uses.
- **The draw path did its paperwork first.** `AttemptCounter.record`, `BlockLog.record` (a prefs
  read, a sixty-entry split and a rebuild), a `rootInActiveWindow` lookup for the log alone, a full
  repaint of theme and arrangement, and a synchronous PNG decode of the mark all sat between
  `decideBlock` returning and `addView`. None changes a pixel. The log now goes on the next
  message; the repaint is skipped when nothing changed; the mark is skipped when it is already the
  thing on screen. **`OwnUi.visible` and the window classification must still be read before the
  show** — our own non-focusable cover can report as the active window, so reading them afterwards
  would describe the cover rather than what it covered.
- **`DISMISS_GRACE_STUCK_MS` charged to the BACK exit** — invariant 25's second half.
- **The lockout had no cover.** `addKeywordLockout` records the deadline; nothing raised the
  "Locked" screen. It waited for a foreground change or `RECHECK_MS`, and after a BACK exit onto a
  static page neither arrives. Now re-decided when the grace ends. Raising it *immediately* would
  be wrong — the page cover is up at that instant, and a second package-keyed cover through it
  double-counts one word and flashes twice.
- **The single-flight URL scan dropped events.** An address arriving mid-read was never re-queued.
  One bounded repeat now, not a loop: unbounded retry is the spin the single-flight exists to stop.
- **`WebContentFilter.get()` re-parsed four assets per scan while a load was failing.** The retry
  is right, its frequency was not.

**Not done, deliberately: fusing the two tree walks.** `scanWebContent` walks for text and then
`omniboxRead` walks again. Merging them would save one four-hundred-node walk on non-tier-1
browsers — but the two differ in root selection and in how the node budget is shared (400 total vs
400 *per root*), so on an OEM build that populates `windows` a merged walk would read a different
four hundred nodes. That is the exact class of change whose failure mode is a silent under-block.
After invariant 25 removed ~5,200 IPCs from that same path there was not enough left in it to
justify restructuring the most delicate function in the app. **Revisit with `BlockLatency` numbers,
not with a reading.**

**What is verified and what is not.** 18 new unit tests, each watched failing first — the depth
case proven by restoring the hop cap, the grace cases by removing the BACK branch, the buckets by
rounding them the flattering way. 609 pass. The *wiring* is uncovered as always. **No before/after
measurement on a device yet** — that is what `BlockLatency` (invariant 26) exists to produce, and
the honest next step is to ask him for a report and read `blockSpeed` rather than to claim this
worked.

### Reported, not swept (21 Aug 2026) — two reports, one shape

Both were *knowledge that did not survive the moment it was needed*, and both were already written
down as the intent somewhere in the file that failed.

- **The browser one** (invariant 20's continuation): Chrome inline-autocompletes the omnibox out of
  the user's own history, and the site layer read the completed address as a page he was on. The
  v1.133 fix had taken the tiles, the suggestion list and the feed away from the matcher and left
  the address bar itself trusted whole — the same history, one node further in.
- **The guard, on his tablet** (invariant 21): the exemption saying "we opened this installer" was
  erased by the install it was written to cover.
- **The guard, on his phone** (invariant 22): the package came from the event and the text from the
  screen, so a background Settings window let the guard read a Claude conversation and bounce it for
  quoting our own service description. Almost certainly report #5 as well, a week earlier.

Three of the four are the same sentence: *the thing that knew was not the thing that was asked.*

Neither came out of a sweep. Both were predicted in spirit by entries already in this file, which is
the argument for working the list below rather than admiring it.

### Swept (29 Aug 2026) — the liveness layer, asked for by name

The owner's question was specific: *"why dont you check one more time about bugs that could make
the app stop automatically after the phone was off or spaces switched or just like that?"* — asked
minutes after v1.143 published. The area had never been swept: ~1,368 lines of it were three days
old, and this file's own "Not yet swept" list did not mention it.

**Method.** Two enumerations rather than a read-through: every mechanism that keeps the watcher
alive or notices it has died, and every piece of state that can hold blocking in a "do not block"
position. Then the standing move — grep the primitive, not the feature — over `elapsedRealtime`,
`@Volatile` process-lifetime state, `apply()` vs `commit()`, and every `register*` with no
re-registration path.

- **Blocking switching itself back off after Reactivate** — invariant 32. The one that best matches
  what he actually reports.
- **Schedules not firing on an app already open after a rebind** — invariant 33. The other half of
  the Second Space fix that v1.139 only closed for HARD blocks.
- **The mid-use re-check loop dying on one swallowed throw** — invariant 35.
- **PAUSED outranking "switched on and not running"** — so the window right after an install, which
  is Android killing our process and the leading hypothesis for his outages, was the one case
  `OutageLog` structurally could not record. Reordered; `bindPending` now covers the paused case so
  the reorder cannot cry wolf during a legitimate rebind.
- **An outage episode only ever closed on the way to OK** — STALLED → OFF (him doing the repair the
  alert asks for) and STALLED → PAUSED (an update mid-outage) left it open and `foundDeadPending`
  stuck true, so the next outage was never counted and the one eventually closed spanned hours that
  were not an outage. Reports now carry `outageEnded`.
- **`ServiceHealth`'s two stamps were the last wall-clock timers in the app** — a backward clock
  froze `health_last_event_at` and the staleness arm answered OK until the clock caught up. The
  throttle is monotonic now and a future stamp self-heals.
- Plus `DeviceBoot` caching a failed read forever, `realBrowserPackages` (invariant 34), the four
  listener-fed settings gaining the backstop the DNS filter already had, and the revive nudge and
  `onInterrupt` finally being counted — the inside view of `aliveButDeaf`.

**What this did NOT establish.** The cause of his outages. Nothing here is proof; `outageDeaf` in
the first real report still is. Two of the five, though, were corrupting that very instrument, so
they were worth fixing before the data starts arriving rather than after.

**Also confirmed, and worth not re-deriving:** there is no `disableSelf`, no `stopSelf`, no
`System.exit` and no `killProcess` anywhere in the app. It never switches itself off deliberately.
The boot path is sound — `BootReceiver` is not direct-boot-aware and takes only `BOOT_COMPLETED`,
so nothing runs before credential storage is available, and WorkManager re-arms the watchdog two
ways. The rule flow cannot die permanently: `SupervisorJob` plus `retryWhen` close that.

### Swept (5 Sep 2026) — "where does a null mean no instead of don't know", finally enumerated

The candidate this list has named as the best one since it was written, and the only one that had
already produced a real report before anyone swept it (invariant 20). Method: grep the primitive —
`getOrDefault(false)`, `getOrNull()`, `?: false`, `?: return`, `?: 0L` — across the watcher, the
decision code, the web filter and the screen readers, then ask of each whether the fallback is an
answer or an admission.

- **`canObserveEvents` / `probeScreen` — 1 bug** (invariant 47 above). The only one in the
  enumeration that was wrong, and it was wrong in the sibling-null shape.
- `isProtectiveApp` fails toward auto-blocking and says so; `stillOnScreen` returns true on an
  unreadable root, which is the blocking direction; `WebContentFilter.check` distinguishes
  `Blank` (a measurement) from `Unreadable` (a failure) and only the failure falls back;
  `ProtectionWatchdog.read` returns null usage minutes so a failed read can never say STALLED;
  `OutageLog.shape` reports `-1` rather than guessing a duration. **All clean, all documented at
  the site.** A clean enumeration is a result: this file may stop listing the question as open.
- Still permissive by choice, and recorded rather than fixed: a Wi-Fi schedule whose SSID cannot
  be read does not match, and `handlePurchaseBlock` is className-only. Both were judged in
  earlier sweeps; neither applies to the owner, who runs no schedules (`scheduleCount: 0`).
- `BugReportQueue`: **the daily cap is spent only by `markSent`**, so a run of failed sends can
  never burn the budget — checked because a silent day looked exactly like that. One stale
  comment in `flush` still claimed the cap was also checked at enqueue, which stopped being true
  in v1.156; corrected. Third stale comment in two days, all three in code that had just been
  changed correctly.

**Yield: 2 bugs, one of them an hour old, from an enumeration of ~40 sites.** That is thin, and
the thinness is the finding — this area has been swept out. The next hunt should take the second
named candidate (*where else does a lookup have exactly one spelling?*) or the ~8,300 lines of
reporting and recovery code written since 30 Aug, which no sweep has touched.

### Not yet swept

- **Where else does a null answer mean "no" instead of "don't know"?** Four bugs now share this
  root and the enumeration has never been done. The shape to grep for is a nullable read feeding a
  decision where `null` takes the permissive branch — `?: return`, `if (x != null)`, `?.let` around
  a block that blocks. ~~`WebContentFilter.check`'s URL is one~~ — **that one landed** (20 Aug 2026,
  invariant 20): it was written here as a prediction and arrived as a report before anybody swept
  it, which is the best argument this list has for being worked through rather than admired. The
  remaining ones have still never been enumerated.
- **Where else does a lookup have exactly one spelling?** The 13 Aug report was a hardcoded
  vendor string that silently meant "off". `SHORTS_ID_MARKERS` is the same shape and admits it
  ("exact ids vary by YouTube version"); so are the launcher, IME and dialer detections, though
  those fail *loudly* because the phone stops working. Nobody has enumerated the rest.

- ~~The rest of `BlockOverlay`.~~ **Swept in the eighteenth hunt** — all eleven readers traced, the
  set-before-`addView` window confirmed unreachable, one latent trap (`onClose`) recorded. See that
  entry. It is no longer the best candidate.
- ~~The AI coach's persistence and the report queue's disk format.~~ **Swept in the nineteenth
  hunt** — `BugReportQueue`'s cap was found counting the wrong side of the queue; `CoachProfile`'s
  write-back-on-failed-read and the unordered sent-key trim were both judged and left, with the
  reasons recorded there. `MoodStore` and the advice ledger are clean. No longer a candidate.
- **The new best candidate: state that escapes its composition.** The nineteenth hunt's first
  finding was a lambda holding a `remember(key)` state that a resume had already replaced, and it
  only enumerated the two sites in `ProfileScreen`. The general question — *which callbacks in this
  app outlive the composition that built them, and what do they write?* — has not been asked of
  `BlockEditorScreen`, `BlockingScreen`, `ScheduleEditorScreen` or the overlay screens, all of which
  hand callbacks upwards to `AppRoot`.
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

### A layout rule, learnt three times in one day (v1.113–v1.115)

Not a sweep — three separate reports about one screen, `ui/FrictionGate.kt`, each the same defect
wearing a different disguise. Recorded here because the rule generalises and the cost of relearning
it was three releases.

**The element a screen exists for must never be the flexible one.**

The gate laid itself out as a `Column` where every child had a fixed height except the paragraph
card, which carried `Modifier.weight(1f)`. A weight is not a size, it is *whatever is left over* —
so every pressure on that screen was paid for by the one thing the screen is about:

1. **v1.113**: five lines of explanation at the owner's system font scale left nothing over. The
   paragraph rendered as a single clipped line.
2. **v1.114**: fixed by *removing content* — explanation into a dialog, input from three lines to
   one. That bought room with the keyboard closed and none with it open.
3. **v1.115**: with the keyboard up, `safeDrawingPadding()` correctly shrinks the Column and the
   weight child went to **zero**; the fixed children then overflowed and the last button was sliced
   in half.

Rounds 1 and 2 treated the symptom. The defect was the contract: **a weighted child is a promise
the layout is allowed to break, and it breaks silently — at exactly the font scale and keyboard
state the developer did not test.** Both of the states that broke it are ones a cloud session
cannot see.

The fix is structural, and came from an app the owner pointed at: nothing on its confirmation
dialog has a flexible height, so there is nothing for the keyboard to squeeze. The gate now pins
the title and the countdown, puts everything else in **one scroll container**, and gives the
paragraph a **stated height**. A weight on a *scroll container* is safe in the way it is not on
content — squeezing it scrolls rather than starving what is inside.

Generalises beyond Compose: whenever a layout has one flexible element and many rigid ones, the
flexible one absorbs every future addition. Ask which element the screen exists for, and make sure
it is not that one.

**This rule now has a test (31 Jul 2026).** The sentence above ended "both of the states that broke
it are ones a cloud session cannot see", and that was true of every kind of test the project had:
the defect only exists once something has been *measured*, so no JVM test could ever hold it. There
is now a rendering layer in **`app/src/androidTest`**, run on a device:

- `FrictionGateTest` — the gate at several viewports and at a large system font. The paragraph must
  keep its stated floor and the field and button must stay reachable. **Restore `weight(1f)` on the
  phrase card and it fails**, which is the whole point: the rule is enforced rather than remembered.
- `FontScaleTest` — the schedule tiles must actually grow with the system font. v1.54 shipped them
  clipped, found from a photo of the owner's phone, because every screenshot taken in development
  was of a font size he does not use.
- `BlockScreenMatrixTest` — every layout × theme × arrangement, measured on a tall screen and a
  short one, asserting the one thing that must never fail: **"Got it" is on screen.** This is the
  fear `BlockLayouts` already wrote down about the Focus layout, now checked instead of hoped.

They run in CI on `master`, on demand, and — the part that matters — as a **gate in front of every
release** (`publish.yml` will not build an APK until they pass). It costs ~10 minutes per publish.
Given v1.104→v1.110 and v1.113→v1.117, that is cheap.

**So: when a fix is a layout fix, add a case here.** The failure mode this file keeps recording is
a lesson learnt and then relearnt; a test is the only form of a lesson that cannot be forgotten.

### The same rule, found in the block screens (31 Jul 2026) — by the test, on its first run

`BlockScreenMatrixTest` was written to check one thing — *is the way out on screen?* — and failed
immediately, on **five** combinations across three of the four layouts:

| Layout | Screen | "Got it" ended |
|---|---|---|
| editorial | short 1080×1600 | 341px below the bottom |
| focus | **tall 1080×2340** | 12px below |
| focus | short 1080×1600 | 52px below |
| scoreboard | **tall 1080×2340** | 185px below |
| scoreboard | short 1080×1600 | 386px below |

Two of those are on a **tall** screen — the owner's phone is 1080×2400, so Scoreboard at "Huge"
put the only button on the block screen about 125px off the bottom of his own device. Nothing in
the editor prevents that combination.

**It is the layout rule above, in a different file.** Each layout was one `LinearLayout` ending in
the button, with a weight above it: on `el_quote` in Editorial and Quote, on a spacer in Focus and
Scoreboard. Grow the fixed pieces past the screen — the size steps reach 1.8×, Scoreboard's number
is 150sp before that multiplier, a long quote wraps — and the weight goes to zero, the fixed
children overflow, and the button is pushed out of the screen. Identical shape to the friction
gate: *one flexible element among rigid ones, which collapses silently at exactly the configuration
nobody tested.*

Fixed the same way it was fixed there: the pieces went into a `ScrollView` carrying the weight, and
the button became a **sibling** of the scroller rather than a child, so nothing above it can move
it. `fillViewport="true"` stretches the inner column to the viewport whenever the content fits, so
every existing weight still divides the same space and the hand-tuned look is unchanged — asserted,
not assumed, by `theDefaultLookIsUnchangedByTheScroller`, which requires the content to be *exactly*
the viewport height at the default arrangement. Applied to all four layouts, including Quote, which
did not fail but has the largest quote in the app (42sp) and was one longer line from the same bug.

Two lessons worth keeping:

- **The rule generalises further than it was written.** It was recorded as a fact about
  `FrictionGate.kt`. It was actually a fact about every screen in the app that ends in a button,
  and nobody looked. When a rule like this is written down, grep for its *primitive* — here,
  `layout_weight` above a fixed control — exactly as the standing guidance says.
- **A test that cannot fail is worse than no test.** The first version of `BlockScreenMatrixTest`
  inflated the layouts without filling in any text, so it was measuring empty views; it still caught
  Editorial, but it would have passed Focus and Scoreboard — the two that reach the owner's own
  screen. It now fills in real content, including `Quotes.longest()`. This file's warning about
  diagnostics that fail silently applies to the tests as much as to the app.

### Swept in the nineteenth "bug hunt" (30 Jul 2026) — this morning's own change, and the report queue

Two targets: sweep 10's method (**audit the day's own changes**) pointed at the typed gate's move,
made hours earlier; and this file's own standing candidate, the coach's persistence and
`BugReportQueue`. Both findings are of the same family — **a safety mechanism whose two halves
disagree** — and neither is on the blocking path, which is worth saying plainly rather than
dressing up.

1. **A confirm action that outlived the state it writes — 1 bug, mine, from this morning.** Moving
   the typed gate out of `ProfileScreen` and into `AppRoot` (so it gets the whole window; see the
   layout rule above) meant the gate's confirm action became a lambda stored in AppRoot's state.
   Every other thing that writes `adminOn` is a lambda built during composition, so it always holds
   the current state object. This one does not — and `adminOn` was `remember(resumeTick)`, which
   builds a **new** `MutableState` on every resume.
   - So: open the gate, leave the app and come back while typing — three minutes is long enough to
     take a call — and the confirm lambda is holding the pre-resume state. `disableDeviceAdmin`
     still runs, the protection really is off, and the row goes on saying *"On. AppBlocker can't be
     uninstalled until you turn this off"*. It self-heals on the next resume, so it is the quiet
     kind: **a protection reporting itself as on while doing nothing**, which is the shape sweeps 2,
     3, 13 and 15 each found somewhere else.
   - `guardRequest` is the same, one row down: the two-hour wait would start on disk and not appear
     on the row, so the countdown looks like it never began.
   - Fixed by keeping **one** state for the life of the screen and refreshing its *value* on resume
     (`remember { }` + `LaunchedEffect(resumeTick)`) instead of rebuilding the state itself. Same
     freshness, stable target.
   - **The generalisable rule, and it is new to this file:** `remember(key)` is not just a cache
     hint, it is an *object identity* that changes when the key does. The moment anything outside
     the composition holds a reference to that state — a callback handed upwards, a lambda parked in
     someone else's `remember` — the key becomes a way to silently strand the writer. Grep for
     state written by a lambda that escapes its composition; there are exactly two in this app and
     both were created this morning.

2. **The daily report cap counted the wrong side of the queue — 1 bug.** `MAX_PER_DAY = 12` is
   described in its own KDoc as "a hard stop on issues opened per day, whatever goes wrong… the
   backstop that cannot be reasoned around". `enqueue` checks it; only `markSent` increments it —
   and nothing is sent until the next app open. A bad day therefore queues report after report
   against a counter still reading **zero spent**, and the next `flush` posts the lot in one pass.
   The real ceiling was `MAX_PENDING` (20), not 12.
   - Fixed in `BugReportSender.flush`, which now re-checks `remainingToday` before each post and
     stops when it is spent; what does not go out stays queued for tomorrow, which is the cost the
     KDoc already describes. No test — the queue needs a `Context`, the same reason its per-day cap
     has never had one.
   - Worth naming as a shape: **a limit is only as good as the event it counts.** This one counted
     sends while being enforced at queue time, and those two moments are decoupled *precisely* when
     it matters, because trouble correlates with being offline — which is the queue's whole reason
     for existing.

**Considered and deliberately left** (so the next sweep doesn't re-litigate them):

- `BugReportQueue.markSent` trims the sent-key memory with `keys.toList().takeLast(200)`, but the
  keys come from a `getStringSet` — a `HashSet`, with no ordering and no timestamp in the entries —
  so "the most recent 200" is arbitrary rather than recent. The newly added key does survive (it is
  appended last), so the immediate guarantee holds; what can be evicted is an *old but still
  recurring* bug's key, costing one duplicate issue. Reaching 200 distinct keys needs months at 12
  a day with dedup working. Giving it a real ordering means stamping the entries, i.e. a stored
  format change, for a memory whose worst failure is one extra issue in a private tracker. Not
  worth it — but note the sibling got this right: the advice ledger is also a `StringSet` and
  recovers its order by sorting on the day stamp it stores (sweep 10).
- `CoachProfile.merge` is a read-modify-write over a read that returns `emptyMap()` on failure, so a
  parse failure would not merely fail open — it would **persist** the empty map and erase everything
  the coach has learned. Invariant 10's write-back variant, and the nastier one. Left because it is
  not reachable in practice: the file is written only from a `Map<String, String>` and
  SharedPreferences replaces its XML atomically with a backup file, so there is no partial-write
  path to corrupt it. Worth revisiting the day anything else writes that key.

**Verified clean, so a later sweep can skip them:**

- Profile's off-switch-guard block is the sibling of the `KeywordsScreen` phase bug (sweep 13,
  finding 3) and already has the right shape: `guardPhase` is derived on every recomposition from
  `OffSwitchGuard.phase`, the tested state machine, and the one-second ticker only drives the
  countdown's redraw. No phase decision depends on the ticker having run, so backgrounding cannot
  strand it.
- `ProfileScreen.onRequestGate` defaults to a no-op, so a wiring mistake in `MainScaffold` would
  make the row silently do nothing rather than turn the protection off — the safe direction, and
  checked rather than assumed (the wiring is present).
- `MoodStore`, the advice ledger and `BugReportQueue.encode`/`decode` all round-trip their fields;
  the day-stamp arithmetic goes through `dayGap`/`stampDaysAgo` per the sweep-1 lesson.

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
