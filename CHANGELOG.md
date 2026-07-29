# Changelog

All notable changes to AppBlocker, newest first. Versions map to `versionName` in
`app/build.gradle.kts` and the `vX.Y` git tags / GitHub releases the in-app updater reads.

## v1.110
- The AI coach answers again — it was giving up when your first-choice model ran out of daily quota instead of trying the next one. Bug reports now actually carry the settings and the recent-block log they were built to send; one small failure used to empty the whole report silently. Strict Mode no longer blocks the whole Settings app — both modes now guard only the three screens that actually end protection, so your battery settings and other accessibility apps stay reachable. Turning off Prevent uninstall now costs a typed paragraph, and that paragraph is a race: 40 words in 3 minutes, a fresh one if the clock beats you, and pasting is properly blocked. Plus six bugs found by auditing rather than by you hitting them — including a block screen that could flash over apps that aren't blocked, a Got it that could drop the cover without getting you out, adult protection that could be switched off after its window closed, and daily limits that could quietly stop counting.

## v1.109
- The guard stops blocking pages you actually need. It was blocking your battery settings — that page has an "Uninstall" button at the bottom, so it matched the words the guard watches for. Blocking it is self-defeating too, because this app tells you to set battery to "No restrictions" so blocking survives in the background.

The real mistake was bigger than that one page. I'd been guarding the whole App info screen, which is the hub for battery, permissions, storage, notifications and uninstall. Guarding it to protect one button costs you everything else on it — which is why this kept coming back.

The guard now covers exactly three screens, and they're the only three that actually end your protection: AppBlocker's own accessibility page, the "uninstall this app?" confirmation, and the screen for turning off device admin. Battery, permissions and app details are yours again.

One thing given up on purpose: Force stop is no longer blocked. It shares the page with the battery settings, and force-stopping only pauses blocking until the service restarts by itself — whereas the settings beside it are ones the app asks you to change. Uninstalling is still guarded, at the confirmation where it actually happens.

Strict Mode is unchanged and still blocks all of those pages.

## v1.108
- You can turn on uninstall protection again. The guard was blocking Android's "Activate device admin app?" screen — the one you have to pass through to switch that protection ON — so it could never be enabled, while the app still reported itself as guarded. That's the worst kind of bug this app can have, and it was mine, from 1.107.

It fired because that screen says "device admin" and has an "Uninstall app" button on it, so it matched every word the guard watches for. Reading the wording can't separate "activate" from "deactivate" — one word literally contains the other, and it's translated on other phones.

So the app no longer tries to read it. When you tap Prevent uninstall, the app knows it just opened that screen and stands aside for a minute. That works in any language. The deactivation screen, which the app never opens, is still guarded.

After installing: Profile ▸ Prevent uninstall. If it says AppBlocker can be uninstalled right now, turn it on — that switch is what actually makes Android refuse the uninstall.

## v1.107
- Uninstalling is guarded again. You could just switch the protection off and uninstall the app — and that was my fault, from a few hours earlier.

Fixing the accessibility-list problem in 1.106, I removed a check that reads the screen's text, because that check was what kept bouncing the list. What I missed is that the same check was the only thing catching the uninstall and device-admin screens on your phone. Xiaomi routes those through generic screens whose names match nothing, so with the text check gone, nothing was watching them.

It's back, with its own separate word list — uninstall, force stop, deactivate, device admin — none of which can appear on the accessibility list. So the uninstall route is guarded again without re-breaking what 1.106 fixed.

The Prevent uninstall row in Profile now says plainly when it's off: "AppBlocker can be uninstalled right now." Those are two separate protections — the guard makes the page hard to reach, device admin is what actually refuses the uninstall — and it was possible for the app to look locked down while that one was switched off. You want both on.

## v1.106
- The accessibility list really does open now. Fourth attempt, and the first three were the same mistake in different clothes — sorry for the runaround.

Every earlier version tried to recognise AppBlocker's own accessibility page by reading text that ANDROID puts on screen: a screen name, then the app's name, then other services' names. All of that changes with the phone brand, the Android version and the language, so each fix was a guess.

This version matches text the app itself wrote. Android shows AppBlocker's own description on AppBlocker's own accessibility page — "AppBlocker uses this to detect when a blocked app opens…" — and shows it nowhere else. Not on the list of downloaded services, not on another app's page. It's our sentence, so it doesn't change with your phone or your language.

