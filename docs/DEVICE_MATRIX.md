# Which phones this has actually been measured on

**Everything in this file is evidence or it is blank.** A row here means the device probe ran on
that hardware and said so. A row does not mean someone reasoned that it ought to work — that
belief already exists in the code, and `PhoneReport.kt` opens by admitting how much of it was
never checked:

> Everything the app knows about non-Xiaomi phones was reasoned rather than measured: that Samsung
> routes an uninstall through `com.samsung.android.packageinstaller`, that Device Care sits at a
> particular activity, that Oppo's browser keeps Chromium's toolbar id. Each guess is defensible
> and each one **fails silently**.

That is what this table is for. Not a support matrix to show people — a record of which guesses
have stopped being guesses.

## The state of it today

| Phone | Android | Uninstall screen | Keep-alive page | `<queries>` visible | Browsers read | Measured |
|---|---|---|---|---|---|---|
| Xiaomi (owner's, HyperOS) | 15 | ✅ `com.miui.packageinstaller` | ✅ MIUI security centre | ✅ | ✅ Mi Browser, read 14 Aug 2026 | in use daily |
| Emulator `appblocker_test` | 14 | ✅ AOSP installer | — generic advice, no link | ✅ | Chrome only | every release |
| Emulator `appblocker_a15` | 15 | ✅ `com.google.android.packageinstaller` | — generic advice, no link | ✅ all 7 | Chrome only | 22 Aug 2026 |
| **Samsung** Galaxy S24 FE (SM-S721B, EEA) | 15 / One UI 7.0 | ✅ but **`com.google.android.packageinstaller`** — Samsung's own installer is *not installed here* | ✅ `com.samsung.android.lool/…sm.battery.ui.BatteryActivity` (the **second** of the two guesses) | ✅ all 7 | ❌ **Samsung Internet not installed on the lab unit** — claim still unproven | 22 Aug 2026, Remote Test Lab |
| **Samsung** Galaxy A36 5G (SM-A366N, **Korea**) | **16 / One UI 8.0.5** | ✅ **`com.google.android.packageinstaller` again** — so the S24 FE result is not an EEA quirk | ✅ same `…lool/…BatteryActivity`, on One UI 8 too | ✅ all 7 | ✅ **Chrome read here**, `instagram.com` covered by the site layer | 23 Aug 2026, Remote Test Lab |
| **Huawei / Honor** | — | ❓ guessed | ❓ guessed | ❓ | ❓ claim unproven | **never** |
| **Oppo / Realme / OnePlus** | — | ❓ guessed | ❓ guessed | ❓ | ❓ claim unproven | **never** |
| **Vivo / iQOO** | — | ❓ guessed | ❓ guessed | ❓ | ❓ claim unproven | **never** |

**Samsung is now measured twice** — two models, two countries, two Android versions — and the two
runs agree on every column they share. The uninstall-screen answer in particular is no longer a
one-unit surprise: Samsung routes uninstalls through **Google's** package installer on both, so
`com.samsung.android.packageinstaller` in `GuardPackages.INSTALLERS` has never fired on a real
Samsung yet. Three brands remain entirely unseen, and those three bold rows are the work.

**The one Samsung question that stays open is Samsung Internet**, and it cannot be closed here:
Remote Test Lab images do not contain it — see `REMOTE_TEST_LAB.md`, where the Korean unit proved
it is absent rather than merely disabled. It will be answered by the first profile report from a
real Samsung owner, not by another booking.

## How a row gets filled in

Run `DeviceProbeTest` on the phone. It answers the first four columns by itself:

```
gradle -p . :app:connectedGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.appblocker.DeviceProbeTest
```

Three of its checks are allowed to fail, and each failure names its own one-line fix:

- **the uninstall screen** — a dialog from a package `GuardPackages.INSTALLERS` does not watch
  means Strict Mode cannot stop an uninstall on that phone.
- **the keep-alive page** — a deep link that resolves nowhere means the button opens the app's own
  settings page while its label promises the OEM's battery screen.
- **`<queries>` visibility** — an OEM package that is installed but invisible to us. This is the
  one that was true of the MIUI link for the app's entire life, and it is invisible from a JVM
  test because "not installed" and "filtered out" are the same exception. The probe compares what
  the *shell* sees against what the *app* sees, which is what tells those two apart.

The fourth check never fails. It prints the device profile — brand, Android version, installed
browsers, and which browsers we *claim* we can read versus which have actually demonstrated it —
into the logcat and into `device-probe.txt` in the app's external files dir, so a CI job or a
device farm run carries it home.

## What the probe cannot tell you

Two things, and both matter more than anything in the table.

**Whether the phone kills the blocker after a few days.** Samsung's "Put unused apps to sleep" is
on by default and is the single most likely thing to switch blocking off, and no test session
lasting twenty minutes will ever see it. That needs the app on a real phone in a real pocket, and
the thing that reports it is v1.135's stalled alert, not this file.

**Whether a browser's address bar is really readable.** `KNOWN_READABLE_BROWSERS` claims the OEM
browsers keep Chromium's `url_bar`, and its own comment concedes that if one renamed that id "the
claim is wrong in the silent direction" — not blocked, not filtered either. Proving it needs the
browser open on a blocked site with the service running. The probe reports which unproven claims a
given phone is leaning on; it does not settle them. **A "claim unproven" row is not a passing row.**
