# The spare GCP VM (future server-side features)

The owner has a small always-on server left over from a previous project. It is
**available for AppBlocker** whenever a server-side feature becomes worth building.
Nothing runs on it for AppBlocker yet.

## The machine

| Fact | Value |
|---|---|
| Provider | Google Cloud Platform |
| Machine type | e2-micro (free-tier eligible; 2 vCPU, 1 GB RAM) |
| OS | Debian GNU/Linux 12 |
| Zone | us-east1-b |
| Network tag | `hermes-bot-vm` |
| Access | GCP web console SSH (console.cloud.google.com → Compute Engine → SSH in browser). No gcloud CLI set up locally. |
| Project ID / external IP | **Unknown — look up in the GCP console on first use.** |

## History & standing instructions

- The VM previously ran a **Telegram bot ("Hermes")** 24/7, built with a different
  agent. The owner **does not want that bot anymore**.
- **Standing instruction: on first SSH into the VM, wipe the Hermes bot completely** —
  stop/disable its service, delete its files, and **retire its Telegram bot token**
  (revoke via @BotFather). Any future Telegram feature uses a *fresh* bot.
- Free-tier notes: only one e2-micro per account is free, and free egress is limited —
  keep any service low-traffic. The VM costs nothing while idle, so it stays alive.

## Use cases, ranked (with plans)

### 1. Gemini proxy for the AI Coach — highest value — **LIVE ✅**

**Live since 2026-07:** the VM runs Caddy as a reverse proxy at
`https://appblocker-coach.duckdns.org` (DuckDNS → the VM's external IP,
firewall opens tcp:80,443). Caddy holds the Gemini key + shared secret in
`/etc/caddy/coach.env` (loaded via a systemd drop-in), rejects requests without
`Authorization: Bearer <secret>` (403), and injects `x-goog-api-key` before
forwarding to Google. The app's `gradle.properties` carries the matching
`coachProxyUrl`/`coachProxySecret`, so the coach works with no on-device key.
Hermes was fully wiped from the VM first (service, files) per the standing
instruction. To rotate the secret: edit `/etc/caddy/coach.env` + `gradle.properties`,
`sudo systemctl restart caddy`, rebuild. Original design notes below.


Today the coach requires the user to paste a Gemini API key on-device
(`AiCoach.setApiKey`). A tiny proxy makes the coach work out of the box.

**Status (2026-07):** the *app side is built and merged*. `AiCoach` routes through
the proxy whenever `BuildConfig.COACH_PROXY_URL`/`COACH_PROXY_SECRET` are set (from
root `gradle.properties`), sending our shared secret as `Authorization: Bearer …`
instead of the Google key; it falls back to the user's own on-device key if the
proxy has a transient failure, and the coach shows its existing `Unavailable` state
when nothing is reachable. `AiCoach.coachAvailable(ctx)` gates the "add a key" UI.
Owner chose: HTTPS via **DuckDNS**, proxy enabled in **all builds** (shared secret
is therefore effectively public — natural cap is the Gemini key's own quota).

**What's left (the VM):** nothing runs on the VM yet. To go live:
1. Point a free **DuckDNS** subdomain at the VM's external IP; open GCP firewall
   `tcp:80,443` on tag `hermes-bot-vm`.
2. `apt install caddy`; put `SHARED_SECRET` + `GEMINI_KEY` in `/etc/caddy/coach.env`
   (chmod 600) and load it via a `systemctl edit caddy` drop-in
   (`EnvironmentFile=`).
3. Caddyfile: 403 unless `Authorization: Bearer {$SHARED_SECRET}`, else
   `reverse_proxy https://generativelanguage.googleapis.com` with
   `header_up Host …`, `header_up x-goog-api-key {$GEMINI_KEY}`,
   `header_up -Authorization`.
4. Fill `coachProxyUrl` / `coachProxySecret` in root `gradle.properties`, rebuild.
5. Only the same aggregate `usageSummary` the coach already builds crosses the wire.

### 2. Telegram accountability bridge — most differentiating
An external witness for the one loophole the app can't close alone: the user
disabling protection. Uses a **new** bot (Hermes token is retired).

Plan when we build it:
1. Create a fresh bot via @BotFather; small service on the VM receives events from
   the app (`POST /event`, shared secret) and forwards to Telegram.
2. App side: fire-and-forget reports for — protection turned off, Strict-Mode
   settings-guard bounce, daily summary (blocks, screen time, goals hit).
3. Optional: accountability partner mode — alerts go to a chosen chat, not just the
   owner.
4. Everything degrades silently when offline; no blocking behavior ever depends on
   the server.

### 3. Config/blocklist endpoint — low priority
Fresher adult-domain/social-domain lists without an app release. **Prefer GitHub raw
files instead** (free, no server) if this is ever wanted.

### 4. Settings/stats backup — low priority
Cross-device backup of rules/schedules/stats. **Prefer a local export/import file
feature** unless multi-device sync becomes a real need.

### Explicit non-uses
Updates, APK hosting, CI, and release publishing all stay on GitHub (already built,
free, reliable). The VM must never become a second delivery pipeline.

## Principles for anything built on it

- The app must work fully with the VM offline — server features are additive only.
- Minimal data over the wire; nothing sensitive stored on the VM.
- One service per feature, systemd-managed, so a future session can reason about it.