Two real bugs in 1.105: it compared against apps' names while the list actually shows the service name, so it matched nothing; and with no other third-party accessibility service installed, its test was true of every page and blocked the list every time. The broken check is deleted rather than left beside the new one.

## v1.105
- Other accessibility apps are reachable again. The guard was blocking the whole Accessibility section rather than just AppBlocker's entry, so every other service — TalkBack included — sat behind a two-hour wait. It asked "does this page mention AppBlocker?", and Android's list names every installed service. It now asks "does this page name AppBlocker and nobody else?", so the list opens normally and only our own entry bounces.

Size buttons actually resize things now. They only ever changed text, never the app icon — so on the Focus screen, which is mostly a big icon, pressing Larger moved the label and left the picture alone. It looked broken because it half was. There are also five steps now instead of three: Tiny, Small, Normal, Large, Huge.

Better quotes. Several of the old ones were generic, and a few were about starting things, which has nothing to say to someone trying to stop. The list now leans on self-mastery over desire, plus new lines written for the moment you actually read that screen.

And a new Twelve Steps screen in Profile — the programme in plain English, with a note under each step on what it actually asks of you. The wording is the app's own summary, not SAA's official text, which they don't permit to be reprinted; there's a link to the real thing at the top.

## v1.104
- Fixes the block screen flashing where it shouldn't — at least one cause of it. When you open a Settings page, the app checks whether it's AppBlocker's own page before covering it, and I'd made that check guess "treat it as ours" whenever it couldn't read the screen. That ignored when the check runs: at the instant a page is opening, which is exactly when the screen usually can't be read yet. So any app's App info page could flash a cover depending on timing. It now waits instead of guessing.

That can't explain covers over unblocked apps or inside AppBlocker itself, so there's a second cause still unfound — and this release is how the app tells me about it. Bug reports now include why it blocked the last few times: which part of the app raised each block screen, whether AppBlocker's own screens were in front, and whether the app being blocked was really the one on screen. None of what was blocked is recorded — no app, no word, no website.

Reports also now carry your current settings: which block screen you use, whether blocking was actually running, permissions, and how many blocks today. All switches and numbers, never content.

Next time it flashes, tap Report a problem and say "flashed here" — the last fifteen blocks come with it.

## v1.103
- Bugs can now report themselves. The app already recorded every error it survived, but there was nowhere to send them — so the only way a problem reached me was you noticing and mentioning it. Errors and crashes now send themselves to a private tracker, and there's a Report a problem button in Profile for the ones you spot.

Never sent: your blocked words, the sites you visit, the apps you block, your name, your location. Error messages are dropped entirely rather than cleaned up, because that's exactly where a blocked word would get quoted back.

The Focus block screen can now show a quote — small and centred rather than the big hero line, since Focus has no room to spare. The quote's Side setting gained an Auto option, now the default, meaning "however this layout draws it".

And the off-switch guard from 1.102 no longer overreaches: it was blocking every app's App info page and the whole Accessibility section, so force-stopping a frozen app cost the full wait. It now only guards AppBlocker's own pages. Strict Mode still guards all of them.

## v1.102
- Blocking now guards its own off-switch. Before this, turning AppBlocker's accessibility service off in Settings took about five seconds and switched off every block in the app — apps, blocked words and websites all at once. Strict Mode guarded that page, but only while a session was running; the rest of the time nothing did. It is now covered whenever the guard is on, which it is by default.

You can still turn it off, but not quickly: Profile ▸ Guard the off-switch, type the paragraph, wait 2 hours with the guard still standing, then you have 15 minutes to do it. Cancel any time. Turning it back on is instant. Changing the phone's clock doesn't shorten the wait.

Also fixed: the backup detection for those Settings pages only ever worked in English, so on an Arabic phone it silently did nothing. It now understands Arabic too.

And the block screen quote can sit on the left, in the centre or on the right — Profile ▸ Block screen ▸ Pieces ▸ Quote.

## v1.101
- You can now arrange the block screen yourself. Show, hide, resize and reorder its pieces — the minutes counter, the quote, the "Blocked" label, the app — on top of the four looks from 1.100, with a live preview that is the real block screen rather than a picture of one. The four looks are untouched; they are starting points now. Editing is locked while Strict Mode is running.

