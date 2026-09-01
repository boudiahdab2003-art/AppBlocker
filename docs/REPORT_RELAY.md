# Moving the reporting relay off dynamic DNS

**Read `tools/report-worker.js` first — it explains why this exists.** Short version: AppBlocker's
own family DNS filter blocks dynamic-DNS domains as a category, `appblocker-coach.duckdns.org` was
one, and so the owner's phone could not resolve the address its own reports were sent to. Six days
of reports queued undelivered and the AI Coach — same host — was dead too.

⚠️ **Rule that comes out of it: the endpoint may never live on a dynamic-DNS domain again.**
Before moving this anywhere, check the new host resolves through the filter he actually uses:

```
nslookup <new-host> 185.228.168.168     # CleanBrowsing family
```

Verified resolvable on 1 Sep 2026: `workers.dev`, `vercel.app`, `fly.dev`, `netlify.app`,
`pages.dev`, `onrender.com`. Verified blocked on **all three** CleanBrowsing tiers: `duckdns.org`.

## Setup (owner, ~10 minutes)

1. Sign in at **dash.cloudflare.com** (free account is enough) → **Workers & Pages** → **Create** →
   **Create Worker**. Name it something plain like `appblocker-reports`; the address becomes
   `appblocker-reports.<your-subdomain>.workers.dev`.
2. **Edit code** → delete the sample → paste all of `tools/report-worker.js` → **Deploy**.
3. **Settings ▸ Variables and Secrets**, add four **secrets** (not plain variables):
   - `REPORT_SECRET` — the same shared password the app uses
   - `GITHUB_TOKEN` — a fine-grained PAT, **Issues: Read and write**, on the reports repo **only**
   - `COACH_SECRET` — the coach password from `gradle.properties`
   - `GEMINI_KEY` — the Gemini API key
   and one plain **variable**:
   - `REPORTS_REPO` = `boudiahdab2003-art/appblocker-reports`
4. Test it before shipping anything:
   ```
   curl -s -o /dev/null -w "%{http_code}\n" -X POST https://<your-worker-url>/report \
     -H "Authorization: Bearer <REPORT_SECRET>" -H "Content-Type: application/json" \
     -d '{"title":"relay test","body":"x"}'
   ```
   **201** = working. **403** = the secret does not match. Delete the test issue afterwards.
5. Point the app at it and **republish** — both values are compiled in at build time, so nothing
   changes until a release ships:
   - GitHub → repo **Settings ▸ Secrets and variables ▸ Actions** → set `REPORT_URL` to
     `https://<your-worker-url>/report`
   - `gradle.properties` → `coachProxyUrl=https://<your-worker-url>`
6. After the release installs, confirm on the phone **with the DNS filter ON**: open the app, check
   a `profile OK` issue arrives, and ask the coach something. That combination — filter on, report
   through — is the exact condition that failed silently for six days and that nothing tested.

## What this replaces

The Caddy `/report` and coach routes on the GCP VM (`docs/SERVER.md`). Leave the VM running until
the Worker is proven; after that it is only needed for whatever else is on it.

## If reports stop again

Don't guess. The phone now says which of three things happened, in Profile ▸ *What the blocker
sees*: **never tried**, **was refused** (the build's password does not match the relay's — needs a
republish), or **cannot reach the server** (name resolution — suspect the DNS filter first). That
screen is the first thing to read, before touching a token or a password.
