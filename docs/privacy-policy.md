# AppBlocker Privacy Policy

**Last updated: July 4, 2026**

AppBlocker is a screen-time and app-blocking app. It is built around one principle: **your data belongs on your device.**

## What AppBlocker stores — and where

Everything AppBlocker needs to work is stored **only on your phone**:

- Your blocked apps, schedules, keywords and settings (local database and app preferences)
- Your screen-time statistics (read from Android's built-in usage-stats system)
- Your goals, coach chat history and the personal facts the coach remembers
- Your PIN (stored as a one-way hash, never as plain text)

AppBlocker has **no accounts, no sign-up, no analytics, no ads, and no tracking**. We do not run any servers, and we never see your data.

Uninstalling the app permanently deletes all of this data.

## The Accessibility service

To block apps, AppBlocker uses Android's Accessibility service to detect which app is on screen, and — in web browsers only — to check the page address and visible text against your blocked keywords. **All of this checking happens entirely on your device.** Screen content is never stored or sent anywhere.

## The AI Coach (optional)

The AI Coach is **off until you choose to enable it** by pasting your own free Google Gemini API key into the app. If you enable it:

- The app sends **aggregate statistics** (screen-time totals, app names with usage minutes, blocked-attempt and unlock counts), your **blocking setup**, your **goals**, the **personal facts you've shared with the coach**, and your **chat messages** to Google's Gemini API to generate tips and replies.
- This data is processed by Google under the [Google API Terms of Service](https://developers.google.com/terms) and the [Gemini API terms](https://ai.google.dev/gemini-api/terms). Your API key is stored only on your device.
- The coach never receives your screen content, browsing pages, PIN, or precise location.
- You can see everything the coach remembers about you (person icon in the chat), erase it at any time ("Forget everything"), or stop all AI traffic by removing your key.

## Update check

On launch, the app contacts GitHub to check whether a newer version exists. This request contains no personal data.

## Location (optional)

If you create a Location-based blocking schedule, the app uses your device's location **on-device** to decide when to block. Location is never transmitted anywhere.

## Children

AppBlocker is not directed at children under 13.

## Changes

If this policy changes, the update will be published at this address and noted in the app's changelog.

## Contact

Questions or requests: **boudiahdab2003@gmail.com**
