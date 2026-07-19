# Turning AppBlocker into a paid Google Play app — the master plan

> **What "play version" means (shorthand for us).** When the owner says
> **"play version,"** he means this whole effort: taking the current personal,
> single-user app and turning it into a product a stranger can find on the
> Google Play Store, install, set up for themselves, and pay for. Whenever he
> says it, open this file first and continue from wherever we are.
>
> The owner is **non-technical** — explain in plain language, and remember most
> of the remaining work is *product and business* work, not coding. The hard
> engineering (a real, working, published blocker) is already done.

---

## The honest big picture

- **This is a real project, not a weekend job.** Realistically **weeks to a few
  months**, depending on how far we take payments and testing.
- **Most of it is not code.** It's decisions, accounts, legal text, store
  paperwork, and finding testers. Claude does the code; the owner makes the
  business decisions and owns the accounts (Google won't let Claude hold them).
- **The single biggest gamble is Google's accessibility rule** (next section).
  It's out of our hands and could reject the app no matter how polished. We plan
  around it and keep a fallback, but we can't guarantee it.

**Suggested order:** Phase 1 → 2 first (the app becomes usable by other people
and legal for Play). Then decide payments (Phase 3). Then the store + testing
gate (Phase 4). Legal/support (Phase 5) runs alongside Phase 4. Then launch
(Phase 6). Phase 0 (your decisions) happens before any of it.

---

## ⚠️ The #1 risk: Google's Accessibility Service policy

The whole app works by using Android's **Accessibility Service** to watch the
screen and block apps. Google restricts this hard: they officially want it used
to help people with disabilities, and app-blockers live in a grey zone.

- **Some blockers ARE on Play** (as "digital wellbeing / self-control" tools), so
  it's *possible* — but it requires a clear in-app disclosure, a written
  justification in the Play Console, a matching privacy policy, and a Data Safety
  form.
- **It can still be rejected or removed later**, sometimes without warning.
- **Plan B exists** (last section): if Google says no, we sell it *directly*
  (sideload / license keys) to a specific community, avoiding Google entirely.

We build to maximise approval odds, but we keep the fallback ready.

---

## Phase 0 — Decisions only the owner can make (no code)

These are business choices. Claude will lay out options for each; the owner picks.

### 0.1 App name & brand
- The current name may need to change for a public product: it should be
  **memorable, easy to spell, searchable, and not already trademarked** by
  another app.
- Consider the market fit (a faith/focus name reads differently than a generic
  "blocker").
- **Claude can generate a shortlist of candidate names** and check Play for
  obvious clashes. Owner picks. A new **app icon/brand look** may follow.

### 0.2 Who is the seller (the developer account)
- A **personal Google Play developer account** in the owner's name is fine to
  start (an organisation account needs extra business paperwork).
- Google now requires **identity + address verification** for new accounts
  (photo ID, sometimes a short wait). The owner does this once.
- Country of registration affects **payouts and tax** — the owner's own country.

### 0.3 Price model (pick one to start)
- **One-time price** — simplest; user pays once, owns it. Easiest to build. Lower
  lifetime revenue.
- **Subscription (monthly/yearly)** — best fit for an ongoing service and the
  "accountability partner" feature; needs a small backend and more setup. Highest
  potential revenue.
- **Free + paid upgrade (freemium)** — free core blocking, pay to unlock premium.
  Best for growth (more installs → more word of mouth), monetises a slice.
- **Recommendation:** start **freemium** (free blocking + a one-time "Pro"
  unlock), because in this niche installs and trust come first; add a
  subscription later if the accountability-partner feature proves popular.
- Typical prices in this category: one-time unlocks around **$5–15**, or
  subscriptions around **$3–7/month**. Owner decides.

### 0.4 Audience / positioning (pick the lead angle)
- **Recommended: faith-based / self-discipline** (the English **+ Arabic** word
  pack and the very strict lock-down are a genuine edge, and this market pays for
  exactly this).
- **Alternative: general "digital wellbeing / focus"** — bigger market, far more
  competition, weaker differentiation.
- We can serve both, but the **store listing and marketing should lead with one**.

### 0.5 Budget
- Small but non-zero (itemised in "Money & time" below). Owner confirms he's okay
  spending ~$25 up front plus Google's cut of any sales.

**Owner's to-do in Phase 0:** just think about 0.1–0.5. Nothing technical.

---

## Phase 1 — Make it *everyone's* app (de-personalise + onboarding)

**Goal:** a stranger can install it and make it *theirs*. This unlocks everything
else, and it's the cheapest way to learn whether other people even want it.

### 1.1 Remove the baked-in personal identity
- The owner's name ("Abdallah Ahdab") is currently the default display name in
  the settings. Replace with a neutral default (e.g. "You" / ask on first run).
- Sweep the app for any other "this is Abdallah's phone" assumptions (defaults,
  labels, sample data) and neutralise them.

### 1.2 Build a proper first-run setup wizard
There's already an onboarding screen to extend. A new user should be walked,
step by step, through:
1. **Welcome** — one screen: what the app does and why it's strict (the honest
   pitch).
