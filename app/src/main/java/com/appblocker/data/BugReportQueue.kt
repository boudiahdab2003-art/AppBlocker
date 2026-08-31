package com.appblocker.data

import android.content.Context
import org.json.JSONArray

/**
 * Reports waiting to be sent, and the memory of what has already gone.
 *
 * **Why a queue at all.** The moment worth reporting is the moment the app is in trouble, and
 * trouble correlates with a phone that is offline, asleep, or being killed by an OEM battery
 * manager. Sending straight from the error path would drop exactly the reports that matter and
 * keep the ones from a healthy phone. So errors are written to disk first and sent later, on the
 * next app open.
 *
 * **Why the memory.** A crash in a loop is one bug, and left alone would file one issue per
 * occurrence until the tracker is useless. [markSent] remembers the [BugReport.dedupeKey] so the
 * second copy is dropped rather than sent. That memory is also what makes it safe to re-queue on
 * failure: a report can be retried forever without ever arriving twice.
 *
 * Kept in its **own** preferences file rather than the app's — see [PREFS]. Everything here is
 * best-effort: a report is never worth an exception, and the whole feature must be droppable
 * without the blocking behaviour noticing (see docs/SERVER.md: the app works fully with the VM
 * offline).
 */
object BugReportQueue {
    /**
     * **The queue's own file, not the app's main preferences.**
     *
     * It used to share `appblocker_prefs` with every setting in the app, which was fine while a
     * report was a few hundred bytes. It is not fine now: a report carries the settings table, the
     * block log, the stoppage history and the health verdicts, and up to [MAX_PENDING] of them can
     * be waiting at once. Android reads a preferences file **whole, into memory, on first touch** —
     * so a full queue meant every launch parsing a couple of hundred kilobytes of queued JSON
     * before the app could read a single setting. And a full queue is not hypothetical: when the
     * delivery route is down, full is exactly what it becomes.
     *
     * The reports already waiting are evidence, so this does not start clean — see [migrate].
     */
    private const val PREFS = "appblocker_reports"

    /** Where the queue used to live. Only ever read, and only until [migrate] has emptied it. */
    private const val LEGACY_PREFS = "appblocker_prefs"
    private const val KEY_PENDING = "bugreport_pending"
    private const val KEY_SENT_KEYS = "bugreport_sent_keys"
    private const val KEY_SENT_DAY = "bugreport_sent_day"
    private const val KEY_SENT_TODAY = "bugreport_sent_today"

    /** Small on purpose: this is a diagnostic aid, not a black box recorder. Oldest is evicted. */
    private const val MAX_PENDING = 20

    /**
     * A hard stop on issues opened per day, whatever goes wrong. Dedup already collapses a
     * repeating crash, but dedup keys off a stack frame — a bug that reports from a *changing*
     * line would slip past it. This is the backstop that cannot be reasoned around, so a runaway
     * costs a wasted day of reporting rather than a thousand issues.
     */
    private const val MAX_PER_DAY = 12

    @Volatile private var migrated = false

