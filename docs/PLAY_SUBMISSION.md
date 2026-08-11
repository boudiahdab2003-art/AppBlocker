# Play Console paperwork — drafts to review

> **What this is.** The Google Play Console asks a series of written questions before it
> will list an app. None of it is code, all of it is drafted here so it is ready the day
> the developer account exists.
>
> **Read it before you paste it.** Every answer below is a claim *you* are making to
> Google about your own app. They are all true of the current build as far as I can
> verify from the source, and each one says where I checked — but you are the one who
> signs them, so read them as statements you'd be comfortable defending.
>
> **Nothing here can be submitted yet.** It needs the Play developer account from
> Phase 4.1 of `PLAY_VERSION_PLAN.md` ($25, one-off, plus ID verification).
>
> Related: `docs/privacy-policy.md` (the hosted document these answers point at) and
> `docs/PLAY_VERSION_PLAN.md` (the whole plan).

---

## 1. Accessibility permissions declaration

This is the make-or-break one. Google restricts `AccessibilityService` because it was
built for users with disabilities, and they ask every app that uses it to justify
itself. Blockers live in a grey zone: some are on Play, some get rejected.

**Console question: "What is your app's core functionality?"**

> AppBlocker is a self-control and digital-wellbeing tool. Its core function is to help
> a person stop themselves opening apps and websites they have chosen to block —
> app blocking, scheduled blocking, screen-time limits and keyword/site filtering,
> with optional self-imposed restrictions (a PIN, a strict-mode timer) that make it
> deliberately hard to undo a decision in a weak moment.

**Console question: "Which permissions does your app use, and why is each necessary?"**

> AppBlocker uses `AccessibilityService` because it is the only API Android offers that
> can tell an app which app is currently in the foreground, and read the address bar and
> page text of a web browser, at the moment they change. Both are required for the app's
> core function and neither can be achieved another way:
>
> 1. **Foreground app detection.** To cover a blocked app with a blocking screen, the app
>    must know the instant that app comes to the foreground. `UsageStatsManager` reports
>    usage after the fact and at coarse granularity, which is too late to block anything.
>    We use `TYPE_WINDOW_STATE_CHANGED` to react immediately.
> 2. **In-browser site and keyword blocking.** To block a website or a search term the
>    user has chosen, the app reads the URL bar and visible page text inside web browser
>    apps only, and compares them against the user's own blocklist. Android provides no
>    other way for a third-party app to see what page a browser is displaying.
>
> The service reads content only for these comparisons. Screen content is never stored,
> never written to disk, never logged, and never transmitted. There is no server, no
> account and no analytics in the app. Nothing read from the screen leaves the device.

**Console question: "Is the accessibility use disclosed to users?"**

> Yes. Before the app sends a user to Android's Accessibility settings, it shows a
> full-screen disclosure ("How blocking works") that must be explicitly accepted. It is
> shown at every point in the app that can lead to the Accessibility settings page. The
> user can decline; the rest of the app continues to work and blocking simply does not
> run. The same explanation is also in the service's own `android:description`, which
> Android displays on the system Accessibility page, and in the privacy policy.
>
> *(Source: `ui/AccessibilityDisclosureScreen.kt`, which names all four entry points;
> `gatedFix` in `ui/Permissions.kt`; `res/values/strings.xml`'s
> `accessibility_description`.)*

**Exact in-app disclosure wording**, so the Console answer and the app agree — copy it
from `ui/AccessibilityDisclosureScreen.kt` if it is ever edited:

> **What AppBlocker can see.** Blocking only works if the app can tell what's on your
> screen. Here is exactly what that means, before you're asked to allow it.
>
> *What it reads:* Which app is in front — so the moment you open something you've
> blocked, the block screen can cover it. In web browsers only, the address and the text
> on the page — so a blocked site or a blocked word is caught in the browser too, not
> just in apps. Outside browsers, AppBlocker only looks at which app is open.
>
> *What never happens:* It never leaves your phone — every check happens on this device,
> screen content is never stored and never sent anywhere. There's no account and no
> server. The AI Coach never sees your screen. It's only used for blocking — no ads, no
> analytics, no tracking.

**Also needed:** the app must declare `isAccessibilityTool="true"` in
`res/xml/accessibility_service_config.xml` **only if** it genuinely serves users with
disabilities. **It does not**, so that flag must stay off and the justification above is
what carries the submission. Claiming otherwise would be the single fastest way to get
the account in trouble.