2. **The accessibility disclosure** — a plain-language screen explaining *exactly*
   why the app needs the Accessibility permission and that it reads screen text
   **only to block, and never uploads it** (this doubles as the Google-required
   disclosure — see 2.2).
3. **Grant permissions** — accessibility, "draw over other apps," and the
   battery-optimisation exemption, each with a simple "why" and a button.
4. **Pick what to block** — choose apps, add blocked words, toggle the adult
   pack (sensible defaults pre-selected).
5. **Set a protection PIN** — so the user can lock their own settings.
6. **Done** — a short "you're protected" confirmation.

### 1.3 Sensible defaults for a brand-new user
- Adult pack on, word-blocking-everywhere on, no apps blocked yet (they choose),
  Strict Mode off until they turn it on. Nothing assumes prior setup.

### 1.4 Make it survive being someone else's phone
- Different launcher, different apps installed, different language — the setup and
  defaults must not break. (The launcher-detection robustness we discussed for
  HyperOS matters here too.)

### 1.5 Real-world test
- Have **one real person besides the owner** install it from scratch and set it
  up with zero help. Watch where they get confused; fix that.

**Who:** Claude writes all the code. **Owner:** tries it, finds a test person,
gives feedback. **Why it matters:** you cannot sell an app that only works for
one person — and a confusing setup kills a paid app instantly.

---

## Phase 2 — Make it Play-legal (compliance hardening)

**Goal:** the build follows Google's rules so it can be listed without instant
rejection.

### 2.1 Switch to the Play build flavor
- The **`play` flavor already exists** and turns off the two things Google
  forbids: **self-updating APKs** (Play delivers updates itself) and
  **background-location schedules** (background location triggers Google's
  heaviest review).
- Consequence: Play users get updates **through the Play Store**, not the in-app
  updater. That's expected and fine.
- First real task: confirm the `play` flavor **builds cleanly** and behaves.

### 2.2 The accessibility disclosure + declaration (make-or-break)
- **In-app:** a clear screen shown *before* asking for the permission, stating
  what the Accessibility Service does and doesn't do (built in Phase 1.2).
- **In the Play Console:** a **Permissions Declaration** justifying accessibility
  use as a user-facing self-control/wellbeing tool, plus the correct metadata
  flag describing the app's accessibility purpose.
- Claude drafts all wording; owner reviews. This is the section most likely to
  decide approval, so we get it right and keep evidence that other similar apps
  are permitted.

### 2.3 Device-admin "resist uninstall"
- The current app can use **device-admin** to make itself hard to uninstall.
  Google **restricts device-admin** heavily and it can cause rejection.
- **Decision:** likely **remove or soften** device-admin in the Play build. The
  **Strict-Mode PIN and the settings-guard still give strong protection** without
  it. (The sideload/Plan-B build can keep the stronger version.)

### 2.4 Permissions review
- Go through every permission the app requests and make sure each is justified
  and Play-compliant. (Good news: the app already avoids the "see all apps"
  mega-permission Google dislikes.)

### 2.5 Privacy policy
- Write a plain, honest policy. **Strong selling point: everything runs
  on-device, nothing is uploaded or sold.** It must specifically say the
  Accessibility Service reads on-screen text *only to detect blocked content* and
  that this text is **never stored or transmitted**.
