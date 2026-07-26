# The spare GCP VM (future server-side features)

The owner has a small always-on server left over from a previous project. It is
**available for AppBlocker** whenever a server-side feature becomes worth building.
It currently runs **Caddy**, serving the AI-coach proxy (#1, live) and — once the owner
pastes the route — the bug-report endpoint (#2).

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

**What's left (the VM):** nothing — this is done, and the steps below are kept only as
the record of how it was set up. To go live *was*:
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

### 2. Bug reporting → private GitHub issues — **app side built, VM side pending**

Why it exists: the watcher already records every error it swallows
(`ServiceHealth.recordError`) and the Profile screen already says "tap to clear once
you've reported it" — with nowhere to report to. And the failures worth catching are
the ones the owner *cannot* see; `docs/BLOCKING_INVARIANTS.md` opens by saying
under-blocking is invisible. Those reports have to send themselves.

Shape: app `POST /report` → Caddy checks a shared secret → Caddy injects a GitHub
token → GitHub Issues API on a **private** reports repo. **No new service on the VM** —
it is the coach's trick pointed at a different API.

**Privacy.** Reports are built from an allow-list in `data/BugReport.kt` (version,
flavour, SDK, device, `where` tag, exception class, our own stack frames, plus any note
the owner typed). `Throwable.message` is **dropped entirely**, because that is where a
blocked keyword gets quoted back. `BugReportTest` holds this; do not weaken it.

**Security — deliberately unlike the coach.** `coachProxySecret` lives in the committed
`gradle.properties` and this repo is **public**, so that secret is readable by anyone.
That was an accepted trade for a Gemini key capped by its own quota. A token that writes
to the owner's issue tracker is not the same bet, so:
- `REPORT_URL` / `REPORT_SECRET` come from **GitHub Actions repository secrets**, read
  via `System.getenv` in `app/build.gradle.kts`; unset ⇒ reporting silently off.
- The GitHub token must be **fine-grained**, scoped to the reports repo only, with
  **Issues: write and nothing else**. Worst case for a leak is issue spam in a private
  repo — not code access, not read access.
- Be clear-eyed: this raises the bar from "read a public file" to "unzip the public
  APK". The token's narrow scope is what actually bounds the damage, not the secrecy.

**To go live (owner, in GCP console SSH — paste in order):**
1. Create a **private** repo, e.g. `appblocker-reports`, and a fine-grained PAT scoped
   to it with Issues: write.
2. `sudo nano /etc/caddy/coach.env` — add `REPORT_SECRET=<make one up>` and
   `GITHUB_TOKEN=<the PAT>`.
3. `sudo nano /etc/caddy/Caddyfile` — inside the existing site block, **above** the
   coach route:
   ```
   @report path /report
   handle @report {
       @badreport not header Authorization "Bearer {$REPORT_SECRET}"
       respond @badreport 403
       rewrite * /repos/<owner>/appblocker-reports/issues
       reverse_proxy https://api.github.com {
           header_up Host api.github.com
           header_up Authorization "Bearer {$GITHUB_TOKEN}"
           header_up Accept "application/vnd.github+json"
       }
   }
   ```
4. `sudo systemctl restart caddy` (restart, not reload — the env file changed).
5. Test before shipping any app build:
   `curl -X POST https://appblocker-coach.duckdns.org/report -H "Authorization: Bearer <secret>" -H "Content-Type: application/json" -d '{"title":"test","body":"hello"}'`
   → an issue appears. Wrong secret → **403**. Then confirm the coach still works.
6. Add `REPORT_URL` (`https://appblocker-coach.duckdns.org/report`) and `REPORT_SECRET`
   as repository secrets, then publish a release.

Rotating: edit `/etc/caddy/coach.env` + the Actions secrets, restart Caddy, republish.

### 3. Telegram accountability bridge — most differentiating
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

### 4. Config/blocklist endpoint — low priority
Fresher adult-domain/social-domain lists without an app release. **Prefer GitHub raw
files instead** (free, no server) if this is ever wanted.

### 5. Settings/stats backup — low priority
Cross-device backup of rules/schedules/stats. **Prefer a local export/import file
feature** unless multi-device sync becomes a real need.

### Explicit non-uses
Updates, APK hosting, CI, and release publishing all stay on GitHub (already built,
free, reliable). The VM must never become a second delivery pipeline.

## Principles for anything built on it

- The app must work fully with the VM offline — server features are additive only.
- Minimal data over the wire; nothing sensitive stored on the VM.
- One service per feature, systemd-managed, so a future session can reason about it.
