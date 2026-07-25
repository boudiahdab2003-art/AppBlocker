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
- **Releases are cloud-published**: merge to `master`, then trigger the
  **"Publish release"** workflow (`publish.yml`) with a plain-language release note —
  it bumps the version, builds the signed APK (key in repo secrets, fingerprint
  verified), updates CHANGELOG.md, tags, and publishes the GitHub release the
  in-app updater reads. **Only publish when the owner says "publish".**
- CHANGELOG.md entries are written by the publish workflow — don't hand-edit
  version sections.
- Develop on the session branch, merge to `master` after the Build check is green.

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
- **Dialog windows report zero insets while drawing edge-to-edge** on this device —
  never rely on inset modifiers inside a `Dialog`; capture
  `WindowInsets.safeDrawing` in the activity window's scope and pass it in
  (see `DurationPickerDialog` in `ui/WheelPicker.kt`).
