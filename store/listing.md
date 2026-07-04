# Google Play listing — draft

## App title (30 chars max)
**AppBlocker: AI Focus Coach** (26 chars)

## Short description (80 chars max)
**Block distracting apps & sites — with an AI coach that knows you. (66 chars)**

Alternates:
- Beat phone addiction: hard blocks, schedules, and a personal AI coach. (71)
- The app blocker with a coach: block apps, set goals, win your time back. (73)

## Full description (4000 chars max)

**Not just a blocker — a coach in your pocket.**

Every app blocker can block apps. AppBlocker is the only one with a personal AI coach that actually knows you: it sees your real screen time, remembers why you're trying to change, and works with you on goals — day after day.

**🤖 YOUR AI COACH**
• Chats with you about your real numbers — never generic advice
• Gets to know you naturally: why you block, what tempts you, what you'd rather be doing — and remembers
• Sets measurable goals with you, tracks them live, and celebrates your wins
• Writes fresh, personal tips several times a day
• You see everything the coach knows about you, and can erase it any time

**🚫 SERIOUS BLOCKING**
• Quick Block: one tap to block your chosen apps — instantly, full-screen
• Blocking a social app also blocks its website in your browser
• Block YouTube Shorts only — the rest of YouTube keeps working
• Schedules: block by time window, daily usage limit, or number of opens
• Timer & Pomodoro sessions for focused work
• Strict Mode: an unstoppable focus session that can't be ended early

**🌐 WEB & CONTENT FILTER**
• Block adult sites and your own keywords inside browsers
• Block in-app purchase screens
• Pre-block apps you haven't even installed yet

**📊 INSIGHTS THAT MEAN SOMETHING**
• Daily, weekly and 30-day screen-time trends
• Goals with live progress bars, 7-day hit dots, and streaks
• Most-used and most-opened apps, blocked attempts, phone unlocks

**🔒 HARD TO BYPASS**
• PIN-protect your settings
• Prevent uninstall while protection is on
• Mid-use enforcement: limits kick in even if you never leave the app

**🛡️ PRIVACY FIRST**
Everything stays on your phone: no accounts, no ads, no analytics, no tracking. The optional AI Coach uses your own free Google Gemini key, and you control everything it remembers. Full policy: https://boudiahdab2003-art.github.io/AppBlocker/privacy-policy

Built by someone fighting phone addiction — for everyone fighting it.

## Category
Productivity

## Contact
boudiahdab2003@gmail.com

## Privacy policy URL
https://boudiahdab2003-art.github.io/AppBlocker/privacy-policy

## Graphics needed (Play requirements)
- App icon 512×512 PNG (export from the adaptive icon)
- Feature graphic 1024×500 PNG (TODO — design later)
- 2-8 phone screenshots (drafts in `store/screenshots/`; retake with polished demo data before submission)

## Notes for the Play Console forms
- **AccessibilityService declaration:** core functionality = blocking user-selected distracting
  apps/sites (digital wellbeing). In-app prominent disclosure + consent dialog shown before
  enabling ("How blocking works"). `isAccessibilityTool` is NOT set (not an assistive-tech app).
- **PACKAGE_USAGE_STATS declaration:** core functionality = daily usage limits + screen-time
  insights, user-facing, on-device only.
- The Play build contains NO location permissions and NO self-updater (github flavor only).