---

## 2. Data Safety form

Play's questionnaire about what the app collects and shares. For this app the answers
are unusually clean, and that is worth stating precisely.

| Question | Answer | Why |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **No** | Nothing is transmitted off the device by the app's core features. |
| Is all user data encrypted in transit? | N/A — no data is transmitted | Except the optional AI Coach, below. |
| Do you provide a way for users to request data deletion? | **Yes** | Uninstalling removes everything; there is no server-side copy. Stated in the privacy policy. |
| Location | Not collected | Location schedules are **excluded from the Play build** (`Dist.LOCATION_SCHEDULES = false`) and location is never transmitted in any build. |
| Personal info (name) | Not collected | The optional display name is stored on-device only and never leaves the phone. |
| App activity / screen content | Not collected | Read transiently for blocking comparisons, never stored or sent. |
| Financial info, health, contacts, messages, photos, files, calendar | Not collected | The app does not access them. |
| Crash logs / diagnostics | **See note** | The in-app "Report a problem" sends a report only when the user writes one and presses Send. |

**Two things to declare honestly rather than round down to "nothing":**

1. **Bug reports.** When a user chooses to send one, the app transmits the report text
   they typed plus app version, Android version, phone model and a summary of their
   settings. It is built from a named allow-list and deliberately never includes blocked
   words, sites or app names (`data/BugReport.kt`, and `BugReportTest.kt` asserts it).
   Declare as: *Diagnostics — collected, not shared, optional, user-initiated.*
   **Decide before submitting** whether the Play build ships with reporting enabled at
   all; it is off unless `REPORT_URL` is set at build time.
2. **The AI Coach.** Off until the user pastes their own Google Gemini API key. When on,
   it sends screen-time totals, app names with usage minutes, the user's blocking setup,
   goals, remembered personal facts and chat messages to Google's Gemini API. It never
   receives screen content, browsing pages, the PIN or location. Declare as: *App
   activity and user-generated content — collected and shared with a third party
   (Google), optional, off by default.*
   **Decide before submitting** whether the Play build includes the coach; if it does,
   this section of the form must be filled in and the listing should say the feature is
   optional and needs the user's own key.

---

## 3. Content rating questionnaire

Answer honestly; the app is a tool that *blocks* adult content and never displays any.

| Topic | Answer |
| --- | --- |
| Violence, sexual content, nudity, profanity | **No** — the app displays none. |
| Does the app contain references to drugs, alcohol or tobacco? | **No.** |
| Gambling | **No.** |
| Does the app share the user's location with other users? | **No.** |
| Does the app allow users to interact or communicate? | **No** — there is no social feature and no user-to-user contact. |
| Does the app contain user-generated content? | **No** in the shared sense; the user's own blocklist and coach chat are private to their device. |
| Does the app let users purchase digital goods? | **Not yet** — answer changes if/when Play Billing is added (Phase 3). |
| Adult themes | The app's *description* references blocking pornography, and the built-in word pack contains explicit terms **that are never displayed to the user** — they are matched against, not shown. Mention this in the free-text box rather than hiding it; a reviewer who finds it unmentioned will assume the worst. |

Expected outcome: a low rating (Everyone / PEGI 3–7), possibly Teen depending on how the
adult-content-blocking description is read.

---

## 4. Before any of this is submitted

- [ ] Developer account created and ID-verified (`PLAY_VERSION_PLAN.md` Phase 4.1).
- [ ] **Confirm the privacy policy is actually reachable** at
      <https://boudiahdab2003-art.github.io/AppBlocker/privacy-policy>. The README and
      the in-app link both point there; if GitHub Pages is not switched on for this
      repo it is one toggle in Settings → Pages (source: `master`, folder `/docs`).
      Google will not accept a listing whose policy link 404s.
- [ ] Decide whether the Play build keeps **device-admin "Prevent uninstall"**. Google
      restricts device-admin heavily and it is a plausible rejection cause;
      `PLAY_VERSION_PLAN.md` 2.3 leans towards removing it from the Play build, where
      the PIN and Strict Mode still provide real protection.
- [ ] Decide the two "decide before submitting" questions above (bug reporting, AI Coach).
- [ ] Build an **App Bundle** rather than an APK (Play's required upload format).