    private fun prefs(context: Context): android.content.SharedPreferences {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!migrated) migrate(context, p)
        return p
    }

    /**
     * Carries a queue written by an older build across to [PREFS], once.
     *
     * **Both halves matter and for different reasons.** The pending reports are the evidence the
     * owner's phone has already gathered and could not deliver — dropping them on an update would
     * throw away exactly the reports that prove the route was broken. The *sent keys* matter just
     * as much in the other direction: they are what stops a report being filed twice, so losing
     * them would re-file the device profile for every build the phone has ever run, the moment it
     * next opens the app.
     *
     * Only ever copies into an empty destination, so a second run can never overwrite newer work,
     * and clears the old keys afterwards so the main preferences file actually gets smaller.
     * Wrapped, because a reporter is never allowed to be the thing that breaks the app: a failed
     * migration costs the backlog, not the launch.
     */
    private fun migrate(context: Context, into: android.content.SharedPreferences) {
        synchronized(this) {
            if (migrated) return
            migrated = true
            runCatching {
                val old = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                val hasLegacy = old.contains(KEY_PENDING) || old.contains(KEY_SENT_KEYS)
                if (!hasLegacy) return@runCatching
                if (!into.contains(KEY_PENDING) && !into.contains(KEY_SENT_KEYS)) {
                    into.edit()
                        .putString(KEY_PENDING, old.getString(KEY_PENDING, null))
                        .putStringSet(KEY_SENT_KEYS, old.getStringSet(KEY_SENT_KEYS, emptySet()))
                        .putInt(KEY_SENT_DAY, old.getInt(KEY_SENT_DAY, 0))
                        .putInt(KEY_SENT_TODAY, old.getInt(KEY_SENT_TODAY, 0))
                        .apply()
                }
                old.edit()
                    .remove(KEY_PENDING).remove(KEY_SENT_KEYS)
                    .remove(KEY_SENT_DAY).remove(KEY_SENT_TODAY)
                    .apply()
            }
        }
    }

    /**
     * Queues a report unless it duplicates one already sent, or today's cap is spent.
     * Returns false when it was dropped, for logging only — no caller should care.
     */
    fun enqueue(context: Context, report: BugReport): Boolean = runCatching {
        val p = prefs(context)
        val key = report.dedupeKey()
        if (key in sentKeys(context)) return false
        if (remainingToday(context) <= 0) return false

        val pending = pending(context).toMutableList()
        // Same bug already waiting to go — don't stack copies while offline either.
        if (pending.any { it.dedupeKey() == key }) return false
        pending += report
        while (pending.size > MAX_PENDING) pending.removeAt(0)
        p.edit().putString(KEY_PENDING, encode(pending)).apply()
        true
    }.getOrDefault(false)

    fun pending(context: Context): List<BugReport> = runCatching {
        val raw = prefs(context).getString(KEY_PENDING, null) ?: return emptyList()
        decode(raw)
    }.getOrDefault(emptyList())

    /** Drops one report from the queue and remembers it, so it is never sent twice. */
    fun markSent(context: Context, report: BugReport) = runCatching {
        val p = prefs(context)
        val remaining = pending(context).filterNot { it.dedupeKey() == report.dedupeKey() }
        val keys = sentKeys(context).toMutableSet()
        keys += report.dedupeKey()
        // Bounded: an app that runs for years must not grow this forever.
        val trimmed = if (keys.size > 200) keys.toList().takeLast(200).toSet() else keys
        p.edit()
            .putString(KEY_PENDING, encode(remaining))
            .putStringSet(KEY_SENT_KEYS, trimmed)
            .putInt(KEY_SENT_TODAY, spentToday(context) + 1)
            .putInt(KEY_SENT_DAY, todayStamp())
            .apply()
    }

    /** Leaves the report queued to try again later — the normal outcome when offline. */
    fun markFailed(context: Context, report: BugReport) = Unit

    private fun sentKeys(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SENT_KEYS, emptySet()).orEmpty()

    /** Resets with the calendar day, via the same day stamp the rest of the app counts by. */
    private fun spentToday(context: Context): Int {
        val p = prefs(context)
        return if (p.getInt(KEY_SENT_DAY, 0) == todayStamp()) p.getInt(KEY_SENT_TODAY, 0) else 0
    }

    fun remainingToday(context: Context): Int = MAX_PER_DAY - spentToday(context)

    // --- storage: a JSON array, since a report has free text in it and the app's usual
    // pipe-delimited format would be broken by the first note containing a '|'. ---

    /**
     * **Every field a report carries has to be written here.** This is not a summary of a report,
     * it is the report: `enqueue` writes to disk and `flush` sends what it reads back, so anything
     * missing from this pair is missing from the issue — silently, and only on a real phone, since
     * a report built in memory looks perfectly complete right up until it is stored.
     *
     * That is exactly what happened. `context` (the settings table) and `recentBlocks` (the log of
     * what the watcher covered) were added to [BugReport] and never added here, so every report
     * ever sent arrived without the two sections built specifically to diagnose the flashing. The
     * owner's first real report looked bare, and the per-field hardening added to
     * `BugReportSender.appContext` on the same day would not have changed it by one line: the map
     * was assembled correctly and then dropped on the way to disk.
     *
     * [ReportRoundTripTest] is the test that would have caught it, and now does.
     */
    internal fun encode(reports: List<BugReport>): String {
        val arr = JSONArray()
        reports.forEach { r ->
            arr.put(
                org.json.JSONObject()
                    .put("where", r.where)
                    .put("errorClass", r.errorClass ?: org.json.JSONObject.NULL)
                    .put("frames", JSONArray(r.frames))
                    .put("note", r.note ?: org.json.JSONObject.NULL)
                    .put("appVersion", r.appVersion)
                    .put("flavor", r.flavor)
                    .put("androidSdk", r.androidSdk)
                    .put("device", r.device)
                    .put("context", org.json.JSONObject(r.context.toMap()))
                    .put("recentBlocks", JSONArray(r.recentBlocks))
                    .put("recentOutages", JSONArray(r.recentOutages))
                    .put("healthFacts", JSONArray(r.healthFacts)),
            )
        }
        return arr.toString()
    }

    internal fun decode(raw: String): List<BugReport> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val frames = o.optJSONArray("frames")
                val blocks = o.optJSONArray("recentBlocks")
                val outages = o.optJSONArray("recentOutages")
                val health = o.optJSONArray("healthFacts")
                val ctx = o.optJSONObject("context")
                BugReport(
                    where = o.getString("where"),
                    errorClass = if (o.isNull("errorClass")) null else o.getString("errorClass"),
                    frames = (0 until (frames?.length() ?: 0)).map { frames!!.getString(it) },
                    note = if (o.isNull("note")) null else o.getString("note"),
                    appVersion = o.getString("appVersion"),
                    flavor = o.getString("flavor"),
                    androidSdk = o.getInt("androidSdk"),
                    device = o.getString("device"),
                    // Re-sanitised on the way back in, not trusted. The stored file is ours, but a
                    // report that sat on disk across an app update could have been written by a
                    // version whose allow-list was wider than this one's — and the allow-list has
                    // to be the *sending* version's, or an old file becomes a way past it.
                    context = BugReport.sanitizeContext(
                        (0 until (ctx?.length() ?: 0)).let {
                            ctx?.keys()?.asSequence()?.associateWith { k -> ctx.optString(k) }
                                ?: emptyMap()
                        },
                    ),
                    recentBlocks = (0 until (blocks?.length() ?: 0))
                        .map { blocks!!.getString(it) },
                    recentOutages = (0 until (outages?.length() ?: 0))
                        .map { outages!!.getString(it) },
                    healthFacts = (0 until (health?.length() ?: 0))
                        .map { health!!.getString(it) },
                )
            }.getOrNull()
        }
    }
}