Underneath, the first proper audit of the updater. It never checked that a finished download was actually an app — only its size, and only when the server stated one — so a café or hotel Wi-Fi login page could have been handed to the installer as if it were AppBlocker. Updating on a phone that had not yet been told to allow it used to dead-end silently; it now carries on by itself when you come back. The download popup has a Cancel button, the installer file is deleted once it is used, and a crash that would have hit any Android 7 phone is gone.

And a hole worth knowing about: Strict Mode locks every protective setting in the app except the update button, and installing an update used to end a running Strict session. So whenever a newer version existed, Strict was two taps from over. A session now survives the update and keeps blocking — and because it does, there is nothing to reactivate afterwards.

## v1.100
- The AI coach is much smarter. It uses the best model available, thinks properly before answering (replies take 10-30 seconds now), writes like a person instead of a form letter, remembers far more of your conversation, and remembers its own advice so it can actually follow it up. You can also design your own block screen: four layouts crossed with four colours, in Profile > Block screen. Underneath, seven rounds of bug hunting. The important one: setting your phone's clock backwards used to switch off the guard protecting the Accessibility page during Strict Mode - the page where AppBlocker can be turned off entirely. Also fixed: blocking could stop reacting to your rules permanently after a single error; the adult filter and browser detection could silently fail open; a daily-limit schedule could never block without usage access and never said so; in-app purchase screens could be missed entirely; and location schedules trusted your phone's position forever.

## v1.99
- Six problems found by going looking for them, none of them reported. The biggest: after every update blocking was completely off until you tapped Reactivate, and nothing else told you — Profile still said "Protection active" and no notification arrived. Also fixed: blocking could stop reacting to your rules permanently after a single error; two protections were skippable by winding the phone's clock forward (the adult pack's 24-hour wait and the 30-minute lockout after a blocked word); errors the app swallowed were recorded and never shown to anyone; a Wi-Fi schedule naming a specific network could never match without location access; and your history could be wiped around New Year.

## v1.98
- Pulling down your notification shade over a block screen used to take the block screen away - and closing it again counted the app as a brand-new attempt, adding another 3 minutes. A volume key press or a notification pop-up did the same, in both Blocking modes. The shade, volume dialog, notification pop-ups and your keyboard all become 'the thing in front' as far as Android is concerned, and the blocker read that as you having left the app. They don't replace the app though, they sit on top of it - so they're now recognised for what they are and the block screen stays put underneath. Plus three more, found by going looking rather than waiting: a blocked YouTube could have its block screen removed entirely, locking your phone while Shorts was covered skipped all the tidying up (so unlocking could show a leftover block screen), and the safety net that clears a stray block screen off your home screen could refuse to run. All three came from the app keeping its own note of whether Shorts was covered instead of just checking the screen - it now checks, so it can't go out of date.

## v1.97
- Fixes the last of the Allowlist flashing: turning Allowlist mode on could still put a block screen over AppBlocker's own screens, and you could still catch a flash in an app you'd allowed like WhatsApp. One cause behind both. Android tells the blocker 'this app's window changed', but apps send that in the background too - when a message arrives, or something reloads behind what you're actually looking at - and the blocker took every one of those as 'this is the app in front of you now'. In Allowlist mode everything is blockable, so a background app could get you a block screen over whatever you were really doing, and a background app you'd ALLOWED could make a legitimate block screen vanish, which the real app then brought straight back. The blocker now checks what's genuinely in front before acting. If it can't tell, it still blocks - an app can't dodge it by hiding - and if a real app switch just arrived a fraction early it re-checks a moment later, so blocking still happens immediately.

## v1.96
- Fixes the block screen flashing on your home screen and over AppBlocker's own screens after turning on Allowlist mode. This was our fault in 1.95: the new 'keep the block screen up until you've really left the app' behaviour had no way to tell you'd already reached your home screen, because the swipe-up Home gesture tells apps nothing and the block screen itself can look like the app in front. Not knowing was treated as 'still in the app', so the screen was held for a couple of seconds over your home screen every time you tapped 'Got it' - and in Allowlist mode you tap it constantly. It's now only held on when the app can actually be seen to still be in front. Also: AppBlocker's own screens can no longer be covered, your home screen and phone app can no longer be blocked in Allowlist mode, and the apps that must always keep working there are now protected against every kind of block, including a blocked-word lockout.

