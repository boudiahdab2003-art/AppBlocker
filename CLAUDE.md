# AppBlocker — project memory

Personal Android app blocker (Kotlin, Jetpack Compose, accessibility-service
enforcement). Owner is non-technical; explain things in plain language.

## Workflow (established)

- **No local builds** in cloud sessions (Google Maven is blocked). Compile-verify by
  pushing: the **"Build check"** GitHub Actions workflow runs the unit tests and
  `assembleGithubDebug` on every push.
- On the owner's PC local builds DO work: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`
  with the bundled Gradle (`_tools\gradle\gradle-8.9\bin\gradle.bat -p . :app:assembleGithubDebug
  :app:testGithubDebugUnitTest`) — there is no gradle wrapper, and the `github`/`play` flavors
  make bare `assembleDebug` ambiguous. Test on the `appblocker_test` emulator.
- **Rendering tests** live in `app/src/androidTest` and run on a device:
  `gradle -p . :app:connectedGithubDebugAndroidTest` (needs the `appblocker_test` emulator
  running). They exist for the bugs a JVM test structurally cannot have — a button under
  the keyboard, a label clipped at a large system font, a block screen whose "Got it" has
  been pushed off the bottom. Those only appear once something has been *measured*.
  **Add one whenever a fix is a layout fix**; the five releases it took to make the typed
  gate usable are what this layer is for. In CI they run on `master`, on demand, and as
  the gate in front of every release.
- **Releases are cloud-published**: merge to `master`, then trigger the
  **"Publish release"** workflow (`publish.yml`) with a plain-language release note —
  it now runs the rendering tests on an emulator FIRST and refuses to build if they fail
  (~10 extra minutes per publish, deliberately) —
  it bumps the version, builds the signed APK (key in repo secrets, fingerprint
  verified), updates CHANGELOG.md, tags, and publishes the GitHub release the
  in-app updater reads. **Only publish when the owner says "publish".**
- CHANGELOG.md entries are written by the publish workflow — don't hand-edit
  version sections. **`data/Changelog.kt` is different**: it is the in-app "What's new" list,
  hand-written, and `ChangelogTest` fails the publish if the version being released has no
  entry. Write it for the *next* version — and if a release goes out mid-session, the entry you
  were editing is now history: start a new one rather than editing a shipped version's text.
- Develop on the session branch, merge to `master` after the Build check is green.
- **Direct `curl` to `api.github.com` does not work in cloud sessions** — the proxy returns a
  "GitHub access is not enabled" JSON body with HTTP 200-ish framing, which a shell watcher
  happily reads as "not finished yet" and waits forever. Use the GitHub MCP tools
  (`actions_list`, `actions_get`, `list_issues`, …) for every GitHub read. Their run listings are
  large; save the tool result and parse it with `python3 -c` rather than reading it whole.

## "Check my report" — the owner reports bugs from inside the app

In-app bug reports become **GitHub issues in the private `boudiahdab2003-art/appblocker-reports`
repo** — *not* this one. It is not attached to a session by default: call `add_repo` for it (no
clone needed; the reports are issues, not code), then `list_issues`. `docs/SERVER.md` still
describes the VM side as "pending"; it is not — reports have been arriving since #1.

Read a report with `BlockLog`'s format in front of you (`data/BlockLog.kt`). Each line says which
layer raised a cover (`why=`), what was really on screen (`window=match/other/blind/n-a`), whether
it covered our own UI, and whether it counted. The entries *after* the complaint are usually the
owner going to Settings to investigate — read the log backwards and find the block he means.

**Then send him to Profile ▸ "What the blocker sees"** (`ui/DiagnosticsScreen.kt`). It is the first
tool for any "it blocked X" / "it didn't block Y" report: it names this phone's brand, the uninstall
screen, whether the keep-alive deep link resolves, which apps count as browsers, and — separately —
which of those have *actually been read* versus merely *assumed* readable. One screenshot of it
answers most of what would otherwise be a guess.

## Phones nobody here owns — the device probe and the profile report

The app claims to support Samsung, Huawei, Oppo/OnePlus and Vivo, and **every one of those claims
was reasoned rather than measured** (`PhoneReport.kt` says so itself). Two things now answer that
without owning the hardware, and they share one implementation in **`data/DeviceProfile.kt`** —
the diagnostics screen, the probe and the reporter must never be able to disagree about a phone.

- **`DeviceProbeTest`** (androidTest) — point it at any device and the guesses that are wrong *for
  that device* fail with the one-line fix in the message. Three can fail: an uninstall screen
  `GuardPackages.INSTALLERS` does not watch, a keep-alive deep link that resolves nowhere, and an
  OEM package installed but invisible to our `<queries>`. That last one compares the **shell's**
  package list against the **app's**, which is the only way to tell "not installed" from "filtered
  out" — a JVM test structurally cannot. Most of it skips on the release-gate emulator by design.
