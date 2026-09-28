# AppBlocker Privacy Policy

**Last updated: September 28, 2026**

AppBlocker is a screen-time and app-blocking app. It is built around one principle: **your data belongs on your device.**

## What AppBlocker stores — and where

Everything AppBlocker needs to work is stored **only on your phone**:

- Your blocked apps, schedules, keywords and settings (local database and app preferences)
- Your screen-time statistics (read from Android's built-in usage-stats system)
- The display name you choose (optional — the app asks once during setup and you can skip it or clear it later; it is only used to greet you in the app)
- Your goals, coach chat history and the personal facts the coach remembers
- Your PIN (stored as a one-way hash, never as plain text)
- Your recovery counter and your journal entries — see the section below

AppBlocker has **no accounts, no sign-up, no sign-in, no analytics, no ads, and no tracking**. There is no user account of any kind, and nothing you enter is transmitted to us. The one exception is optional: the direct-download build can send short reports about how the blocker is running, **only if you say yes** — see [Reports to the developer](#reports-to-the-developer-direct-download-build-only) below. They never contain what you type, block or see.

Uninstalling the app permanently deletes all of this data from your phone. Reports you agreed to send are kept on the developer's private issue tracker; to have them removed, write to the address at the end of this policy.

## Your counter and your journal

AppBlocker can count how long it has been since your last relapse, and it holds a journal with one entry per calendar day.

This is the most personal information the app will ever contain, and it is treated accordingly:

- Both are stored **only on this phone**, in the app's own database and preferences.
- **The AI Coach is never given either of them** — not the journal text, and not the day count.
- **They are never included in a bug report.** A report can only carry a fixed list of named settings values; there is no field in it that could hold your writing or your count.
- They are not in any report the direct-download build sends.
- Journal entries are **kept until you delete them**. Emptying an entry removes that day; uninstalling the app removes all of it. There is no copy anywhere else, and no way to recover one.
- If you set a PIN, both are behind it, because the PIN covers the whole app.

## Which version of AppBlocker you have

AppBlocker ships in two builds, and two sections below apply to only one of them:

- The **Google Play** build: updates arrive through the Play Store, and location-based schedules are not included.
- The **direct-download** build (from the project's GitHub releases): can update itself, and supports location-based schedules.

## The Accessibility service

To block apps, AppBlocker uses Android's Accessibility service to detect which app is on screen, and — in web browsers only — to check the page address and visible text against your blocked keywords. **All of this checking happens entirely on your device.** Screen content is never stored or sent anywhere, and it is never used for anything but blocking — no advertising, no analytics, no profiling.

Before AppBlocker ever sends you to Android's Accessibility settings, it shows a full-screen explanation of exactly this and asks you to agree. You can decline and keep using the rest of the app; blocking simply will not run. Turning the service off in Android's settings stops all screen reading immediately.

## The AI Coach (optional)

The AI Coach is **off until you choose to enable it** by pasting your own free Google Gemini API key into the app. If you enable it:

- The app sends **aggregate statistics** (screen-time totals, app names with usage minutes, blocked-attempt and unlock counts), your **blocking setup**, your **goals**, the **personal facts you've shared with the coach**, and your **chat messages** to Google's Gemini API to generate tips and replies.
- This data is processed by Google under the [Google API Terms of Service](https://developers.google.com/terms) and the [Gemini API terms](https://ai.google.dev/gemini-api/terms). Your API key is stored only on your device.
- The coach never receives your screen content, browsing pages, PIN, or precise location.
- You can see everything the coach remembers about you (person icon in the chat), erase it at any time ("Forget everything"), or stop all AI traffic by removing your key.

## Update check (direct-download build only)

On launch, the direct-download build contacts GitHub to check whether a newer version exists. This request contains no personal data. **The Google Play build does not do this** — Play delivers its own updates.

## Reports to the developer (direct-download build only)

The direct-download build can send short reports that help fix problems: when blocking stops and comes back, a summary once a week, when something inside the app goes wrong, and a description of the phone and the app's setup once per version. **A new install asks first — "Send reports to the developer?" — and sends nothing until you say yes.** You can change the answer at any time in Profile ▸ Automatic reports. A report you write yourself in Profile ▸ Report a problem is sent when you press Send.

A report carries the app and Android versions, the phone model, your settings, and what the blocker knows about itself: whether it is running, when it last saw anything, how fast blocks appear, and every time blocking stopped and came back. **It never carries** your blocked words, the sites you visit, which apps you block, anything on your screen, your location, or anything from Recovery. Reports go to a private issue tracker that only the developer can read. **The Google Play build does not send reports.**

## Location (direct-download build only)

If you create a Location-based blocking schedule, the app uses your device's location **on-device** to decide when to block. Location is never transmitted anywhere. **The Google Play build does not include location schedules and does not request location permission.**

## Children

AppBlocker is not directed at children under 13.

## Changes

If this policy changes, the update will be published at this address and noted in the app's changelog.

## Contact

Questions or requests: **boudiahdab2003@gmail.com**