## v1.95
- Fixes being blocked twice for one open. Tapping 'Got it' now keeps the block screen up until your phone has really left the app, instead of taking it away and hoping the trip Home lands - so you never get a second block screen for a single open, and one open is counted once (your 'minutes reclaimed' moves by 3, not 6). Also fixes YouTube Shorts staying uncovered after you tapped 'Got it', a blocked word counting as two attempts, and a late message from an app you had just left throwing the block screen over your home screen.

## v1.94
- Fixes how the guide cards look: the thinker's name and the headline beside it no longer leave a big empty gap when the headline is too long for one line, and the big number on numbered rules now sits next to the rule's title instead of floating in the middle.

## v1.93
- Overnight schedules now follow the day you picked, timers and Strict sessions stay correct after a restart, and a Strict session started right after an update is no longer cancelled. Blocking is also harder to knock over: an unexpected error can't take it down any more, and if your phone kills it in the background the app now notices and tells you.

## v1.92
- Fixed the block screen flickering. When you open a blocked app, the block screen now stays put instead of flashing away and coming back. It sits solidly over the app and sends you to your home screen when you tap Got it.

## v1.91
- Quick Block now has an Allowlist mode: instead of choosing what to block, choose the few apps you want to keep — everything else is blocked while a Quick Block, Timer or Pomodoro is running, and your home screen, phone, keyboard and Settings always stay usable. The Quick Block editor got an AppBlock-style redesign, with a full-screen Blocking-mode chooser and a tidy summary that opens Apps (and Websites & words) on their own screens. The AI Coach now works for everyone with nothing to set up — no more pasting a Gemini API key. And the block screen no longer shows twice on tablets after you tap Got it.

## v1.90
- New Scenarios section in your Profile: short guides for the hard moments — relapse, can't focus, feeling lazy, can't sleep, the urge to scroll, and feeling overwhelmed — each opening with real quotes from thinkers like Marcus Aurelius, Seneca, Viktor Frankl and Sartre. The Dopamine detox guide stays where it was. You can also add a Quick Block button to your phone's pull-down Quick Settings panel to turn blocking on and off without opening the app.

## v1.89
- When you block a social app (Facebook, Instagram, TikTok, YouTube, and so on), the browser now blocks that app's actual website — but no longer blocks pages that just mention its name, and no longer locks your whole browser for 30 minutes over it.

## v1.88
- The block screen no longer flashes over your home screen after unlocking. When a word is blocked, the screen now shows you which word it was. And the plain word "porn" no longer blocks non-sexual apps (the fuller phrases still do).

## v1.87
- The Dopamine Detox guide's header is now compact — the tall empty blue box at the top is fixed.

## v1.86
- The Dopamine Detox guide is now a full rulebook: three truths from Buddhism, 25 clear rules for beating the scrolling and porn cravings, a craving SOS, and a fresh, cleaner design.

## v1.85
- New: a full Dopamine Detox guide on the Profile page — what scrolling does to your brain and a 7-day reset plan. Fixes: templates no longer add app-name words (like "youtube") to your blocked words, and the ones added before are cleaned up once; the turn-off typing challenge now works properly with the keyboard open; and the challenge uses longer words.

## v1.84
- Three fixes: the typing challenge for turning off the adult content pack now works properly (full-screen, keyboard can't hide it, capitals and extra spaces don't matter). Turning the pack off is also much harder now — after the typing challenge the pack keeps protecting you for 24 more hours before the switch actually works. And the block screen no longer flashes over your home screen or right after pressing "Got it".

## v1.83
- Word blocking is much stronger now: pressing "Got it" no longer lets you go back and keep reading — the app where the blocked word appeared locks completely for 30 minutes. Scanning is also much faster and now catches words while you scroll. After updating, turn the AppBlocker accessibility service off and on once in your phone's settings.

## v1.82
- Schedules can now be deleted right from the list (trash icon with a confirm - hidden during Strict Mode), and two labels no longer break mid-word on larger font sizes (the Active badge on templates and the Location schedule tile).

