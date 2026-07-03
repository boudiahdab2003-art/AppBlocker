package com.appblocker.data

/** One released version and everything it changed, in plain language. */
data class VersionLog(
    val version: String,
    val date: String,
    val title: String,
    val points: List<String>,
)

/**
 * The app's full story, newest first — shown in Profile ▸ What's new. Written for the owner:
 * every version that ever reached the phone, what it added, and why it mattered.
 */
val changelog: List<VersionLog> = listOf(
    VersionLog("1.48", "Jul 3, 2026", "Coach replies got a face-lift", listOf(
        "The coach's answers are now properly formatted: section headings stand out in bold, lists render with the app's gradient bullet dots, and the key numbers and app names are highlighted.",
        "Weekly reports finally look like reports — scannable sections instead of a wall of text.",
        "Older messages in your chat history keep rendering exactly as before.",
    )),
    VersionLog("1.47", "Jul 3, 2026", "Reports, weekly goals, one-tap questions", listOf(
        "Ask the coach for a proper report: 'Give me my weekly report' gets you a structured, day-by-day breakdown of your week — what went up, what went down, and what it means.",
        "Weekly goals with a plan: ask the coach to set a goal for the week and he proposes one specific, measurable target based on your real numbers, plus a concrete plan — which apps to limit, with which feature, at what setting, and what to check each day.",
        "One-tap suggestions above the message box: starter prompts when you open the chat, then the coach's own suggested follow-ups after every reply — keep the conversation going without typing.",
        "The coach now knows today's date and your exact last 7 days, so 'this week' finally means this week.",
    )),
    VersionLog("1.46", "Jul 3, 2026", "Your coach becomes a real coach", listOf(
        "Chat with your AI Coach: a full conversation screen (Insights ▸ Chat with coach). Ask how you're doing, talk through a rough day, or just check in — he answers using your real numbers.",
        "The coach now knows the whole app: Quick Block, every schedule type, Pomodoro, Shorts blocking, the web filter — and what you already have set up. His advice names real features with real settings instead of generic tips.",
        "Long-term goals, set together: agree on a goal in chat and the coach saves it himself. It appears as a chip in the chat and on the Insights card, and every daily tip starts with your progress toward it.",
        "Your conversation and goals are stored only on your device and survive updates and restarts.",
        "Daily tips got the same upgrade — they now reference your goals and suggest concrete next steps you haven't set up yet.",
        "Fixed: checking for updates in Profile no longer re-shows the big 'Update available' popup over and over — you get it once when the app opens, after that it lives quietly in the Profile row.",
        "New: this page! The full history of every version, right here in the app.",
    )),
    VersionLog("1.45", "Jul 2, 2026", "The AI Coach arrives", listOf(
        "A Gemini-powered AI Coach joined Insights: every day it reads your aggregate stats (screen time, averages, top apps, trends, blocks, unlocks) and writes 2–3 personal tips.",
        "Your free Gemini API key is pasted once in the app and stored only on your device — never inside the app package or online.",
        "Tips are cached for the day, so normal use costs a single free API call per day; if you're offline, yesterday's tips stay available.",
        "Insights row titles were brightened — the muted grey labels were genuinely hard to read.",
        "The Coach card got standout styling: gradient icon, gradient border, soft glow, lightbulb bullets.",
    )),
    VersionLog("1.44", "Jul 2, 2026", "Insights, redesigned to be the best page in the app", listOf(
        "A gradient hero card headlines the page: your screen time, how it compares to your 7-day average or last week, plus unlocks, blocks and strict time at a glance.",
        "Every app list gained comparison bars tinted by category, so you can see at a glance what dominates.",
        "Week-over-week changes are colored: green when you used an app less, red when more.",
        "Each section sits in a glowing card with its own icon; charts animate in; category legend pills under the graph.",
    )),
    VersionLog("1.43", "Jul 2, 2026", "Blocks that catch you mid-scroll", listOf(
        "Mid-use enforcement: limits and schedules now check every 30 seconds while you're INSIDE an app. Hit your daily limit mid-scroll? Blocked on the spot. Schedule starts while you're watching? Blocked. Previously you were only checked when switching apps.",
        "The same check also releases the block automatically when the schedule or timer ends — no more leaving and coming back.",
        "Disabled location schedules no longer keep GPS running in the background — a real battery saving.",
        "New-schedule tiles now adapt to tablets (full-width row on big screens) and got a cleaner look with a '+' badge.",
        "Profile page polished: hero stats (blocked apps, schedules, blocks today), glowing cards, and a proper layout on tablets.",
    )),
    VersionLog("1.42", "Jul 2, 2026", "Lighter on the battery, honest toggles", listOf(
        "Insights reuses the app-icon cache warmed at launch instead of re-decoding ~20 icons every visit.",
        "Website and Shorts scanning is now only scheduled while a browser or YouTube is actually on screen — less background work everywhere else.",
        "Fixed: Profile's 'Prevent uninstall' can now actually be turned OFF (it used to dead-end at Security Settings).",
    )),
    VersionLog("1.41", "Jul 2, 2026", "Insights, four times faster", listOf(
        "Usage statistics are now cached per day (and survive restarts) — Insights builds in a quarter of the time.",
        "One system query for today's stats instead of three identical ones.",
        "Insights refreshes every time you open the tab (it used to go stale after the first look).",
        "The usage-limit blocking check became a cached map read instead of a system call on every app switch — snappier blocking.",
    )),
    VersionLog("1.40", "Jul 1, 2026", "Your name on it, your templates, deeper stats", listOf(
        "Profile now greets you by name — Abdallah Ahdab — with an avatar and a rename option.",
        "Templates are finally yours: an edit pencil on each template card lets you choose exactly which apps it blocks.",
        "YouTube Shorts blocking now starts and stops together with Quick Block.",
        "Insights went deep: a real Trend tab with a 30-day chart, this-week-vs-last-week, weekday-vs-weekend patterns, 'Trending this week' per-app changes, and phone unlocks per day.",
        "The usage graph became interactive — tap or scrub any bar for its exact value; the peak is highlighted.",
        "Tap any app in Insights for a detail sheet: screen time, opens, and block attempts together.",
        "Blocking a social app now auto-blocks its website too (block Instagram → instagram.com blocked in browsers).",
        "'Hypothetical apps': pre-block TikTok, Instagram, Snapchat and friends even before they're installed — they're blocked the moment they arrive.",
        "Usage-limit and Launch-count editors became proper stepper fields — type or step to any value.",
        "Location schedules can save places by name ('UK') and reuse them from a chip list.",
        "Fixed a long-standing bug where the device-admin activation screen closed itself, so 'Prevent uninstall' never actually armed. It works now.",
    )),
    VersionLog("1.39", "Jun 29, 2026", "Shorts blocking, where it belongs", listOf(
        "'Block YouTube Shorts' moved to a nested 'Shorts · BETA' sub-row right under YouTube in the Quick Block app list, with its own checkbox.",
    )),
    VersionLog("1.38", "Jun 29, 2026", "Block YouTube Shorts", listOf(
        "New option: block only the Shorts feed/player inside the YouTube app (and youtube.com/shorts in browsers) while the rest of YouTube keeps working.",
        "For fighting the infinite scroll without losing the useful parts of YouTube.",
    )),
    VersionLog("1.37", "Jun 29, 2026", "A professional Profile page", listOf(
        "Gradient header with the app shield, version, and a live 'Protection active / Action needed' status.",
        "Iconed rows with On/Off badges for PIN and Prevent uninstall, plus a Share AppBlocker option.",
    )),
    VersionLog("1.36", "Jun 29, 2026", "Trends and patterns", listOf(
        "New Trend tab: a 30-day chart, 30-day average, and this-week-vs-last-week comparison.",
        "Patterns card: weekday average vs weekend average.",
        "'Trending this week': how each of your top apps changed vs last week.",
        "Phone unlocks per day joined the Summary.",
    )),
    VersionLog("1.35", "Jun 29, 2026", "The Summary card", listOf(
        "Daily average over 7 days, your busiest day, screen time vs yesterday (up/down %), and a Light/Moderate/Heavy rating for today.",
    )),
    VersionLog("1.34", "Jun 29, 2026", "Touch the graph", listOf(
        "Tap or scrub any bar to read its exact value (like '7 PM — 24m').",
        "The busiest bar is auto-highlighted as the peak; Week shows real weekday names.",
    )),
    VersionLog("1.33", "Jun 29, 2026", "Tap an app, see everything", listOf(
        "Tap any app in Insights for its screen time, opens and block attempts in one sheet.",
        "'Most opened apps' header shows your total opens today.",
    )),
    VersionLog("1.32", "Jun 29, 2026", "Counting your opens", listOf(
        "New 'Most opened apps' section — how many times you opened each app today.",
        "'Most used apps' rows show opens alongside screen time.",
    )),
    VersionLog("1.31", "Jun 29, 2026", "Prevent uninstall, everywhere", listOf(
        "'Prevent uninstall (Device admin)' added to the Setup & permissions checklist.",
        "Fixed the activation screen closing itself — it now arms properly from the checklist, Profile, and Strict Mode.",
    )),
    VersionLog("1.30", "Jun 29, 2026", "Block the app, block its website", listOf(
        "While Quick Block is on, a blocked social app's website is blocked in browsers too — block Instagram and instagram.com goes with it. Stays in sync when you pause.",
    )),
    VersionLog("1.29", "Jun 29, 2026", "Hypothetical apps, refined", listOf(
        "The pre-block list is now social-media only, and each app shows a brand-coloured badge.",
    )),
    VersionLog("1.28", "Jun 29, 2026", "A home for future blocks", listOf(
        "The pre-block section became 'Hypothetical apps' — its own collapsed list inside Quick Block, separate from your installed apps.",
    )),
    VersionLog("1.27", "Jun 29, 2026", "Grok joins the list", listOf(
        "Added Grok to the pre-block popular-apps list.",
    )),
    VersionLog("1.26", "Jun 29, 2026", "Block before you install", listOf(
        "Pre-block popular apps (TikTok, Instagram, Snapchat…) even if they aren't installed — they're blocked the moment you install and open them.",
    )),
    VersionLog("1.25", "Jun 29, 2026", "Limits exactly your way", listOf(
        "Usage limit and Launch count became clean editable fields: hours + minutes steppers, an opens stepper — type any value or use plus/minus.",
    )),
    VersionLog("1.24", "Jun 29, 2026", "Saved places", listOf(
        "Location schedules can save a captured spot under a name (like 'UK') and reuse it from a Saved-places list. Long-press to delete.",
    )),
    VersionLog("1.23", "Jun 29, 2026", "Custom numbers", listOf(
        "Usage limit and Launch count gained an 'Other…' option for any custom value — a 45-minute limit, block after 7 opens, whatever fits.",
    )),
    VersionLog("1.22", "Jun 28, 2026", "Location blocking that works", listOf(
        "Location schedules now guide you to grant 'Allow all the time' location (needed for background blocking) and reliably read where you are.",
    )),
    VersionLog("1.21", "Jun 28, 2026", "Preset buttons, unclipped", listOf(
        "Schedule preset chips now wrap neatly instead of getting cut off.",
    )),
    VersionLog("1.20", "Jun 28, 2026", "Create schedule, always in reach", listOf(
        "The Create-schedule button stays pinned at the bottom while you scroll the app list.",
    )),
    VersionLog("1.19", "Jun 28, 2026", "Human-readable times", listOf(
        "Schedule times read 9:00 AM / 5:00 PM, limits show '30 min / 1 hr' and '10 opens' instead of raw numbers.",
    )),
    VersionLog("1.18", "Jun 28, 2026", "Smart app lists", listOf(
        "App lists load fast thanks to a cache warmed at launch.",
        "Apps are ordered by what's most worth blocking — most-distracting and most-used first — instead of alphabetically.",
    )),
    VersionLog("1.17", "Jun 28, 2026", "Collapsible lists", listOf(
        "The Apps and Websites & words lists in the editors collapse and expand by tapping their headers.",
    )),
    VersionLog("1.16", "Jun 28, 2026", "Version, always visible", listOf(
        "The version number now always shows in Profile ▸ About.",
    )),
    VersionLog("1.15", "Jun 28, 2026", "Updates that come to you", listOf(
        "Automatic 'Update available' prompt on launch with one-tap Update now.",
        "A permanent download link and QR code for first-time installs.",
    )),
    VersionLog("1.7", "Jun 23, 2026", "The app learns to update itself", listOf(
        "In-app updates: AppBlocker checks GitHub, downloads the new version and installs it for you. No more hunting for APK files.",
        "Bundled every fix from v1.2–v1.6 for phones that skipped them.",
    )),
    VersionLog("1.6", "Jun 23, 2026", "Template cards, fixed for good", listOf(
        "Template cards no longer cut off their schedule time at any font size.",
    )),
    VersionLog("1.5", "Jun 23, 2026", "Blocking only where it belongs", listOf(
        "Website/word blocking now only applies inside browsers — typing a blocked word in a chat (or in AppBlocker itself) no longer triggers a block.",
        "Bottom tab labels no longer wrap to two lines.",
    )),
    VersionLog("1.4", "Jun 23, 2026", "Two toggles come alive", listOf(
        "'Add newly installed apps' works: new installs are auto-blocked when it's on.",
        "'In-app purchases blocking' works: blocks the Google Play purchase sheet in games and apps.",
    )),
    VersionLog("1.3", "Jun 23, 2026", "Strict Mode grows up", listOf(
        "You can now ADD protection during Strict Mode — start blocks, timers, schedules, add apps and words. Removing or weakening stays locked until the timer ends.",
        "Activating Strict Mode asks for confirmation and shows exactly how long it will lock.",
        "'Block unsupported browsers' actually works — browsers we can't filter (like Brave) can be blocked so they can't bypass website blocking.",
    )),
    VersionLog("1.2", "Jun 23, 2026", "First fixes from real phone use", listOf(
        "Strict Mode allows adding new schedules while active.",
        "Strict Mode warns instead of activating a pointless lock when nothing is set up.",
        "The strict countdown shows H:MM:SS for locks of an hour or more.",
    )),
    VersionLog("1.1", "Jun 23, 2026", "The timer wheel", listOf(
        "A proper 'Set the timer' wheel picker (days / hours / minutes with a live 'Ends…' preview) for Quick Block Timer and Strict Mode.",
        "Restyled Pomodoro picker with preset cards.",
    )),
    VersionLog("1.0", "Jun 22, 2026", "Where it all began", listOf(
        "The first build installed on a real phone: hard app blocking with an instant block screen, time / usage-limit / launch-count / Wi-Fi / location schedules, un-stoppable Strict Mode, Pomodoro focus sessions, adult-content and keyword web filtering, PIN lock, templates, and Insights.",
        "Built from zero — every version above made it stronger.",
    )),
)
