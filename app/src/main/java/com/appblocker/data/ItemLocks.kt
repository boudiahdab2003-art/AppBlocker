package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **Strict Mode for one thing at a time** (invariant 95). His ask on 25 Oct 2026: "I want this
 * website — just this website — blocked 50 days strict mode, but I am able to make other changes
 * more flexible." Strict locks every weakening at once, so a long lock on one site meant a month of
 * nothing else being editable.
 *
 * A lock names one blocked app (by package) or one blocked word (a website is a word, see
 * `WebContentFilter`) and a deadline. Until the deadline:
 *  - **it cannot be removed.** The rule lives on the writes, like every Strict rule
 *    (`WebFilterViewModel.setKeywords`, `AppListViewModel.commitQuickBlock`), not on the buttons —
 *    the editors stage their lists, and a stale staging would otherwise delete it silently;
 *  - **a locked app is blocked whatever else is going on** — Quick Block paused, between Pomodoro
 *    rounds, or in Allowlist mode. A lock that a pause could lift would be a pause with extra steps;
 *  - **it can be extended, never shortened.** [lock] keeps whichever deadline is later.
 *
 * The deadline is kept the way Strict keeps its own ([SessionClock]): monotonic within a boot,
 * wall clock after a restart, an impossible clock rejected. So it is exactly as hard to get round
 * as a Strict session — no harder, and the same known gap after a restart.
 *
 * Stored in prefs, one line per lock, so the watcher can read it synchronously on every decision
 * without touching Room (and no migration). The parsed list is cached in this process, which the
 * UI and the watcher share, and refreshed on every write.
 */
object ItemLocks {

    /** What a lock is on. Our own literals; they go into the stored line as-is. */
    object Kind {
        const val APP = "app"
        const val WORD = "word"
        val ALL = setOf(APP, WORD)
    }

    /** The choices the lock dialog offers, in days. No keyboard, so no dialog-inset trap. */
    val DAY_CHOICES = listOf(1, 7, 14, 30, 50, 90, 180, 365)

    private const val DAY_MS = 24 * 60 * 60_000L
    private const val PREFS = "item_locks"
    private const val KEY = "locks"

    /** One lock. Times as [SessionClock] wants them. */
    data class Lock(
        val kind: String,
        val key: String,
        val rtStart: Long,
        val rtEnd: Long,
        val wallStart: Long,
        val wallEnd: Long,
        val bootCount: Int,
    )

    // ---- pure -----------------------------------------------------------------------------

    /** Time left on [lock], at the given instants. 0 means it has ended. */
    internal fun remainingAt(lock: Lock, bootNow: Int, nowRt: Long, nowWall: Long): Long =
        SessionClock.remainingAt(
            lock.rtStart, lock.rtEnd, lock.wallStart, lock.wallEnd, lock.bootCount, bootNow, nowRt, nowWall,
        )

    /**
     * What a write may actually store: everything asked for, plus every locked item that is there
     * now. Taking a locked item off is refused; adding anything always goes through.
     */
    fun keepLocked(current: Set<String>, target: Set<String>, locked: Set<String>): Set<String> =
        target + (current intersect locked)

    /**
     * The lock list after locking [key] for [durationMs] from now: a new lock, or the old one if
     * it already runs longer — **a lock can be lengthened, never shortened**. Ended locks are
     * dropped on the way.
     */
    internal fun withLock(
        locks: List<Lock>,
        kind: String,
        key: String,
        durationMs: Long,
        bootNow: Int,
        nowRt: Long,
        nowWall: Long,
    ): List<Lock> {
        val live = locks.filter { remainingAt(it, bootNow, nowRt, nowWall) > 0L }
        val existing = live.firstOrNull { it.kind == kind && it.key == key }
        if (existing != null && remainingAt(existing, bootNow, nowRt, nowWall) >= durationMs) return live
        val fresh = Lock(kind, key, nowRt, nowRt + durationMs, nowWall, nowWall + durationMs, bootNow)
        return live.filterNot { it.kind == kind && it.key == key } + fresh
    }

    internal fun encode(locks: List<Lock>): String = locks.joinToString("\n") { l ->
        listOf(
            l.kind, java.net.URLEncoder.encode(l.key, "UTF-8"),
            l.rtStart, l.rtEnd, l.wallStart, l.wallEnd, l.bootCount,
        ).joinToString("|")
    }

    internal fun decode(raw: String): List<Lock> = raw.lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 7 || p[0] !in Kind.ALL) return@mapNotNull null
        Lock(
            kind = p[0],
            key = runCatching { java.net.URLDecoder.decode(p[1], "UTF-8") }.getOrNull() ?: return@mapNotNull null,
            rtStart = p[2].toLongOrNull() ?: return@mapNotNull null,
            rtEnd = p[3].toLongOrNull() ?: return@mapNotNull null,
            wallStart = p[4].toLongOrNull() ?: return@mapNotNull null,
            wallEnd = p[5].toLongOrNull() ?: return@mapNotNull null,
            bootCount = p[6].toIntOrNull() ?: return@mapNotNull null,
        )
    }

    // ---- on the phone ---------------------------------------------------------------------

    @Volatile private var cache: List<Lock>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun all(context: Context): List<Lock> = cache ?: runCatching {
        decode(prefs(context).getString(KEY, "").orEmpty())
    }.getOrDefault(emptyList()).also { cache = it }

    /** Locks [key] for [days] days, or lengthens the lock it already has. */
    fun lock(context: Context, kind: String, key: String, days: Int) {
        if (kind !in Kind.ALL || key.isBlank() || days <= 0) return
        synchronized(this) {
            val next = withLock(
                all(context), kind, key, days * DAY_MS,
                DeviceBoot.count(context), SystemClock.elapsedRealtime(), System.currentTimeMillis(),
            )
            // commit(): a lock that did not land would be a promise the app did not keep.
            prefs(context).edit().putString(KEY, encode(next)).commit()
            cache = next
        }
    }

    /** Every live lock of [kind], key to millis left. */
    fun remaining(context: Context, kind: String): Map<String, Long> = runCatching {
        val boot = DeviceBoot.count(context)
        val rt = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        all(context).filter { it.kind == kind }
            .associate { it.key to remainingAt(it, boot, rt, wall) }
            .filterValues { it > 0L }
    }.getOrDefault(emptyMap())

    /** The keys of [kind] locked right now. */
    fun locked(context: Context, kind: String): Set<String> = remaining(context, kind).keys
}