## v1.81
- The timer picker's Save button now keeps a guaranteed fixed distance from the bottom of the screen - no more depending on the phone reporting its gesture-bar height (some phones report zero, which defeated every measured fix).

## v1.80
- The timer picker's Save button now reads the navigation-bar height directly from Android's root window - the deepest possible source, immune to the popup-window and layout-accounting issues that defeated the earlier fixes.

## v1.79
- Fixed for real: the Save button in the timer picker (Strict Mode and Quick Block) now measures the navigation bar from the app's main window - the popup was reporting it as zero, which is why earlier fixes didn't stick. Also: Instructions topic pages got a cleaner layout with titled point cards instead of plain text.

## v1.78
- Smoother settings: Instructions topics now open as their own full page (easier to read than the old expanding cards), and the app-icon chooser is a clean full-page grid instead of the cramped popup.

## v1.77
- New: Profile > Instructions - a built-in guide explaining every feature in detail, in thirteen expandable topics: protection setup, Quick Block, templates, all five schedule types, Strict Mode, blocked words and websites, the block screen, YouTube Shorts, the full Insights tab, goals/mood/AI Coach, PIN lock, updates, and personalization.

## v1.76
- The block screen now shows the app icon you actually picked in the icon switcher (it used to show the default logo), and its quotes got a quality pass - cliches cut, misattributions fixed, and stronger lines from William James, Mary Oliver, Seneca, Pascal and James Clear added.

## v1.75
- Fixed: one blocked attempt was being counted (and re-shown) many times - a single block now records exactly one entry, the quote stays put while the block screen is up, and tapping Got it no longer re-triggers the same block on the way home.

## v1.74
- Smarter, more precise blocking: no more false blocks on the home screen; the block screen now explains WHY (schedule name, daily limit, Quick Block, Strict Mode, or the matched word); blocked words match the site you're on in Chrome instead of any page that mentions them; blocked apps get covered faster; and blocking YouTube or a social app now covers its website and short links too (youtube.com, youtu.be, t.co, redd.it, fb.watch...).

## v1.73
- Fixed: the Save button in the Strict Mode timer picker (and the schedule, template and Quick Block editors) sat too low, inside the gesture-navigation area - it now sits clearly above it.

## v1.72
- Polish for Android 15 phones: the AI Coach chat, What's new page and PIN lock screen no longer draw behind the status bar, and the PIN screen's Unlock button stays above the keyboard.

## v1.71
- Fixed: on phones with larger text or display size, the setup wizard could hide its Continue button below the screen with no way forward - steps now scroll and the button is always visible, and the wizard no longer draws behind the status bar on Android 15.

## v1.70
- Fewer false adult-word blocks: only real porn vocabulary blocks now - everyday words (adult content, queen of spades, cream pie...) no longer trigger it. And the AI Coach now knows the time of day, so no more 'goal hit!' at 9am or praising your night's sleep as phone-free time.

## v1.69
- This update ends your running Strict Mode session - the 1.67 to 1.68 hop could not (1.67 was too old to leave the note 1.68 needed), this one can. Ending the session is also retried until it truly lands, so Android cutting the first attempt short can never leave Strict stuck again.

## v1.68
- Calm updates: after every update, blocking pauses until you tap Reactivate - and updating now ends a running Strict Mode session (a new version is a clean slate). Fixed a sneaky bug where a finished Strict session could switch itself back ON after a phone restart with a briefly-wrong clock. Insights reordered: AI Coach up top, Focus/Distractions/Mood at the bottom.

## v1.67
- App lists are now organized into 12 categories with one-tap whole-category blocking, and Gemini automatically categorizes every app on YOUR phone. The AI Coach got a big upgrade: a newer faster Gemini model, much richer knowledge of your day (busiest hour, temptations, phone-free stretches, mood check-ins), and tips that refresh every hour.

## v1.66
- The app icon's shield is now smaller so it fits beautifully in the round icon shape, and you can pick your favourite icon in Profile > Appearance > App icon - six AI-designed choices: Halo glow, Violet night, Pure black, Daylight, Bold silhouette, and Shield & lock.

## v1.65
- A beautiful new block screen - giant motivational quotes, a minutes-reclaimed-today counter, calmer poster design. Brand-new AI-designed app icon (glowing hourglass shield). Adult content pack now has a much harder off-switch.