- **Host it free** (e.g. a simple GitHub Pages page). Google requires a public
  link.

### 2.6 Data Safety form (in the Play Console)
- A questionnaire declaring what data the app collects/shares. For us that's
  essentially **nothing off-device** — a clean, trust-building answer. Claude
  fills the draft; owner submits.

### 2.7 Technical Play requirements
- **Target the recent Android version** Google requires that year.
- Provide the app as an **App Bundle** (Google's required upload format) — a
  build-config change Claude handles.

### 2.8 Content rating
- Fill Google's content-rating questionnaire. The app is a *tool* that blocks
  adult content (it doesn't show any), so it typically lands at a low rating, but
  we answer honestly.

**Who:** Claude (code + drafting policy/forms). **Owner:** host the policy link,
review wording, submit the Console forms (Claude guides each click).

---

## Phase 3 — Add a way to pay (monetization)

**Goal:** money can actually change hands, cleanly and within Google's rules.

### 3.1 Decide the free vs paid split
- Concrete suggestion (freemium): **free** = core app blocking, word blocking,
  schedules. **Pro (paid)** = the **accountability partner** feature, extra word
  packs, and advanced options. This gives a real reason to pay without crippling
  the free app.

### 3.2 Integrate Google Play Billing
- Google **requires its own billing system** for digital purchases and takes
  **~15%** of the first $1M/year (30% above that).
- Claude adds the billing code; the owner **creates the product(s)** (the "Pro
  unlock" or subscription) in the Play Console. Claude guides every field.

### 3.3 Backend — only if we do subscriptions / accountability partner
- A one-time "Pro unlock" needs **no server**. A **subscription** or the
  **accountability-partner** feature (a chosen person is alerted if you try to
  disable protection — the killer feature in this niche) needs a **small backend**
  to verify who paid and to send those alerts.
- The project already has a **free Google Cloud VM** (see `docs/SERVER.md`) we can
  use, so hosting is ~free at small scale.

### 3.4 Test the purchases
- Google provides a **sandbox / license-tester** mode so we can test buying,
  refunding, and subscribing **without real money** before launch.

### 3.5 Get paid
- The owner adds a **bank account and tax details** in the Play Console so Google
  can pay out sales. One-time setup.

**Who:** Claude (billing + backend code). **Owner:** create paid products, connect
bank/tax.

---

## Phase 4 — The store listing & Google's testing gate

**Goal:** the app is live on Google Play.

### 4.1 Create the developer account
- **$25 one-time fee**, paid by the owner. Requires **ID/address verification**;
  approval can take a couple of days.

### 4.2 Build the store listing (Claude drafts everything, owner approves)
- **App title** (short), **short description** (one line), **full description**
  (the sales pitch, keyword-optimised so people find it).
- **Screenshots** (Claude can generate polished ones), a **feature graphic**
  banner, the **512×512 icon**, and the **category** (Tools / Health & Fitness /
  Lifestyle).
- **Keywords/ASO:** we choose words people actually search ("block porn",
  "focus", "self control", "detox") so the app is discoverable.

### 4.3 Content rating & Data Safety
- Submit the questionnaires from Phases 2.6 and 2.8.

### 4.4 ⚠️ Google's 14-day tester gate (important & surprising)
- New **personal** developer accounts must run a **closed test with at least ~12
  testers for 14 continuous days** *before* Google lets you publish publicly.
- Practically: line up **~15–20 people** (friends, family, community members)
  willing to install it and keep it for two weeks. They opt in by email or a
  Google Group.
- **Plan this early** — it's often the slowest, most surprising step. The
  faith/focus community is a natural place to find willing testers.

### 4.5 Release tracks (how Google rolls it out)
- **Internal test** (a handful of people, instant) → **Closed test** (the 14-day
  gate) → **Open test** (anyone can try) → **Production** (public on Play).
- Each submission is **reviewed by Google**, which can take **days to a couple of
  weeks**.

**Who:** Claude (all listing text, screenshots, upload guidance). **Owner:**
create the account, recruit testers, click the Console buttons (Claude walks
through each one).

---

## Phase 5 — Legal & support (the unglamorous but required 20%)

### 5.1 Privacy policy
- Finalised and hosted (from Phase 2.5). Required by Google.

### 5.2 Terms of use
- A short "use at your own responsibility" document. Optional but recommended
  (Claude drafts).

### 5.3 Support channel
- A simple **support email** and a short **FAQ / help page** for "it stopped
  working" questions. Google requires a contact method on the listing.

### 5.4 Refunds
- Google auto-refunds within a **short window** after purchase and handles most
  refund requests. The owner just decides a basic stance for anything beyond that.

### 5.5 Data requests
- Even though data stays on-device, Play expects a way for users to **request
  deletion**. Easy for us — "uninstalling removes everything" — but it must be
  stated.

**Who:** Claude drafts the documents; owner provides the support email and hosts
the pages.

---

## Phase 6 — Launch & grow

### 6.1 Soft launch to the niche
- Share where the target users already are: relevant **Reddit communities**,
  **faith/self-improvement forums and Discords**, **word of mouth**, and short
  videos. Start small and real, not a big splashy launch.

### 6.2 Get early reviews
- Ratings drive Play ranking. Politely ask happy early users to **rate and
  review** (an in-app "enjoying it? rate us" nudge helps).

### 6.3 Watch and fix
- The Play Console shows **crashes and problems** ("Android vitals"). Fix the top
  issues fast; early stability builds trust and ratings.

### 6.4 Track the numbers that matter
- **Installs**, **retention** (do people keep it after a week?), and **conversion**
  (what % buy Pro). These tell us if it's working — and are exactly what a future
  **acquirer** would want to see (ties back to the "sell to a company" idea).

### 6.5 Grow deliberately
- Improve the listing keywords (**ASO**), consider a small ad test, and lean into
  the niche's own channels. Content ("how I quit doom-scrolling") markets a
  detox app well.

**Who:** mostly owner (posting, talking to users), with Claude on fixes,
listing updates, and analysis.

---

## What Claude does vs. what the owner does

| Claude (technical + drafting) | Owner (decisions + accounts) |
| --- | --- |
| De-personalise, build onboarding wizard | Choose name, price, audience (Phase 0) |
| Play-flavor build, disclosures, App Bundle | Create Google developer account ($25) + verify ID |
| Draft privacy policy / terms / listing text | Host the privacy-policy page, provide support email |
| Payments (billing) + backend code | Set up paid products, connect bank + tax |
| Generate screenshots & store graphics | Recruit ~15–20 testers for the 14-day test |
| Walk through every Play Console click | Press the buttons; own the account & payouts |

---

## Rough money & time

- **Google Play developer account:** ~**$25 one-time**.
- **Google's cut of sales:** ~**15%** (first $1M/year), 30% above.
- **Backend server:** ~**free** (existing Google Cloud free-tier VM) unless usage grows.
- **Privacy-policy / support pages:** free hosting options exist.
- **Optional ad budget:** the owner's choice, can be $0 to start.
- **Time:** Phase 1–2 first (a few weeks of on/off work). Then payments, then the
  **~2-week tester gate**, then Google review (days–weeks), then launch. Overall
  **weeks to a few months**, most of it waiting and paperwork rather than coding.

---

## Fallback if Google rejects it (Plan B)

If the accessibility policy blocks us, we **don't give up** — we switch to
**direct distribution**: sell the app as a **sideloaded download** (or via an
alternative Android store) with **license keys** instead of Play Billing. This
build can even keep the *stronger* protections (self-update, device-admin) that
Play forbids. Less mainstream reach, **zero Google gatekeeping**, and the
faith/focus niche is comfortable sideloading exactly this kind of tool. A real,
viable path — not a consolation prize.

---

## Where we are right now (update as we go)

- **Status:** Detailed plan written and saved. No product work started yet.
- **Next step when the owner says "play version":** begin **Phase 1**
  (de-personalise + build the onboarding wizard) — it unlocks everything else and
  reveals whether other people want the app before we invest in payments and
  store paperwork.
- **Open Phase-0 decisions still to make:** app name, price model, lead audience.