- **`BugReportSender.reportDeviceProfile`** — called from `MainActivity.onResume`, sent **once per
  phone per build, and sent even when every answer is right.** A phone we got wrong does not crash;
  it quietly stops protecting. Filing the healthy ones too is what makes silence meaningful, and
  the issue title says `profile OK` or `PROFILE: something is wrong here` so the list stays
  scannable. Deduped by the queue's own key — no extra bookkeeping.
- The profile context keys have their **own longer cap** (`PROFILE_CONTEXT_KEYS`, 240 chars): the
  general 24-char cap exists to catch values long *because something unintended got in*, and it
  would shred `com.sec.android.app.sbrowser`. Safe only because every profile value is built from
  our own constants. Do not fold them back into `ALLOWED_CONTEXT_KEYS`.
- **`docs/REMOTE_TEST_LAB.md`** is the run sheet for actually getting onto that hardware, and
  **`tools/lab_apk.sh`** (builds a Samsung-installable APK) and **`tools/rtl.sh`** (drives the
  session; `prove <browser>` is the whole address-bar measurement) are its hands —
  Samsung Remote Test Lab, a ~20-minute clock on a device that is wiped afterwards. It holds
  the three traps that silently disarm blocking on a freshly flashed phone (the after-update
  pause, `pm clear` pruning the accessibility setting, and `am force-stop` rebinding the
  service so it reopens the database before a seed is written over it), and what actually
  proves a block — `BlockLog`, never `mCurrentFocus`.
- Results accumulate in **`docs/DEVICE_MATRIX.md`**. Keep it updated; a "claim unproven" row is
  not a passing row.

## "Play version" — selling this on Google Play

When the owner says **"play version"**, he means the whole effort of turning this
personal, single-user app into a paid product on the Google Play Store. The full
step-by-step plan (non-technical, phase by phase) lives in
**docs/PLAY_VERSION_PLAN.md** — read it first before doing any play-version work,
and keep its "Where we are right now" section updated as we progress. The `play`
build flavor already exists (no self-update, no location schedules) as the
Play-safe starting point.

## "Bug hunt" — go looking for blocking bugs

When the owner says **"bug hunt"** (or asks to look for bugs/errors), he means: audit the
blocking watcher for bugs *before* he hits them, rather than fixing only what he reports.
Read **docs/BLOCKING_INVARIANTS.md** first — it holds the invariants the code depends on, the
two mistake-shapes every past bug reduced to, the grep-by-kind audit method that works, which
areas have already been swept, and which are still untouched. Keep its "Swept so far" /
"Not yet swept" sections updated.

Two things that make this worth doing: the watcher has **no test coverage** and can't have any
as written, and **under-blocking is invisible to the owner** — he notices a block screen that
shouldn't be there, never one that failed to appear. Assume his bug reports under-represent
the real count. Batch what you find into **one** release.

## Spare server

A free-tier GCP VM (e2-micro, Debian 12, us-east1-b) is available for future
server-side features — details, standing instructions (wipe the old Hermes
Telegram bot on first use) and ranked use-case plans live in **docs/SERVER.md**.

## Device quirks worth remembering

- Owner's phone: HyperOS (Xiaomi), Android 15, gesture navigation.
- **The app is no longer written for that phone alone.** Per-brand setup advice (Samsung, Xiaomi,
  Huawei, Oppo/OnePlus, Vivo, plus a generic fallback) lives in `data/DeviceVendor.kt`; the
  guard's OEM package lists live in `service/GuardPackages.kt`. Both are unit-tested. Before
  hardcoding anything about a phone, check whether it belongs in one of those two files — and
  read invariant 15 in `docs/BLOCKING_INVARIANTS.md` first.
- **Strict Mode locks *weakening* only.** Every control that makes blocking stronger stays usable
  during a session — arming the off-switch guard, ticking an app on, adding a word, switching on
  any Quick Block extra option. The pattern in the UI is `enabled = ed || !value` (`ed =
  !strictActive`), so a toggle can go on and then locks itself. This has now been got wrong three
  times (v1.127's guard ejecting people for switching protection *on*, the guard row lowerable
  mid-session, the extra options frozen both ways); see invariant 16. Refusing to let someone arm
  a protection is never the safe direction.
  **And "weakening" means what actually gets weaker.** A blocked word whose site is already blocked
  by its app (`SOCIAL_DOMAINS`) can be removed mid-session, because nothing it blocked stops being
  blocked — `StrictEdits.coveredBy`, invariant 19. That exception is only trustworthy because the
  Strict rules now live on the **writes** (`WebFilterViewModel.setKeywords`,
  `AppListViewModel.commitQuickBlock`) rather than on the buttons: the editors stage their lists,
  so a disabled bin never saw a Save that deleted a word added elsewhere. Put a new rule where the
  data changes, not where the user taps.
- **Dialog windows report zero insets while drawing edge-to-edge** on this device —
  never rely on inset modifiers inside a `Dialog`; capture
  `WindowInsets.safeDrawing` in the activity window's scope and pass it in
  (see `DurationPickerDialog` in `ui/WheelPicker.kt`).