## v1.64
- Adult content pack: hundreds of English + Arabic pornographic/fetish words (incl. cuckold + BNWO vocab) blocked out of the box - whole-word matching, Arabic normalization + glued-form coverage, toggle on Blocked words screen, Strict-locked

## v1.63
- Light mode + a much richer Insights (Balance, Peak time, Focus, Distractions, Mood check-in, Trend rankings), nicer dark background, and a Strict-Mode fix

## v1.62
- Blocked words now work in every app + a tougher Strict Mode (blocks the accessibility/uninstall escape hatches)

## v1.61
- Beautiful redesigned protection alert: a bold branded banner (shield + 'PROTECTION OFF') instead of a wall of text.

## v1.60
- The protection alert now shows every time you open the app while accessibility is off (no longer silenced for hours), plus a new 'Send a test alert' button in Setup & permissions so you can check notifications reach your phone.

## v1.59
- Protection-off alert now fires no matter which screen you open the app to (was only checking on some tabs), and the reminder cooldown no longer gets used up if notifications are turned off.

## v1.58
- Notifies you if the blocking service gets silently turned off, with a one-tap fix to turn it back on.

## v1.57
- Editing a template now opens the same clean full-screen editor as Quick Block, with the app list hidden by default (tap Apps to expand, with search) and the extra options as proper labelled switches.

## v1.56
- Templates now switch on Quick Block's extra options too (block in-app purchases, unsupported browsers, and more) - each template has smart defaults, and the pencil lets you choose exactly which options it turns on. Applying a template only ever turns options on, never off.

## v1.55
- Blocked words now have their own screen (on the Blocking tab and in Profile) - add and remove words instantly. And you can now block words inside apps you choose, like YouTube or TikTok, not just browsers. Apps are opt-in, so Messages and Notes are never affected.

## v1.54
- Fixed the New schedule tiles (Usage limit, Launch count) cutting off their labels on phones with a larger font size - they now grow to fit the text.

## v1.53
- New: the welcome tour now introduces your AI Coach right up front. Cleaner app icon (just the shield). Under the hood: a clear consent screen before enabling Accessibility, a public privacy policy, fewer permissions requested, and Android 15 support - groundwork for a Play Store release.

## v1.52
- AI Coach upgrade: the coach now remembers you (personal facts saved on device, visible via the person icon in chat), asks natural get-to-know-you questions, leads with your wins in a more motivating voice with emojis, renders step-by-step plans as numbered lists, and daily tips refresh every 3 hours instead of once a day.

## v1.51
- Fewer numbers, more meaning: the Focus Score, XP levels and achievements are retired - they added noise, not motivation. Goals stay front and center: live progress bars against your real usage, 7-day hit/miss dots, per-goal streaks, one-tap enforcement, and your coach tracking every target with you.

## v1.50
- Goals that actually mean something: measurable daily targets the app tracks itself (screen time, one app, or unlocks), with live progress bars, 7-day hit/miss dots and per-goal streaks on a new Goals card in Insights. Hitting a goal pays XP and unlocks achievements, one tap turns a goal into a real Usage-limit schedule, and the coach sets and follows structured goals with your live numbers. Your old text goal converts automatically.

## v1.49
- Focus Score: your discipline, gamified. A live 0-100 score at the top of Insights measured against your own habits, XP banked from every finished day, 7 levels from Starter to Legend, streaks for good days, and 17 achievements with rewards - from your first block to a 30-day streak. Your coach celebrates every milestone with you.

## v1.48
- The coach's answers got a face-lift: section headings in bold, lists with the app's gradient bullet dots, and key numbers and app names highlighted - weekly reports finally look like real reports instead of a wall of text.

## v1.47
- The coach levels up: ask for a proper weekly report (day-by-day breakdown of your week), have him set a specific weekly goal with a detailed plan he tracks, and tap suggestion chips above the message box to chat without typing - starters when you open, his own follow-ups after every reply.

## v1.46
- Chat with your AI Coach: a full conversation screen in Insights - he knows your data, the app's features and your setup, and you can set long-term goals together that he tracks in daily tips. Plus: the 'Update available' popup now shows only once per app open, and Profile has a new 'What's new' page with the full detailed history of every version.

