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
    VersionLog("1.68", "Jul 12, 2026", "Insights reordered", listOf(
        "The Insights page flows better now: your AI Coach moved up right after the usage cards, and Focus, Distractions and the Mood check-in close out the page at the bottom.",
    )),
    VersionLog("1.67", "Jul 11, 2026", "Apps organized into categories", listOf(
        "The app lists in Quick Block, schedules and templates are now organized into 12 categories — Social media, Entertainment, Games, News & Books, Shopping & Food, Creativity, Travel, Utilities, Education, Health & Fitness, Productivity and Other — just tap a category to open it.",
        "Block a whole category with one tap: every category has its own checkbox. A dash on the checkbox means some (but not all) of its apps are selected.",
        "AI sorted over 270 well-known apps into the right categories — including the apps popular in Germany and the Arabic world — and any app it doesn't know is placed using the category its developer declared.",
        "Even better: if you've connected the AI Coach, YOUR phone's own apps are sent to Gemini once and it files every single one into the right category automatically — the answer is remembered forever, and newly installed apps get categorized the same way.",
        "Searching still works exactly as before: type a name and you get a simple flat list of matches.",
        "The AI Coach got a brain upgrade: it now runs on a much newer Gemini model, answers noticeably faster (it no longer silently 'thinks' for seconds before replying), and long chats stay quick.",
        "The coach also sees much more of your day now: your busiest hour, time per category, most-opened apps, which blocked apps tempted you (and how often), your longest phone-free stretch, notifications, minutes reclaimed — and your daily mood check-ins, so it knows how the day actually FELT.",
        "Coach tips refresh every hour now (was every 3), so the advice keeps up with your day as it happens.",
    )),
    VersionLog("1.66", "Jul 10, 2026", "Choose your icon", listOf(
        "The app icon's shield is now a little smaller, so it sits beautifully inside the round icon shape instead of touching the edges.",
        "New: Profile → Appearance → App icon — pick your favourite from six AI-designed icons: Halo glow, Violet night, Pure black, Daylight, Bold silhouette, and Shield & lock.",
        "Your home screen may take a few seconds to show the new icon after switching — that's your phone's launcher refreshing, not a bug.",
    )),
    VersionLog("1.65", "Jul 10, 2026", "A beautiful new block screen", listOf(
        "The block screen is completely reimagined as an editorial poster: a huge, beautiful serif quote owns the screen — 50 hand-picked lines about focus, discipline and time, from Marcus Aurelius and Seneca to your coach's own words. A fresh one every time, right at the moment of temptation.",
        "A giant 'minutes reclaimed today' counter crowns the screen — every blocked open counts as ~3 minutes of your life back, so the number grows with every temptation you dodge.",
        "Everything else steps back: a small BLOCKED badge up top, the blocked app in a quiet footer, studio-light glows on a near-black backdrop, and a blue→violet 'Got it' button.",
        "A brand-new app icon — a glowing gradient shield designed by AI to match the app's look, after being shown the app itself.",
        "Turning off the Adult content pack is now deliberately hard, so it can't happen on impulse. When you switch it off, AppBlocker asks you to type out a long paragraph of random words — exactly, by hand. Pasting is disabled.",
        "There's also a 2-minute cooldown: even once you've typed the paragraph, the Turn-off button stays locked until the timer runs out.",
        "The paragraph is different every time, so it can't be memorised, and turning the pack back ON is always instant.",
        "This only affects switching the pack off outside Strict Mode — during a Strict session it stays fully locked as before.",
    )),
    VersionLog("1.64", "Jul 9, 2026", "Built-in adult word pack", listOf(
        "Blocked words now come with a built-in adult content pack: hundreds of pornographic and fetish words — in English AND Arabic — blocked out of the box, on top of your own list.",
        "It's on by default. One switch on the Blocked words screen controls it, and like your other protections, it can't be switched off while Strict Mode is running.",
        "The pack matches whole words only, so everyday words are never caught by accident — 'analysis' or كسر will never trigger a block.",
        "Arabic matching is smart about spelling: different alef forms, diacritics and stretched letters (like سِكْس or سـكـس) are all caught the same.",
        "The blocked screen never repeats the word it caught — it just says adult content was blocked.",
    )),
    VersionLog("1.63", "Jul 9, 2026", "Light mode + richer Insights", listOf(
        "AppBlocker now has a light theme. Choose it in Profile → Appearance: System default (follows your phone), Light, or Dark.",
        "System default automatically matches your phone — so if your phone switches to dark at night, the app does too.",
        "The block screen stays dark on purpose — it's a full-screen stop sign and reads best that way.",
        "Insights gained three new cards: Balance (how much of your waking day was screen time), Peak time (your busiest hour), and a Productive / Distracting / Neutral split of your usage.",
        "Insights also added Focus (your longest phone-free stretch and longest single session), Distractions (notifications + pickups), a daily Mood check-in, and Trend rankings of the apps you spent more/less time on.",
        "Counting notifications is optional — it asks for Notification access, and AppBlocker only counts them, never reads them.",
        "The dark background got a richer look — a soft glow up top fading to true black.",
    )),
    VersionLog("1.62", "Jul 8, 2026", "Words everywhere + a tougher Strict Mode", listOf(
        "Your blocked words are now caught in every app — not just browsers. The moment a word appears on screen, anywhere, the screen is blocked. No more picking apps one by one.",
        "Built-in exceptions keep your phone usable: the home screen, the keyboard, the notification shade and Android's Settings are never blocked — so a word that's also an app's name can't lock you out of your own phone.",
        "Prefer the old behaviour? A single switch on the Blocked words screen goes back to browser-only.",
        "Strict Mode is now much harder to escape: while a Strict session is running, opening the Accessibility settings, the Device-admin page, or AppBlocker's own app-info page (where Force stop / Uninstall live) bounces you straight back to the home screen — you can't quietly switch protection off mid-session.",
        "Turning off 'Prevent uninstall' now shows a warning reminding you Strict Mode is still locked.",
    )),
    VersionLog("1.61", "Jul 6, 2026", "A protection alert you can't miss", listOf(
        "If Android ever quietly switches off AppBlocker's Accessibility service — which stops all blocking — you now get a clear notification the moment it happens, so protection never lapses without you knowing.",
        "The alert got a full redesign: a clean single line at a glance, and when you pull it down, a bold branded banner with the shield and just the words 'PROTECTION OFF'. No wall of text.",
        "New 'Send a test alert' button in Setup & permissions — tap it any time to confirm alerts reach your phone and to see the new look.",
    )),
    VersionLog("1.57", "Jul 4, 2026", "Templates get the full editor", listOf(
        "Editing a template now opens the same clean full-screen editor as Quick Block, instead of a cramped pop-up sheet.",
        "The app list is tucked away by default — tap 'Apps' to expand it, with a search box — so you're not scrolling past a long list to reach the options.",
        "The extra options are proper switches now, each with a short description of what it does.",
    )),
    VersionLog("1.56", "Jul 4, 2026", "Smarter templates", listOf(
        "Templates now switch on Quick Block's extra options too, not just apps and words — like blocking in-app purchases or unsupported browsers.",
        "Each template comes with sensible defaults (Gaming Break blocks in-app purchases, Stay Clean blocks unsupported browsers so the adult filter can't be dodged), and the pencil on any template now lets you choose exactly which options it turns on.",
        "Applying a template only ever turns options on — it never switches your protections off.",
    )),
    VersionLog("1.55", "Jul 4, 2026", "Blocked words, front and center", listOf(
        "Blocked words now have their own screen — open it from the new 'Blocked words' card on the Blocking tab (or from Profile). Add and remove words instantly, no Save needed.",
        "New: block your words inside apps too, not just browsers. Pick apps like YouTube or TikTok and a blocked word gets caught there as well.",
        "Your browsers are always covered. Apps are strictly opt-in — nothing new is scanned unless you choose it, so typing in Messages or Notes is never affected.",
    )),
    VersionLog("1.54", "Jul 4, 2026", "Small fixes", listOf(
        "Fixed the New schedule tiles (Usage limit, Launch count…) cutting off their labels when your phone uses a larger font size — they now grow to fit the text.",
    )),
    VersionLog("1.53", "Jul 4, 2026", "The coach greets you at the door", listOf(
        "The welcome tour now introduces the AI Coach right after the first page — new users meet the app's signature feature before anything else: it knows your real numbers, gets to know you, and sets goals with you.",
        "The tour's step counter includes the new page, and everything else about setup works exactly as before.",
        "AppBlocker now has a public privacy policy (linked from the project page). Short version: everything stays on your device; only the optional AI Coach talks to Google's Gemini, and only with your own key.",
        "Before you enable the Accessibility service, the app now explains exactly what it reads and why, and asks for your agreement — clearer, and required for app stores.",
        "Behind the scenes: the app asks Android for less (no more 'see all apps' permission — it only sees launchable apps and browsers, which is all it ever needed) and now targets the newest Android 15 requirements. Groundwork for a future Play Store release.",
        "A cleaner app icon: just the shield, no emblem — matching the app's own look.",
    )),
    VersionLog("1.52", "Jul 4, 2026", "A coach that knows you", listOf(
        "Your coach now remembers you: things you share in chat — why you're blocking, what tempts you, what you'd rather be doing — are saved on your device and shape every reply and every daily tip from then on.",
        "He gets to know you naturally, one question at a time — never an interrogation. See (or erase) everything he knows via the new person icon at the top of the chat.",
        "A more motivating voice: the coach now leads with your wins — streaks alive, goals hit, numbers going down — calls you by name, and can finally use an emoji or two. 🎉",
        "Cleaner answers: step-by-step plans render as proper numbered lists, and anything longer than a couple of sentences gets headings and bullets.",
        "Daily tips refresh every 3 hours instead of once a day, so a rough afternoon gets an evening course-correction — and the first tip celebrates progress when there is some.",
    )),
    VersionLog("1.51", "Jul 3, 2026", "Fewer numbers, more meaning", listOf(
        "The Focus Score, XP levels and achievements are retired — after real use they added noise, not motivation. Numbers you have to interpret lost to goals you can feel.",
        "Goals stay front and center: live progress bars, 7-day hit/miss dots, per-goal streaks, one-tap enforcement, and the coach tracking every target with you.",
        "Insights is cleaner for it: your data, your goals, your coach — nothing artificial in between.",
    )),
    VersionLog("1.50", "Jul 3, 2026", "Goals that actually mean something", listOf(
        "Goals are no longer just words — they're measurable daily targets the app tracks itself: total screen time under X, one app under X, or unlocks under N.",
        "A new Goals card in Insights shows a live progress bar for each goal (green while you're under, red once you're past), the last 7 days as hit/miss dots, and a per-goal streak.",
        "Hitting a goal pays: every finished day under target adds +15 XP per goal, and two new achievements — 'On target' and 'Promise kept' (7-day goal streak).",
        "Create goals yourself with the New goal button (pick what to measure, set the target), or agree on them in chat — the coach now sets real, structured goals and sees your live progress toward them.",
        "One tap on 'Enforce with a schedule' turns a goal into a real Usage-limit schedule that blocks when you cross the line.",
        "Your old text goal is automatically converted to a tracked one.",
    )),
    VersionLog("1.49", "Jul 3, 2026", "Focus Score: your discipline, gamified", listOf(
        "A live Focus Score (0-100) at the top of Insights, recomputed all day from your real behavior: screen time vs your own 30-day baseline, unlocks vs your average, urges stopped, focus-session minutes, and whether your protection is armed.",
        "Every finished day banks its score as XP. Climb 7 levels: Starter, Aware, Focused, Disciplined, Guardian, Master, Legend.",
        "Streaks: days scoring 60+ chain together — miss a day and it breaks, so showing up daily matters.",
        "17 achievements with XP rewards, from 'First stand' (your first block) to 'Fortress' (1,000 blocks) and 'Transformed' (a 30-day streak) — each with live progress toward the next one.",
        "New Achievements page: your level, XP bar, and every badge — earned ones in full color with their date, locked ones with exactly what's left.",
        "The coach sees your score, level and streak, so he celebrates milestones and pushes you toward the next one.",
    )),
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
