package com.appblocker.data

/**
 * **Telling him, on the phone, that blocking was off for a long time** (invariant 103 — his ask on
 * 8 Nov 2026: *"detect it in report and show me notification at least"*).
 *
 * On 7 Nov 2026 his battery ran down to 1 %, HyperOS force-stopped AppBlocker (`LockScreenClean`)
 * and the phone switched itself off. A force stop also removes the Accessibility entry, and a
 * force-stopped app runs NOTHING — no alarm, no worker, no boot broadcast — so after he charged the
 * phone AppBlocker stayed off for 27 hours and YouTube went through the whole of 8 Nov. Nothing on
 * the phone said so, and the stoppage tracker read 0 because the only report holding the line was
 * the weekly one.
 *
 * The app cannot prevent that: Android keeps a force-stopped app down until something starts it.
 * What it can do is say so **the moment it is back**: a notification on the phone, and the report
 * sent now instead of with the weekly summary.
 *
 * ⚠️ Short gaps stay silent. The silent repair's 2–3 minute kills are invisible by his choice
 * (feedback "silent repairs"); only a gap of [TELL_AFTER_MS] or more is worth interrupting him for.
 */
object OffNotice {

    /** Half an hour: far above a repaired kill (2–6 min), far below the gaps that cost him days. */
    const val TELL_AFTER_MS = 30L * 60_000L

    /**
     * The length to tell him: the wall-clock span from the last sign of life to the close when it
     * was recorded (that is the time he actually went unprotected, across a restart too), else the
     * monotonic duration.
     */
    fun shownMs(durationMs: Long, spanMs: Long): Long = if (spanMs >= 0L) spanMs else durationMs

    fun worthTelling(durationMs: Long, spanMs: Long): Boolean =
        shownMs(durationMs, spanMs) >= TELL_AFTER_MS

    /** `45 min`, `3 h`, `27 h 18 min` — plain, for a notification he reads at a glance. */
    fun length(ms: Long): String {
        val m = (ms / 60_000L).coerceAtLeast(0L)
        val h = m / 60L
        val r = m % 60L
        return when {
            h == 0L -> "$m min"
            r == 0L -> "$h h"
            else -> "$h h $r min"
        }
    }
}