## v1.45
- NEW: AI Coach in Insights - Gemini writes you 2-3 daily tips from your real usage data (paste your free Gemini API key once in the app); plus brighter, easier-to-read row titles across Insights

## v1.44
- Insights page redesigned: gradient hero with key stats, comparison bars on every app list, colored week-over-week trends, glowing cards with section icons, animated charts

## v1.43
- Blocks now apply mid-use (limits/schedules catch you inside the app), disabled location schedules stop draining battery, tablet-friendly New-schedule tiles, more professional Profile page

## v1.42
- Insights loads lighter (reuses cached app icons), less background work while using other apps, and Profile's Prevent-uninstall now switches off properly

## v1.41
- Faster Insights (stats cached per day, refreshes every time you open the tab) and snappier blocking checks

## v1.40
- Profile now shows your name (Abdallah Ahdab) with an avatar and a rename option. YouTube Shorts blocking now starts and stops with Quick Block. You can now choose which of your apps each template blocks (edit pencil on each template card).

## v1.39
- Block YouTube Shorts is now shown as a nested 'Shorts (BETA)' sub-row right under YouTube in the app list (with its own checkbox), instead of a separate Extra-options toggle.

## v1.38
- New option in Quick Block extra options: Block YouTube Shorts. Blocks only the Shorts feed/player in the YouTube app (and youtube.com/shorts in browsers) while the rest of YouTube keeps working.

## v1.37
- Redesigned the Profile page: a gradient header with the app shield, version and a live 'Protection active / Action needed' status; iconed rows with On/Off status badges (PIN, Prevent uninstall); a Share AppBlocker option; and a cleaner layout.

## v1.36
- Insights: new Trend tab with a 30-day chart, 30-day average and this-week-vs-last-week; a Patterns card (weekday vs weekend); a 'Trending this week' list showing how each app changed vs last week; and phone unlocks per day in the Summary.

## v1.35
- Insights: new Summary card with daily average (7 days), busiest day, screen time compared to yesterday (up/down %), and a Light/Moderate/Heavy usage rating for today.

## v1.34
- Insights graph is now interactive: tap or scrub a bar to read its exact value (e.g. '7 PM - 24m'), the busiest bar is highlighted as the peak by default, and the Day/Week charts have clearer time labels (Week now shows the real weekdays).

## v1.33
- Insights: tap any app to see its screen time, opens and block attempts together in a detail panel; the Most opened apps header now shows total app opens today.

## v1.32
- Insights: added a 'Most opened apps' section showing how many times you opened each app today, and the 'Most used apps' rows now show opens alongside screen time.

## v1.31
- Added 'Prevent uninstall (Device admin)' to the Setup and permissions list. Also fixed a bug where the device-admin activation screen self-closed (it was launched as a new task), so Prevent uninstall now actually activates from here, Profile and Strict Mode.

## v1.30
- Blocking a social media app now automatically blocks its website too: while Quick Block is on, a blocked social app's site (e.g. instagram.com when Instagram is blocked) is blocked in browsers. Stays in sync - pausing Quick Block relieves it too.

## v1.29
- Hypothetical apps: now social-media only (removed games, streaming, messaging) and each app shows a brand-coloured initial badge instead of a generic icon.

## v1.28
- Renamed the pre-block popular-apps section to 'Hypothetical apps' (its own list inside Quick Block, hidden/collapsed by default, separate from your installed apps).

## v1.27
- Added Grok to the 'Block before you install' popular-apps list.

## v1.26
- New 'Block before you install' list in Quick Block: pre-block popular apps (TikTok, Instagram, Snapchat, etc.) even if they aren't installed yet - they're blocked the moment you install and open them.

## v1.25
- Usage limit and Launch count are now clean editable fields instead of preset chips: Usage limit has hours + minutes steppers, Launch count has an opens stepper. Type any value or use the minus/plus buttons.

## v1.24
- Location schedules: save a captured spot under a name (e.g. 'UK') and reuse it from a Saved places list, instead of re-capturing every time. Long-press a saved place to delete it.

## v1.23
- Usage limit and Launch count now have an 'Other...' option to enter any custom number (e.g. a 45-minute daily limit or block after 7 opens), not just the presets.

