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
    VersionLog("1.104", "Jul 26, 2026", "Fixes the block screen flashing where it shouldn't", listOf(
        "You reported the block screen flashing in the wrong places. That was mine, from yesterday's 1.103 — and it was a mistake in the reasoning, not a typo.",
        "When you open a Settings page, the app checks whether it's AppBlocker's own page before covering it. I made that check answer \"treat it as ours\" whenever it couldn't read the screen, on the logic that a block you didn't need is annoying but obvious, while a block that fails to happen is invisible. That logic is sound in general — but it ignored *when* the check runs. It runs at the exact instant the page is opening, which is precisely when the screen usually can't be read yet. So \"couldn't read it\" wasn't a rare accident; it was the normal case, and any app's App info page could flash a cover depending on timing.",
        "Now, when the app can't read the screen, it simply waits instead of guessing. Nothing is lost: the page has only just opened, you couldn't have reached any switch yet, and the check runs again a fraction of a second later once the screen is readable. AppBlocker's own pages are still guarded exactly as before.",
        "Your idea: reports now also carry **why it blocked, the last few times**. Each recent block screen is logged by its *shape* — which part of the app raised it, whether AppBlocker's own screens were in front at the time, and whether the app being blocked was really the one on screen. That last pair is what identifies a block screen landing somewhere it shouldn't, which is otherwise gone in the milliseconds it lasts.",
        "It records none of what was blocked — no app, no word, no website. \"Why\" turns out not to need any of that: a cover on the wrong thing looks the same whichever app it landed on.",
        "This is the instrument for the flashing you reported. I fixed one cause above, but it can't explain block screens appearing over unblocked apps or inside AppBlocker itself — so there's a second cause I haven't found, and this is how the app tells me about it instead of us guessing.",
        "Also in this version, bug reports now say what the app was set to. Reports used to carry only what you typed, plus the app version, your Android version and your phone model. That's very private, but it also meant a report saying \"the block screen didn't appear\" arrived with nothing to work from — and you can't be expected to know that \"blocking was in its stalled state and usage access was off\" is the useful half of that sentence.",
        "Reports now also include your current settings: which block screen you're using, whether blocking was actually running, whether the off-switch guard is on, how many blocks happened today, whether the adult pack is on, and whether the app has the permissions it needs.",
        "All of those are switches you set or numbers the app counted. **None of them can contain anything you read, typed, visited or blocked** — no keywords, no websites, no app names. There's a fixed list of what's allowed to be attached, anything not on it is thrown away before sending, and a test fails the build if someone ever adds something that looks like content.",
        "The Report screen now says all of this above the Send button, so what you're sending is on screen when you send it.",
    )),
    VersionLog("1.103", "Jul 26, 2026", "Focus can show a quote, and the guard leaves the rest of your phone alone", listOf(
        "The **Focus** block screen can now carry a quote. It never could before — the quote simply wasn't part of that layout, which is why no Quote switch appeared for it in Pieces. It's there now, and you can switch it off again the same way if you want the bare version back.",
        "It's deliberately small and centred rather than the big hero line Editorial uses. Focus stacks a large icon and the app name with no room to scroll, so a full-size quote would fight the design and could push the \"Got it\" button off the bottom of a short screen.",
        "**Bugs can now report themselves.** Until now the app quietly recorded every error it survived — the Profile page even said \"tap to clear once you've reported it\" — but there was nowhere to report to, so the only way I ever heard about one was you noticing and telling me. Those errors now send themselves to a private tracker I can read.",
        "That matters most for the bugs you *can't* see. A block screen appearing when it shouldn't is obvious; a block that silently never happens looks exactly like a quiet day. Those are the ones this catches.",
        "There's also a **Report a problem** button in Profile for the ones you do notice — describe what happened and it goes straight over.",
        "**What is never sent:** your blocked words, the sites you visit, the apps you block, your name, your location. Reports carry the app version, your Android version, the phone model, and where in the code it broke. Error messages are dropped entirely rather than cleaned up, because an error message is exactly where a blocked word would get quoted back — and there are tests that fail if that ever changes.",
        "The quote's Side setting gained an **Auto** option, which is now the default and means \"however this layout draws it\" — left on Editorial, centred on Focus. Left, Centre and Right still override it everywhere. If you'd already picked a side, that's kept.",
        "Also in this version, fixing an overreach from 1.102 that shipped a few hours earlier. The off-switch guard was blocking far more than its own page: **every** app's \"App info\" screen, and the **whole** Accessibility section — not just AppBlocker's entry in it. So force-stopping a frozen app, clearing a cache, or turning on some unrelated accessibility service all landed on a block screen and cost the full 2-hour wait.",
        "That was never the intention. It came from reusing Strict Mode's rule, which only cares what *kind* of page you opened, not who it's about. Inside a Strict session that's fine — you chose it and it ends by itself. As an always-on default it quietly turned ordinary phone maintenance into a two-hour errand.",
        "Now the guard checks the page is actually about AppBlocker before blocking it. Other apps' App info opens normally, the Accessibility list opens normally, and only AppBlocker's own entry bounces.",
        "Strict Mode is deliberately unchanged and still blocks all of those pages. It's a lock you set on purpose for a set length of time, and it should stay as strong as it has always been.",
        "One deliberate detail: if the app can't read what's on screen at all, it now assumes the page *is* AppBlocker's and blocks it. Being bounced off a page you could have used is annoying but obvious, and you can wait it out. Failing the other way is silent and takes every block in the app down with it.",
        "Worth being straight about the trade-off you're getting: this is the narrower, more convenient setting, and it's slightly weaker than 1.102. Recognising our own page relies on reading it, and in a long list your phone only builds the part currently on screen. If you ever reach the Accessibility toggle without a block screen appearing, tell me — that's the failure this could have, and it's the kind that doesn't announce itself.",
    )),
    VersionLog("1.102", "Jul 26, 2026", "Blocking now guards its own off-switch", listOf(
        "Until now, everything this app does could be switched off in about five seconds. Settings ▸ Accessibility ▸ AppBlocker ▸ off, and every block is gone — apps, blocked words and websites all at once, because all of them are enforced by that one service. No PIN, no wait, no warning. That is not a small hole in the app; for those five seconds it *is* the app.",
        "Strict Mode already blocked that page, but only while a Strict session was actually running. The rest of the time nothing defended it at all. So the strongest lock in the app was guarding a door that stood, most days, in an open field.",
        "That page is now covered whenever the new guard is on, which it is from the moment you install this. Go to it and you get a block screen and a bounce to the home screen, exactly like Strict Mode has always done.",
        "You can still turn it off — this is not a trap, and an app you can never escape is one you eventually uninstall in frustration. But it is deliberately not quick: Profile ▸ Guard the off-switch, type the paragraph out (you cannot paste it), then wait **2 hours** with the guard still standing, and then you have **15 minutes** to actually do it. Cancel at any point, instantly, and nothing changes.",
        "The window is 15 minutes rather than a token few, on purpose. After a two-hour wait, a short window is a trap rather than a safeguard — miss it because you were asleep, driving or at work and you would pay the whole wait again for nothing.",
        "Changing your phone's clock does not shorten the wait. This is the fourth timer in this app to be attacked that way, so it was built clock-proof from the start rather than fixed later.",
        "Turning the guard back ON is always instant, and doing so cancels any wait you had started. Nothing that increases your protection should ever make you wait.",
        "One thing to be aware of, since it is the point rather than a side effect: from now on the fast way out is gone. If you genuinely need the Accessibility page for something else — turning on a different accessibility service, say — that costs you the two hours too.",
        "Also fixed, and worth explaining because you would never have found it: the guard has a backup way of recognising those Settings pages, for phones (yours included) where Android does not name screens predictably. It only ever recognised them in **English**. On a phone showing Settings in Arabic it silently matched nothing and did nothing — no error, no sign, just a defence that was not there. It now understands Arabic too. A block that fails to happen is invisible, which is exactly why this kind of thing has to be gone looking for rather than waited for.",
        "Smaller thing: the quote on the block screen can now sit on the left, in the centre or on the right — Profile ▸ Block screen ▸ Pieces ▸ Quote. The screen-wide Alignment setting never actually moved the quote, because the quote already spans the full width, so choosing 'Centred' appeared to do nothing to the one piece most worth centring.",
        "Still open, and the next thing worth doing: if the service does get switched off, websites are wide open, because every website block works by reading your browser's address bar through that same service. Guarding the off-switch makes it costly to get there; it does not put a second lock on the door. That second lock is a bigger piece of work.",
    )),
    VersionLog("1.101", "Jul 26, 2026", "Arrange the block screen yourself", listOf(
        "You can now show, hide, resize and reorder the pieces of the block screen, not just pick between four fixed ones. Profile ▸ Block screen: switch off the minutes counter or the quote, make the quote smaller or larger, move the app to the top, centre everything — whatever you want on it, in whatever order and size.",
        "Size is a nudge rather than a fixed number: Larger means 25% bigger than that layout intended, so each design keeps its own proportions instead of everything collapsing to one size. The quote also still shrinks itself when it is a long one, so it stays readable at whichever size you pick.",
        "There is a live preview at the top, and it is the real block screen rather than a picture of one — the same code that draws it on your phone draws it there, scaled down. A preview that is a separate drawing of the same thing eventually stops matching, and a preview you cannot trust is worse than none.",
        "The four looks from 1.100 are untouched. They are now starting points: pick the one closest to what you want, then adjust it. If you change your mind there is a reset that puts a layout back to how it was designed.",
        "One deliberate restriction: you can no longer change the block screen while Strict Mode is running. Until now this was allowed because it was purely how things looked — but being able to hide the parts that make the screen persuasive, at the exact moment you are trying to get past it, is not cosmetic. It was never a way to unblock anything (the screen still covers the app whatever you hide), but softening it mid-session is not something a Strict session should permit.",
        "A hole worth explaining, because it was the way out of the one lock that has no way out. Strict Mode locks every protective setting in the app — except, it turned out, the update button. And installing an update deliberately ended a running Strict session, on the reasoning that a new version is a clean slate. Put together, that meant: any time a newer version existed, you could end a Strict session in two taps. From now on a Strict session survives the update and keeps blocking, and because it does, the update no longer switches blocking off afterwards either — there is nothing to reactivate. Updating while a session runs is now genuinely just updating.",
        "The updater itself got its first proper going-over. It only ever checked that the download was the right *size*, and only when the server bothered to say what that size was — so a hotel or café Wi-Fi login page, which answers every request with its own perfectly-complete page, would have been handed to the installer as if it were the app. It now insists the reply is a genuine success and that the file really is an app before installing anything.",
        "Updating on a phone that hasn't yet been told to allow it used to dead-end: tapping Update opened the Android permission screen, and once you came back nothing happened — the popup was gone and the button had reset. Now it remembers what you were doing and carries on by itself the moment you return.",
        "The download popup now has a Cancel button. It covers the whole screen and had none, so a stalled download left you with nothing to tap.",
        "A gap in the after-update pause: the app recorded 'I am now on the new version' *before* switching blocking off, so if it was closed in the split second between the two, the pause never armed — and never could, because it then believed nothing had changed. The order is now the other way round, which makes the whole thing safe to repeat.",
        "The downloaded installer file is deleted once the update is in, instead of sitting in the app's storage until the next one replaces it.",
        "Also fixed a crash that was waiting for any phone on Android 7 — the update code called something that only exists from Android 8 onwards. Your phone was never affected, but it would have taken the app down instantly on an older one.",
    )),
    VersionLog("1.100", "Jul 26, 2026", "A much smarter coach, and clock changes can't weaken blocking", listOf(
        "Your AI coach got a serious upgrade. It was quietly running on Google's *fast* model with its reasoning turned down to almost nothing — both chosen originally to keep replies snappy. It now uses the best model available and is allowed to think properly before answering. Replies take longer (often 10-30 seconds, with the typing dots showing the whole time), and they should be noticeably deeper — actually reasoning about your week rather than reacting to the last number it saw.",
        "It also writes like a person now. It used to be capped at 80 words and forced to format everything into headings and bullet points, which is why it could read like a form letter. Now it just talks, at whatever length the question deserves, and only makes a list when it's genuinely listing steps.",
        "It remembers better. It used to only see the last 16 messages of your conversation, which is why it sometimes seemed to forget what you'd just discussed — that's now 40, and it keeps far more of your chat history. It can also hold more of what it's learned about you personally.",
        "And it now remembers its own advice. Before, it had no idea whether anything it suggested was ever tried, so it would re-suggest the same things and could never follow up. It now keeps a short dated note of what it recommended, checks it against what actually happened, and will tell you when something worked — or admit when it didn't and change the plan.",
        "Caught while re-reading the coach changes above: letting it think longer also meant that if a request ever hung, you'd have waited about four and a half minutes staring at 'Thinking…' before it gave up, because it retried a request that had already timed out. It now only retries the kinds of failure a retry can actually fix, and the ceiling came down to 90 seconds — still three times longer than a reply should need.",
        "One thing to know: it will tell you which brain it's using. Tap the icon in the top corner of the chat and look at the bottom of 'What your coach knows'. If it says a 'pro' model, the upgrade worked; if it still says 'flash', it couldn't reach the better one and quietly fell back rather than leaving you with no coach.",
        "App categorisation deliberately stays on the fast model — it's a throwaway label for an app name, so it doesn't need deep thought and shouldn't cost you a wait.",
        "Location schedules were working off a location with no expiry date. Your phone's position was read once and then trusted forever, which broke in both directions: a reading taken at the place you'd blocked kept blocking you everywhere you went afterwards, and a reading taken while you were away meant coming back to the place never started blocking at all. The reason it went unnoticed is that a stationary phone stops reporting its position — the app was waiting for you to move 25 metres before it would listen again. Your position now expires if it hasn't been confirmed recently, and the app keeps a steady heartbeat instead of waiting for movement.",
        "You can now design your own block screen, in Profile ▸ Block screen. Two separate choices that combine freely, so there are sixteen combinations.",
        "**Layout** — what's actually on it. **Editorial** is the one you have now (minutes reclaimed, then the quote, app in a footer). **Focus** shows just the app, large and centred, with no number and no quote — nothing to linger on. **Scoreboard** makes the minutes the whole screen. **Quote** gives the line the whole screen and shrinks the app to one line at the top.",
        "**Colour** — **Midnight** (what you have), **Aurora** (the blue-violet gradient edge to edge), **Paper** (light, for daylight) and **Ink** (pure black, no colour at all). Any colour works with any layout.",
        "It is only how it looks. Got it behaves identically in every combination, and nothing about blocking itself changes. If you want my suggestion: try **Focus** in **Ink**. Your current screen rewards you with a big number and a nice quote, which is pleasant to look at — arguably the opposite of what a screen designed to make you leave should be.",
        "A 'daily limit' schedule could never block at all if AppBlocker didn't have permission to read Android's usage statistics — every app would look like zero minutes used, so the limit was never reached. Nothing told you. That permission is offered as optional elsewhere in the app, because everywhere else it only affects the charts, so there was every reason to skip it and no way to find out it had quietly switched off a schedule you'd set. The editor now checks and offers to fix it, exactly like the Wi-Fi and Location schedules already did. (Open-count limits are unaffected — they don't use that permission.)",
        "In-app purchase blocking could miss the purchase screen entirely. Recognising a purchase sheet relies on Android telling the app which screen just opened — and in the one case where the app has to double-check what's really in front (added in 1.97 to stop block screens flashing in the wrong place), that piece of information was being thrown away. The purchase check has nothing else to go on, so it silently did nothing, and nothing re-checked afterwards. It's now carried through properly. If you have 'block purchases' switched on, this is the difference between it working and it quietly not.",
        "The YouTube Shorts check could put its block screen up after you'd already left. Checking the screen happens in the background and takes a moment, and nothing stopped that check from finishing after you'd moved on — so swiping to your home screen mid-check could land a Shorts block screen on your home screen (and count it as an attempt), and locking your phone mid-check could leave one stranded, waiting to greet you when you unlocked. That second one is the same problem 1.98 fixed, reaching you by a different route. The check now confirms you're still on YouTube before covering anything, and is properly stopped when you leave or lock the phone.",
        "Also verified rather than assumed: this version number crossing from 1.99 to 1.100 doesn't break the updater. Compared as text '1.100' looks *older* than '1.99', which would have silently stopped the app ever offering you another update. It compares them as numbers, correctly — and there are now tests holding that in place, because every future fix reaches your phone through it.",
        "The important one: setting your phone's clock backwards used to switch off the guard that keeps you out of the Accessibility settings during Strict Mode — and that page is where AppBlocker can be turned off completely. Strict Mode itself was never escapable that way (its countdown already ignores the clock), but the thing standing between you and the off switch was. Get bounced once, wind the clock back an hour, and the guard would go quiet. Fixed.",
        "The same mistake was behind five other timers, all of them measuring short waits with the phone's clock instead of a stopwatch. Winding the clock back froze all of them in whichever state does nothing: blocked apps stopped being covered at all, the blocked-word scan stopped running on busy pages, and the 'Got it' block screen could get stuck on screen and refuse to go away. Winding it forward did the opposite and brought back being blocked twice for one open. Every one of these now uses a stopwatch that nothing on the phone can move.",
        "YouTube Shorts could uncover itself. Whenever the app briefly couldn't read what was on screen — which includes the moment its own block screen is in front — it concluded you were no longer on a Short and took the cover away, then put it back a moment later. You got a watchable gap each time round. It now leaves the cover alone when it can't tell, and only removes it when it can actually see you've scrolled out.",
        "Fixed a freeze that would have arrived on its own in late October. On the day the clocks go back, your local day is 25 hours long — and the code that builds your hourly usage chart could never finish counting it. The Insights screen and the coach would have hung and drained the battery. This one now has tests so it can't come back.",
        "Two ways blocking could silently fail open, both now closed. If the built-in adult word and site lists ever failed to load, the app carried on with empty lists — matching nothing, while the switches still said ON — and it remembered that empty state until the app was next restarted. The most likely moment for that to happen is right after AppBlocker updates itself, which is precisely when the adult filter is the only protection still meant to be running. It now retries instead of giving up, and tells you on the Profile page that something went wrong.",
        "Similarly, if the app's check for 'which apps are browsers' ever came back empty, it believed your phone had no browser at all — so browsers were treated as ordinary apps: no adult site list, no blocking of your blocked apps' websites, no unsupported-browser block. Nothing re-checked, so it stayed that way until you next installed or removed an app. An empty answer is now treated as a failed check rather than as the truth, which is the same rule your home screen already got in 1.96.",
    )),
    VersionLog("1.99", "Jul 25, 2026", "Six problems found by going looking for them", listOf(
        "Nothing here was reported — all of it came from deliberately auditing the blocking code for ways it could quietly stop working. That matters because a block screen appearing when it shouldn't is something you notice, while a block that silently never happens is invisible.",
        "The biggest one: after every single app update, blocking was completely off until you tapped Reactivate on the Blocking tab — and nothing else told you. The Profile page still said 'Protection active', and no notification arrived. So after each release the app was blocking nothing while insisting it was fine. Now the status says 'Paused after update' honestly, and you get a notification about it.",
        "Blocking could also stop reacting to your own settings permanently. The part that watches your rules, schedules and blocked words had no way to recover if it ever hit an error — it just stopped, and blocking carried on using whatever rules it had at that moment, forever, with the health check still reporting everything was fine. It now retries until it recovers.",
        "Two ways to skip a protection by changing your phone's clock, both closed. Turning the adult-word pack off makes you wait 24 hours first — winding the clock forward a day used to skip that wait entirely, which made the app's strongest protection its cheapest to switch off. The 30-minute lockout after a blocked word had exactly the same hole. Both now measure real elapsed time, the same way your Strict Mode and Timer sessions already did.",
        "Errors the app quietly swallowed to avoid crashing were being recorded and then never shown to anyone — the very thing that let the problem above hide. Profile now shows a row when there have been any, so you can tell us. And the health check itself could crash the app when it tried to warn you, which is the last thing it should do.",
        "A Wi-Fi schedule naming a specific network could never match at all without location access, because Android hides the network name — it silently compared as 'not this network'. The schedule editor now checks and offers to fix it instead of mentioning it in small print.",
        "Finally, your daily counts and history could be wiped or misread around New Year. Days were being compared by subtracting date stamps, which stops meaning anything across a year boundary — so tidying up old data would have deleted everything back through January. Blocking itself was unaffected.",
    )),
    VersionLog("1.98", "Jul 25, 2026", "The notification shade no longer breaks a block", listOf(
        "Also, from going looking rather than waiting to be bitten: three more ways blocking could quietly stop working, all from the same cause. The app kept its own separate note of whether the Shorts cover was showing, instead of just looking at the screen — and whenever that note went out of date, three things went wrong. A blocked YouTube could have its block screen taken away entirely (if YouTube became fully blocked while you were on Shorts, then you turned Shorts blocking off or paused Quick Block). Locking your phone while Shorts was covered skipped all of the tidying up, so unlocking could show you a leftover block screen over whatever you were doing. And the safety net that clears a stray block screen off your home screen refused to run. The app now just checks what's actually on screen, so it can't get out of date.",
        "Pulling down your notification shade over a block screen used to take the block screen away — and when you closed the shade again, the blocked app was counted as a brand-new attempt, adding another 3 minutes to your 'minutes reclaimed'. Pressing a volume key or getting a notification pop-up did the same. This affected both Blocking modes, not just Allowlist.",
        "The reason: the shade, the volume dialog, notification pop-ups and your keyboard all genuinely become 'the thing in front' as far as Android is concerned. The blocker took that as you having left the blocked app, so it packed the block screen away. But they don't replace the app — they sit on top of it, and the app underneath is still just as blocked.",
        "Those are now recognised for what they are, so the block screen stays put underneath them, nothing gets counted twice, and returning to the app no longer adds a phantom 'open' to your daily open limits. Going to your home screen, or switching to a genuinely different app, still puts the block screen away as it always did.",
    )),
    VersionLog("1.97", "Jul 25, 2026", "The last of the Allowlist flashing", listOf(
        "Fixes the rest of it: turning Allowlist mode on could still put a block screen over AppBlocker's own screens, and you could still catch a flash in an app you'd allowed, like WhatsApp.",
        "One cause behind both. Android tells the blocker 'this app's window changed' — but apps send that in the background too, when a message arrives or something reloads behind what you're actually looking at. The blocker took every one of those as 'this is the app in front of you now'. In your old Blocklist setup that rarely mattered, because a random background app usually isn't one you'd blocked. In Allowlist mode everything is blockable, so a background app could get you a block screen over whatever you were really doing — and a background app you'd ALLOWED could make a legitimate block screen disappear, which the real app then brought straight back. That's the flash.",
        "Now the blocker checks what's genuinely in front before acting on one of those messages, the same way it already did for the other kind of update it receives. If it can't tell, it still blocks — an app can't dodge it by hiding. And if a real app switch just arrived a fraction early, it re-checks a moment later so blocking still happens immediately rather than being missed.",
    )),
    VersionLog("1.96", "Jul 25, 2026", "Allowlist mode stops flashing the block screen", listOf(
        "Fixes the block screen flashing up on your home screen and over AppBlocker's own screens after turning on Allowlist mode. This was our fault in 1.95: the new 'keep the block screen up until you've really left the app' behaviour had no way to tell that you'd already reached your home screen, because on your phone the swipe-up Home gesture tells apps nothing at all and the block screen itself can look like the app that's in front. Not knowing was treated as 'you're still in the app', so the screen was held for a couple of seconds over your home screen — every single time you tapped 'Got it'. In Allowlist mode almost everything is blocked, so you tap 'Got it' constantly and it looked like flashing.",
        "Now the block screen is only held on when the app can actually be seen to still be in front. When it genuinely can't tell, it steps aside straight away rather than sitting there.",
        "AppBlocker's own screens can no longer be covered by a block screen. The blocker couldn't tell its own settings screens apart from its own block screen — both are the same app — so being in AppBlocker was mistaken for 'the blocked app is still up'.",
        "Your home screen can no longer be blocked in Allowlist mode. If the check for 'which apps are home screens' ever failed or was skipped, the answer 'not a home screen' was remembered permanently, and from then on Allowlist mode treated your home screen as just another app to block.",
        "Your phone app can no longer be blocked in Allowlist mode either — one of the two ways it was recognised silently stopped working on newer Android versions.",
        "And the apps that must always keep working in Allowlist mode — your home screen, keyboard, phone, Settings and AppBlocker itself — are now protected against every kind of block, including a blocked-word lockout, which used to be able to override that protection.",
    )),
    VersionLog("1.95", "Jul 25, 2026", "No more being blocked twice for one open", listOf(
        "Opening a blocked app could block you twice: the block screen appeared, you tapped 'Got it', and a few seconds later it appeared all over again — and both showings were counted, so your 'minutes reclaimed' jumped by 6 instead of 3 and Insights over-reported how often you'd tried.",
        "The cause was that 'Got it' took the block screen away and asked the phone to go Home as two separate things. When the Home request didn't land — which happens on this phone, where the gesture-navigation Home often tells apps nothing at all — the blocked app was left sitting there with nothing covering it, and the blocker then treated it as a brand new attempt and blocked it again.",
        "Now 'Got it' means 'get me out of here': the block screen stays up until your phone is really off the blocked app, asking for Home again if the first try was ignored. So the app is never left uncovered, and you never see a second block screen for a single open. (If Home genuinely can't be reached, the screen still steps aside after a few seconds so you can never get stuck.)",
        "One open is now counted once, no matter how many times the screen has to be drawn to keep you out — so the 'minutes reclaimed' number and your Insights attempt counts are honest. Deliberately opening a blocked app again still counts as the new attempt it is.",
        "Also fixed: a late message from an app you'd just left could throw the block screen over your home screen and add a phantom 'open' to your daily open limits.",
        "YouTube Shorts had the opposite problem, and it was a hole in blocking: tapping 'Got it' on the Shorts cover left the app thinking Shorts was still covered when it wasn't, so if you stayed in YouTube the cover never came back and Shorts was scrollable until you navigated out of Shorts and back in. Now the cover returns while you're still on a Short, and the rest of YouTube keeps working as before.",
        "A blocked word used to count as two attempts. Finding a word shows the 'word was found' screen and also locks that app for 30 minutes, so the 'Locked' screen followed moments later and both were counted. It's one word, so it now counts once — and it still shows up under Websites in Insights, with no phantom attempt added to the app's own row.",
        "The website, purchase and Strict-Mode screens now wait for your phone to actually leave the app before they step aside, the same as the app block screen — so none of them can reappear because the trip Home didn't take.",
    )),
    VersionLog("1.94", "Jul 24, 2026", "Guide cards line up properly again", listOf(
        "In the Scenarios guides and the Dopamine detox, the thinker's name and the headline next to it were laid out badly whenever the headline was too long for one line: the name dropped to the bottom line and left a big empty gap above it. Now they sit side by side when they fit, and the headline moves onto its own line under the name when it doesn't — which is what you'll see at larger font sizes.",
        "The big number on the numbered rules and steps now sits beside the rule's title instead of floating in the middle of a long paragraph.",
    )),
    VersionLog("1.93", "Jul 24, 2026", "Overnight schedules, restarts and updates now behave", listOf(
        "Schedules that cross midnight now follow the day you picked. A 22:00–06:00 schedule set for Monday runs from Monday night straight through to Tuesday morning — before, it stopped at midnight and instead blocked you early on Monday morning, which belonged to Sunday night.",
        "Timers, Pomodoro and Strict sessions now stay honest across a phone restart. The app remembers which boot a session was started in, so after a reboot it falls back to the calendar clock instead of trusting a stopwatch that reset to zero — a finished session can't come back to life, and a running one can't end early.",
        "A Strict session you start right after updating the app is no longer cancelled by the update. The 'clear the old session' step now checks which app version created the session, in a single database operation, so only sessions from before the update are cleared.",
        "Blocking is harder to knock over. An unexpected error inside the blocker can no longer take blocking down with it — it's caught, noted and blocking carries on. And if your phone ever kills the blocker in the background (Xiaomi and friends do this), the app now notices the silence and tells you to switch it off and on again, instead of leaving you unprotected without knowing.",
    )),
    VersionLog("1.92", "Jul 23, 2026", "The block screen stops flickering", listOf(
        "Opening a blocked app used to make the block screen flash away and come back. It now sits solidly over the app and stays put — and tapping 'Got it' is what sends you to your home screen, nothing else.",
    )),
    VersionLog("1.91", "Jul 22, 2026", "Allowlist mode + a cleaner Quick Block, and the coach just works", listOf(
        "Quick Block now has an Allowlist mode. Instead of choosing what to block, you choose the few apps you want to KEEP — and everything else is blocked while a Quick Block, Timer or Pomodoro is running. Tap the 'Blocking' chip at the top of the Quick Block editor to switch between Blocklist and Allowlist. Your two lists are kept separately, so switching back and forth never loses either one.",
        "Allowlist mode always keeps the essentials working, so the phone can never lock you out: your home screen (launcher), phone/dialer, keyboard, the notification shade, Settings, and AppBlocker itself stay open no matter what — only the apps you didn't allow are blocked.",
        "The Quick Block editor got an AppBlock-style makeover: a full-screen 'Blocking mode' chooser with a Continue button, and a tidy summary card where 'Apps' (and 'Websites & words') open their own screens — instead of one long scroll.",
        "The AI Coach now works for everyone with nothing to set up — no more pasting a Gemini API key. It's powered by our own small server, so it's ready the moment you open Insights. If you're ever offline the coach just waits, and the rest of the app keeps working as normal.",
        "Fixed the block screen showing twice on tablets: after you tap 'Got it', the app now reliably takes you Home (pressing again if the tablet ignored the first try) and won't re-block during the transition. Also, when an app is fully blocked, the YouTube Shorts detector no longer repaints the cover and double-counts the attempt.",
    )),
    VersionLog("1.90", "Jul 19, 2026", "Scenarios for the hard moments + a Quick Settings button", listOf(
        "New Scenarios section in your Profile: short guides for the hard moments — relapse, can't focus, feeling lazy, can't sleep, the urge to scroll, and feeling overwhelmed. Each one opens with real quotes from thinkers like Marcus Aurelius, Seneca, Viktor Frankl and Sartre. (The Dopamine detox guide stays right where it was.)",
        "You can now add a Quick Block button to your phone's pull-down Quick Settings panel, so you can turn blocking on and off without even opening the app.",
    )),
    VersionLog("1.89", "Jul 19, 2026", "Smarter social-website blocking", listOf(
        "Blocking a social app (Facebook, Instagram, TikTok, YouTube and so on) still blocks that app's actual website in your browser — but it no longer blocks pages that merely mention the app's name, and no longer locks your whole browser for 30 minutes over it.",
    )),
    VersionLog("1.88", "Jul 19, 2026", "Block-screen polish + clearer word blocks", listOf(
        "The block screen no longer flashes over your home screen after you unlock the phone.",
        "When a word is blocked, the screen now tells you which word it was. And the plain word 'porn' no longer blocks non-sexual apps — the fuller phrases still do.",
    )),
    VersionLog("1.87", "Jul 19, 2026", "Dopamine Detox header fix", listOf(
        "Small fix: the Dopamine Detox guide's header is compact again — the tall empty blue box at the top is gone.",
    )),
    VersionLog("1.86", "Jul 19, 2026", "Dopamine Detox: the full rulebook", listOf(
        "The Dopamine Detox guide is now a full rulebook: three truths from Buddhism, 25 clear rules for beating the scrolling and porn cravings, a craving SOS, and a fresh, cleaner design.",
    )),
    VersionLog("1.85", "Jul 18, 2026", "The Dopamine Detox guide arrives", listOf(
        "New: a full Dopamine Detox guide on the Profile page — what endless scrolling does to your brain, and a 7-day reset plan.",
        "Fixes: templates no longer add app-name words (like 'youtube') to your blocked words, and any added before are cleaned up once; the turn-off typing challenge now works properly with the keyboard open; and the challenge uses longer words.",
    )),
    VersionLog("1.84", "Jul 18, 2026", "Turning off adult protection got harder", listOf(
        "The typing challenge for turning off the adult content pack now works properly — full-screen, the keyboard can't hide it, and capitals or extra spaces don't matter.",
        "Turning the pack off is also much harder now: even after the typing challenge, the pack keeps protecting you for another 24 hours before the switch actually takes effect. And the block screen no longer flashes over your home screen or right after you press 'Got it'.",
    )),
    VersionLog("1.83", "Jul 18, 2026", "Stronger word blocking", listOf(
        "Word blocking is much stronger: pressing 'Got it' no longer lets you slip back and keep reading — the app where the blocked word appeared locks completely for 30 minutes. Scanning is faster too, and now catches words while you scroll.",
        "After updating, turn the AppBlocker accessibility service off and on once in your phone's settings.",
    )),
    VersionLog("1.82", "Jul 17, 2026", "Delete schedules from the list", listOf(
        "Schedules can now be deleted right from the list — a trash icon with a confirm, hidden during Strict Mode.",
        "And two labels no longer break mid-word on larger font sizes (the Active badge on templates and the Location schedule tile).",
    )),
    VersionLog("1.81", "Jul 17, 2026", "Timer Save button, pinned down", listOf(
        "Another fix for the timer picker's Save button: it now keeps a guaranteed fixed distance from the bottom of the screen, instead of relying on the phone to report its gesture-bar height — some phones report zero, which defeated every measured fix.",
    )),
    VersionLog("1.80", "Jul 17, 2026", "Timer Save button reads the real nav bar", listOf(
        "The timer picker's Save button now reads the navigation-bar height straight from Android's root window — the deepest, most reliable source, immune to the popup-window quirks that defeated the earlier fixes.",
    )),
    VersionLog("1.79", "Jul 17, 2026", "Timer Save button fixed at the source", listOf(
        "Fixed for real: the Save button in the timer picker (Strict Mode and Quick Block) now measures the navigation bar from the app's main window — the popup was reporting it as zero, which is why earlier fixes didn't stick.",
        "Also: Instructions topic pages got a cleaner layout, with titled point cards instead of plain text.",
    )),
    VersionLog("1.78", "Jul 17, 2026", "Full-page settings & icon picker", listOf(
        "Smoother settings: Instructions topics now open as their own full page (much easier to read than the old expanding cards), and the app-icon chooser is a clean full-page grid instead of the cramped popup.",
    )),
    VersionLog("1.77", "Jul 17, 2026", "Instructions: a guide to every feature", listOf(
        "New: Profile ▸ Instructions — a built-in guide explaining every feature in detail, across thirteen topics: protection setup, Quick Block, templates, all five schedule types, Strict Mode, blocked words and websites, the block screen, YouTube Shorts, the full Insights tab, goals/mood/AI Coach, PIN lock, updates, and personalization.",
    )),
    VersionLog("1.76", "Jul 17, 2026", "Your chosen icon on the block screen", listOf(
        "The block screen now shows the app icon you actually picked in the icon switcher (it used to show the default logo).",
        "And the quotes got a quality pass — cliches cut, misattributions fixed, and stronger lines added from William James, Mary Oliver, Seneca, Pascal and James Clear.",
    )),
    VersionLog("1.75", "Jul 17, 2026", "One block counts once", listOf(
        "Fixed: one blocked attempt was being counted (and re-shown) several times. A single block now records exactly one entry, the quote stays put while the block screen is up, and tapping 'Got it' no longer re-triggers the same block on the way to your home screen.",
    )),
    VersionLog("1.74", "Jul 17, 2026", "Smarter, more precise blocking", listOf(
        "No more false blocks on the home screen, and the block screen now explains WHY you're blocked — the schedule name, a daily limit, Quick Block, Strict Mode, or the exact word it matched.",
        "Blocked words now match the site you're actually on in Chrome instead of any page that mentions them, blocked apps get covered faster, and blocking YouTube or a social app now also covers its website and short links (youtube.com, youtu.be, t.co, redd.it, fb.watch…).",
    )),
    VersionLog("1.73", "Jul 16, 2026", "Save button lifted above the gesture bar", listOf(
        "Fixed: the Save button in the Strict Mode timer picker (and the schedule, template and Quick Block editors) sat too low, inside the gesture-navigation area — it now sits clearly above it.",
    )),
    VersionLog("1.72", "Jul 14, 2026", "Android 15 polish", listOf(
        "Polish for Android 15 phones: the AI Coach chat, the What's new page and the PIN lock screen no longer draw behind the status bar, and the PIN screen's Unlock button stays above the keyboard.",
    )),
    VersionLog("1.71", "Jul 14, 2026", "Setup wizard always reachable", listOf(
        "Fixed: on phones with larger text or display size, the setup wizard could hide its Continue button off the bottom of the screen with no way forward. The steps now scroll, the button is always visible, and the wizard no longer draws behind the status bar on Android 15.",
    )),
    VersionLog("1.70", "Jul 13, 2026", "Fewer false word blocks + a time-aware coach", listOf(
        "Fewer false adult-word blocks: only real porn vocabulary triggers a block now — everyday phrases (adult content, queen of spades, cream pie…) no longer do.",
        "And the AI Coach now knows the time of day, so no more 'goal hit!' at 9am, or praising your night's sleep as phone-free time.",
    )),
    VersionLog("1.69", "Jul 12, 2026", "Update-ends-Strict, now for real", listOf(
        "THIS is the update that ends your running Strict Mode session. The 1.67 → 1.68 update couldn't do it: 1.67 was too old to leave the note that 1.68 needed to recognize an update had happened — so nothing fired, not even the Reactivate banner. From 1.68 onward the note exists, which makes this the first update the feature can actually see.",
        "Also made it bulletproof: ending the session is now remembered as an unfinished job and retried on every app open until it truly lands — so even if Android cuts the first attempt short (it can, right after an install), the session still ends.",
    )),
    VersionLog("1.68", "Jul 12, 2026", "Calm updates + Insights reordered", listOf(
        "After every app update, ALL blocking now pauses until you're ready: a banner on the Blocking tab waits for your tap on 'Reactivate blocking'. Explore what's new first, then switch protection back on.",
        "An update is a clean slate: a running Strict Mode session ends together with the pause, so you restart fresh on the new version. Only the adult content pack never pauses (its off-switch stays the deliberate one).",
        "The Insights page flows better now: your AI Coach moved up right after the usage cards, and Focus, Distractions and the Mood check-in close out the page at the bottom.",
        "Fixed a sneaky bug where Strict Mode could switch itself back ON: after a phone restart, if the clock was briefly wrong, an old finished Strict session could come back to life. Finished sessions are now erased the moment they end, and an impossible clock can't revive them. A restart DURING a real session still keeps you locked, exactly as intended.",
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
