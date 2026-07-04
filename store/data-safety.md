# Play Console "Data safety" form — answers (for the play flavor)

Derived from the privacy policy (docs/privacy-policy.md). The play flavor has no updater and no
location permissions; the only network traffic is the OPTIONAL user-keyed Gemini call.

## Does your app collect or share any of the required user data types?
**Yes** (only because of the optional AI Coach; if the user never adds a Gemini key, nothing leaves the device).

## Data types

**App activity → App interactions (usage minutes per app, app names)**
- Collected: Yes (sent to Google Gemini when the user uses the AI Coach)
- Shared: No (transmitted to a service provider — Google — at the user's request; not "shared" in Play's sense of third-party advertising/analytics)
- Processed ephemerally: Yes (used to generate the reply; we run no servers and store nothing)
- Required or optional: **Optional** — entire feature off until the user pastes their own API key
- Purpose: App functionality (personalized coaching)

**Personal info → Name; Other info (facts the user chooses to tell the coach)**
- Collected: Yes (only within coach chats, at the user's initiative)
- Shared: No
- Optional: Yes
- Purpose: App functionality

**Messages → Other in-app messages (the user's chat messages to the coach)**
- Collected: Yes (sent to Gemini to generate the reply)
- Shared: No
- Optional: Yes
- Purpose: App functionality

## NOT collected (answer No everywhere else)
Location (play build has no location permissions) · Web browsing history (screen text is checked
on-device and never stored/sent) · Contacts · Photos · Files · Audio · Financial info · Health ·
Identifiers (no accounts, no device IDs sent anywhere).

## Security practices
- Data encrypted in transit: **Yes** (HTTPS to the Gemini API)
- Users can request data deletion: **Yes** — all data is on-device; deleting the app (or "Forget
  everything" in the coach) removes it. Contact: boudiahdab2003@gmail.com
- No data collected by us at all: we operate no servers, no analytics, no ads SDKs.

## Account creation
None.