## v1.22
- Fixed Location blocking: it now guides you to grant 'Allow all the time' location (required since blocking runs in the background) and reliably reads your current location, so apps are blocked inside the chosen area.

## v1.21
- Fixed schedule preset buttons (usage limit / launch count) so all options stay fully visible and wrap neatly instead of getting cut off or split across lines.

## v1.20
- The Create schedule button now stays fixed at the bottom of the screen while you scroll the app list.

## v1.19
- Schedule times now read 9:00 AM / 5:00 PM (12-hour), and limits show 30 min / 1 hr / 2 hr and '10 opens' instead of raw numbers.

## v1.18
- Faster app lists, and apps are now ordered by what's most worth blocking (most-distracting + most-used first) instead of alphabetically.

## v1.17
- App and Websites lists in the editors can now be collapsed and expanded by tapping their header.

## v1.16
- The version number now always shows in Profile > About, even after checking for updates.

## v1.15
- Automatic 'Update available' prompt on launch with one-tap Update now; easier install via a permanent download link and QR code.

## v1.14 — Internal cleanup
- Split the 740-line home screen into focused files (Quick Block, schedules, templates, banners).
  Refactor only — no user-facing change.

## v1.13 — Onboarding
- New first-run setup wizard: walks through the essential permissions (accessibility, overlay) one
  step at a time with a progress indicator, then the recommended optional ones.
- Setup is now only marked "done" once you finish or skip the wizard, so quitting mid-setup brings
  it back next launch instead of silently dropping you into a half-configured app.

## v1.12 — Housekeeping & docs
- Disabled cloud/`adb` backup (`allowBackup=false`) so the PIN hash and block rules can't be
  restored onto another device.
- Added this CHANGELOG and a documented release process in the README; refreshed the README to
  match the current feature set.

## v1.11 — Engineering health
- Enabled R8 + resource shrinking for release (APK ~15 MB → ~1.2 MB) with keep-rules for the
  accessibility service, receivers, and Room.
- Added the first unit tests: version comparison, the Strict/Timer clock logic, and the schedule
  time-window midnight wrap.
- Batched the Quick Block save into a single DB transaction.
- Hardened the updater: internal-storage fallback, partial-download cleanup, truncation check, retry.

## v1.10 — UX smoothness
- Animated bottom-nav tab switches and editor overlays; each tab now keeps its state across switches.
- Toast feedback when a Timer/Pomodoro starts; haptic tick on the duration wheel.
- Persist the Insights sub-tab; refresh Profile PIN state on resume; accessibility labels.

## v1.9 — Watcher reliability
- Fixed a location-listener leak (battery/privacy) on service shutdown.
- Refresh the browser list when apps are installed/removed so "block unsupported browsers" can't be
  bypassed by installing a new browser.
- Moved the web-content scan off the main thread to reduce jank while browsing.

## v1.8 — Strict Mode integrity
- Strict Mode / Timer / Pomodoro now anchor to the monotonic clock, so changing the device clock
  can no longer end an "un-stoppable" session early (wall-clock fallback after reboot).
- Replaced destructive DB migration with explicit migrations + schema export, so app updates no
  longer risk wiping all rules/schedules/PIN.

## v1.7 — In-app updater
- Check GitHub for the latest release, download and install it from within the app, with an
  "Update available" banner.

## v1.6 — Fixes
- Template cards no longer clip the time line at large font scales.

## v1.5 — Web filtering scope
- Website/keyword filtering only applies inside browsers; fixed a wrapping navigation label.

## v1.4 — More blocking
- Auto-block newly installed apps; block in-app purchases.

## v1.3 — Strict Mode controls
- Allow strengthening blocks during Strict Mode with a confirmation; wire up "block unsupported
  browsers".

## v1.2 — Strict Mode fixes
- Fixed a Strict Mode trap, template clipping, and the Strict timer format.

## v1.1 — Pomodoro & pickers
- AppBlock-style wheel duration picker; restyled Pomodoro.

## v1.0 — Initial release
- Block apps via an AccessibilityService with an instant block overlay.
- Strict/Focus sessions, daily time limits, launch-count/usage/Wi-Fi/location schedules.
- Website/keyword + adult-content filtering, one-tap category templates.
- PIN lock and Device Admin uninstall protection; signed release build.
