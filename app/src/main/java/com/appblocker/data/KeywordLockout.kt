package com.appblocker.data

/**
 * One app's keyword lockout: it stays fully blocked for a while after a blocked word was caught
 * in it.
 *
 * Anchored exactly like a Strict/Timer session (see [SessionClock]) rather than as a plain
 * wall-clock deadline. It used to be `pkg -> expiryEpochMillis` compared against
 * `System.currentTimeMillis()`, which meant winding the device clock forward lifted the lockout
 * — the very bypass every other timer in the app was moved off the wall clock to prevent. The
 * lockout is the consequence for hitting a blocked word, so it is one of the last things that
 * should be cheap to escape.
 *
 * [word] is in-memory only, so it can be shown again on re-entry; after a restart the lockout
 * survives but falls back to a generic message. It is deliberately not persisted: a user keyword
 * can contain anything, including this format's separator.
 */
internal data class KeywordLockout(
    val realtimeStart: Long,
    val realtimeEnd: Long,
    val wallStart: Long,
    val wallEnd: Long,
    val bootCount: Int,
    val word: String? = null,
) {
    /** Millis left, 0 when expired — monotonic within a boot, guarded wall clock after one. */
    fun remaining(currentBootCount: Int): Long = SessionClock.remaining(
        realtimeStart, realtimeEnd, wallStart, wallEnd, bootCount, currentBootCount,
    )

    /** `pkg|rtStart|rtEnd|wallStart|wallEnd|boot`. Package names cannot contain '|'. */
    fun encode(pkg: String): String =
        "$pkg|$realtimeStart|$realtimeEnd|$wallStart|$wallEnd|$bootCount"

    companion object {
        /**
         * Parses one stored entry, or null if it is unreadable.
         *
         * Also accepts the old two-field `pkg|expiryEpochMillis` form so an app update doesn't
         * silently drop lockouts that are still running. Those keep using the wall clock (there
         * is no better information in them) until they expire; new ones are clock-proof.
         */
        fun decode(entry: String): Pair<String, KeywordLockout>? {
            val parts = entry.split('|')
            val pkg = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            return when (parts.size) {
                6 -> {
                    val n = parts.drop(1).map { it.toLongOrNull() ?: return null }
                    pkg to KeywordLockout(n[0], n[1], n[2], n[3], n[4].toInt())
                }
                // Legacy: expiry only. bootCount -1 forces the wall-clock path in SessionClock.
                2 -> {
                    val until = parts[1].toLongOrNull() ?: return null
                    pkg to KeywordLockout(0L, 0L, 0L, until, -1)
                }
                else -> null
            }
        }
    }
}
