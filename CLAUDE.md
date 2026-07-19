# AppBlocker — project memory

Personal Android app blocker (Kotlin, Jetpack Compose, accessibility-service
enforcement). Owner is non-technical; explain things in plain language.

## Workflow (established)

- **No local builds** in cloud sessions (Google Maven is blocked). Compile-verify by
  pushing: the **"Build check"** GitHub Actions workflow runs `assembleGithubDebug`
  on every push.
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
