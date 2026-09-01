/**
 * The reporting relay, as a free Cloudflare Worker.
 *
 * ## Why this exists
 *
 * The relay used to be Caddy on a GCP VM at `appblocker-coach.duckdns.org`. On 1 Sep 2026 that
 * turned out to be unusable: AppBlocker's own family DNS filter blocks **dynamic-DNS domains as a
 * category** — correct behaviour for a content filter, since they are a standard way to evade one —
 * and `duckdns.org` is exactly that. The owner's phone could not resolve the address its own
 * reports were sent to, so every send died at name resolution for six days, and the AI Coach,
 * which shares the host, died with it. Blocked on all three CleanBrowsing tiers, so changing tier
 * was not a way out.
 *
 * ⚠️ **The endpoint may never live on a dynamic-DNS domain again.** `workers.dev` was checked
 * against the family resolver (185.228.168.168) and resolves; so do `vercel.app`, `fly.dev`,
 * `netlify.app`, `pages.dev`. Before moving this anywhere, resolve the new host through that
 * server first.
 *
 * ## What it does
 *
 * Two routes, the same two the Caddyfile in `docs/SERVER.md` served:
 *
 *   POST /report          -> creates an issue in the private reports repo
 *   POST /v1beta/models/… -> forwards to Gemini for the in-app coach
 *
 * Both check a shared bearer secret first, exactly as Caddy did. The GitHub token never leaves
 * here, which is the whole reason a relay exists: the app is a public repo, so a token compiled
 * into it would be public too.
 *
 * ## Secrets (set with `wrangler secret put NAME`, never in this file)
 *
 *   REPORT_SECRET   the shared password the app sends. Also set as the REPORT_SECRET Actions
 *                   secret, and the app must be REPUBLISHED — it is baked in at build time.
 *   GITHUB_TOKEN    fine-grained PAT, Issues:write, on the reports repo ONLY.
 *   COACH_SECRET    the coach's shared password (public by accepted choice — see docs/SERVER.md).
 *   GEMINI_KEY      the Gemini API key.
 *
 * ## Env vars (plain, in wrangler.toml)
 *
 *   REPORTS_REPO    e.g. "boudiahdab2003-art/appblocker-reports"
 */

const JSON_HEADERS = { "Content-Type": "application/json" };

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Only POST is ever used by the app. Anything else is somebody poking at it.
    if (request.method !== "POST") {
      return new Response("Not found", { status: 404 });
    }

    if (url.pathname === "/report") {
      return handleReport(request, env);
    }
    if (url.pathname.startsWith("/v1beta/")) {
      return handleCoach(request, env, url);
    }
    return new Response("Not found", { status: 404 });
  },
};

/** Constant-time-ish bearer check. Returns true when the header carries the expected secret. */
function authorised(request, expected) {
  if (!expected) return false;
  const header = request.headers.get("Authorization") || "";
  return header === `Bearer ${expected}`;
}

/**
 * Creates one issue from the app's `{title, body}` payload.
 *
 * The app treats any 2xx as delivered and keeps the report queued otherwise, so a wrong secret or
 * an expired token retries rather than silently discarding evidence — do not "helpfully" return
 * 200 on failure here.
 */
async function handleReport(request, env) {
  if (!authorised(request, env.REPORT_SECRET)) {
    return new Response("Forbidden", { status: 403 });
  }

  let payload;
  try {
    payload = await request.json();
  } catch {
    return new Response("Bad JSON", { status: 400 });
  }
  if (!payload || typeof payload.title !== "string") {
    return new Response("Bad payload", { status: 400 });
  }

  const upstream = await fetch(
    `https://api.github.com/repos/${env.REPORTS_REPO}/issues`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/vnd.github+json",
        // GitHub rejects requests without one.
        "User-Agent": "appblocker-report-relay",
        ...JSON_HEADERS,
      },
      body: JSON.stringify({
        title: payload.title,
        body: typeof payload.body === "string" ? payload.body : "",
      }),
    },
  );

  // Status only. GitHub's error bodies can echo what was submitted, and the app records whatever
  // comes back — the privacy contract in data/BugReport.kt governs stored text as much as sent.
  return new Response(upstream.ok ? "created" : "upstream refused", {
    status: upstream.status,
  });
}

/** Forwards a coach request to Gemini, injecting the key the app must not carry. */
async function handleCoach(request, env, url) {
  if (!authorised(request, env.COACH_SECRET)) {
    return new Response("Forbidden", { status: 403 });
  }

  const upstream = await fetch(
    `https://generativelanguage.googleapis.com${url.pathname}${url.search}`,
    {
      method: "POST",
      headers: { "x-goog-api-key": env.GEMINI_KEY, ...JSON_HEADERS },
      body: await request.text(),
    },
  );

  // The coach's reply is the product here, so it passes through as-is.
  return new Response(upstream.body, {
    status: upstream.status,
    headers: JSON_HEADERS,
  });
}
