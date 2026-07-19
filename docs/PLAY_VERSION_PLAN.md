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
  business decisions and owns the accounts.
- **The single biggest gamble is Google's accessibility rule** (see below). It's
  out of our hands and could reject the app no matter how polished it is. We plan
  around it, but we can't guarantee it.

---

## ⚠️ The #1 risk: Google's Accessibility Service policy

The whole app works by using Android's **Accessibility Service** to watch the
screen and block apps. Google restricts this hard: they officially want it used
to help people with disabilities, and app-blockers live in a grey zone.

- **Some blockers ARE on Play** (as "digital wellbeing / self-control" tools), so
  it's *possible* — but it requires:
  - A clear **in-app disclosure screen** explaining exactly why we use it.
  - A **Permissions Declaration** form in the Play Console justifying it.
  - A matching **privacy policy** and **Data Safety** form.
- **It can still be rejected or removed later**, sometimes without warning.
- **Plan B exists** (see the last section): if Google says no, we sell it
  *directly* (sideload / outside Play) to a specific community. That path avoids
  Google entirely.

Bottom line: we build to maximise the chance of approval, but we keep a fallback.

---

## Phase 0 — Decisions only the owner can make (no code)

Before building, the owner decides (Claude will explain each option when asked):

1. **App name & brand** — the current name may need to change for a public
   product (e.g. something that reads well in the faith/focus market).
2. **Who's the seller** — a personal Google developer account is fine to start.
   Registered under the owner's name/country.
3. **Price model** — one of:
   - **One-time price** (simplest).
   - **Subscription** (monthly/yearly — best fit for an "accountability partner"
     feature, but needs a small backend).
   - **Free + paid upgrade** (freemium).
4. **Target audience** — recommended niche: **faith-based / self-discipline**
   users (the English + Arabic word pack and strict lock-down are a real edge).
5. **Budget** — small but non-zero (see "Money & time" below).

**Owner's to-do here:** just think about #1–#5. Nothing technical.

---

## Phase 1 — Make it *everyone's* app (de-personalise + onboarding)

**Goal:** a second person can install it and make it *theirs*.

- Remove the hardcoded owner identity (the name "Abdallah Ahdab" is baked in).
- Build a **first-run setup wizard**: welcome, what the app does, the
  accessibility disclosure + permission steps, pick blocked apps/words, done.
- Sensible **default settings** for a brand-new user.
- Make sure nothing assumes "this is Abdallah's phone."

**Who:** Claude (code). **Owner:** try it, give feedback.
**Why it matters:** this is the prerequisite for *everything* else — you can't
sell an app that only works for one person.

---

## Phase 2 — Make it Play-legal (compliance hardening)

**Goal:** the build follows Google's rules.

- **Use the `play` build flavor** — it already exists and turns off the two
  things Google forbids: self-updating APKs and background-location schedules.
- **Accessibility disclosure** — add the required in-app screen + prepare the
  Play Console declaration (see the #1 risk section).
- **Device-admin "resist uninstall"** — Play restricts this API. Likely we
  **remove or soften it** for the Play build (the Strict-Mode passcode still
  gives strong protection without it).
- **Privacy policy** — write one. Strong selling point: **everything runs
  on-device, nothing is uploaded.** Host it on a simple free web page.
- **Data Safety form** — declare we collect essentially nothing off-device.
- **Target the latest Android version** Google requires that year.

**Who:** Claude (code + drafting the policy text). **Owner:** host the policy
link, review wording.

---

## Phase 3 — Add a way to pay (monetization)

**Goal:** money can actually change hands.

- Integrate **Google Play Billing** (Google's required payment system for
  in-app sales — they take ~15% of the first $1M/year).
- Set up the product/subscription in the **Play Console**.
- If we choose the **accountability-partner** premium feature (someone gets
  alerted if you try to disable protection — the killer feature in this niche),
  that needs a **small backend server**. The project already has a free
  Google Cloud VM noted in `docs/SERVER.md` we can use.
- A simple **"free vs premium"** split so there's a reason to pay.

**Who:** Claude (code + backend). **Owner:** create the paid products in the
Play Console, connect a bank account for payouts.

---

## Phase 4 — The store listing & Google's testing gate

**Goal:** the app is live on Play.

- **Google Play Developer account** — one-time **$25** fee, registered by the
  owner.
- **Store listing** — title, description, screenshots, feature graphic, icon,
  content rating questionnaire, Data Safety form. Claude drafts all text and can
  generate polished screenshots; owner approves.
- **⚠️ Google's testing requirement (important & surprising):** new **personal**
  developer accounts must run a **closed test with ~12–20 real testers for 14
  continuous days** *before* they're allowed to publish publicly. So we'll need
  to line up **~20 people** (friends, family, community) willing to install and
  keep it for two weeks. Plan for this early.
- Then: **closed test → open test → production.**

**Who:** Claude (listing text, screenshots, technical uploads guidance).
**Owner:** create the account, recruit the ~20 testers, hit the buttons in the
Console (Claude walks him through each click).

---

## Phase 5 — Legal & support (the unglamorous 20%)

- **Privacy policy** + basic **terms of use** (Claude drafts).
- **Support channel** — a simple email address for "it broke on my phone."
- **Refunds** — Google handles most, but decide a stance.

**Who:** mostly owner decisions; Claude drafts the documents.

---

## Phase 6 — Launch & grow

- **Soft launch** to the niche (faith/focus communities, forums, word of mouth).
- Collect reviews, watch what breaks, fix fast.
- **Traction is the real prize:** even a few hundred engaged users make the app
  worth far more — that's also what would make a *company* interested later
  (see the earlier conversation about selling to a company).

---

## What Claude does vs. what the owner does

| Claude (technical + drafting) | Owner (decisions + accounts) |
| --- | --- |
| De-personalise, onboarding wizard | Choose name, price, audience |
| Play-flavor compliance, disclosures | Create Google developer account ($25) |
| Privacy policy / terms / listing text | Host the privacy-policy web page |
| Payments + backend code | Set up paid products, bank payout |
| Screenshots, store graphics | Recruit ~20 testers for the 14-day test |
| Walk through every Console click | Press the buttons, own the account |

---

## Rough money & time

- **Google Play developer account:** ~**$25 one-time**.
- **Google's cut of sales:** ~**15%** (first $1M/year).
- **Backend server:** ~**free** (existing Google Cloud free-tier VM) unless usage grows.
- **Privacy-policy hosting:** free options exist.
- **Time:** Phase 1–2 first (the app becomes usable by others), then payments,
  then the ~2-week testing gate, then launch. Weeks-to-months overall.

---

## Fallback if Google rejects it

If the accessibility policy blocks us, we **don't give up** — we switch to
**direct distribution**: sell the app as a sideloaded download (or via an
alternative store) to a specific community, with license keys instead of Play
Billing. Less mainstream reach, zero Google gatekeeping, and the faith/focus
niche is used to sideloading exactly this kind of tool. This is a real,
viable Plan B, not a consolation prize.

---

## Where we are right now (update as we go)

- **Status:** Plan written. No product work started yet.
- **Next step when the owner says "play version":** begin **Phase 1**
  (de-personalise + onboarding) — it unlocks everything else and reveals whether
  other people even want the app before we invest in payments and store paperwork.
