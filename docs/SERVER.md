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

### 1. Gemini proxy for the AI Coach — highest value
Today the coach requires the user to paste a Gemini API key on-device
(`AiCoach.setApiKey`). A tiny proxy makes the coach work out of the box.

Plan when we build it:
1. Small HTTP service on the VM (single endpoint, e.g. `POST /coach`) holding the
   Gemini key server-side; a shared secret baked into the app gates access.
2. App side: `AiCoach` tries the proxy first, falls back to the on-device key, and the
   whole app keeps working if the VM is down (coach shows its existing
   Unavailable state).
3. HTTPS via a Caddy/nginx + Let's Encrypt on the VM (needs a domain or nip.io).
4. Send the minimum: the same `usageSummary` text the coach already builds — no raw
   per-app data beyond what the prompt needs.

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
