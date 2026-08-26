package com.appblocker.data

/**
 * "You are hunting right now, so every browser closes for an hour."
 *
 * Asked for by the owner on 26 Aug 2026, the day after a relapse, in these words: *"after some
 * violations maybe 3 that are different and connected to sexual terms … block every browser that I
 * have for 1 hr because I'm in a danger zone"*. His four choices are settled and are not to be
 * re-litigated — they are the constants below plus two rules stated here:
 *
 *  - **Three DIFFERENT words**, within **30 minutes**. He was offered a looser window and chose the
 *    tightest one: only a real hunting session should trip this. *The same word three times must
 *    not trip it* — that is one person being stubborn about one thing, and three different words is
 *    the thing that actually looks like searching. `theSameWordThreeTimesIsNotAHunt` pins it.
 *  - **One hour, always.** No doubling on repeats; he chose predictable over harsh, on the
 *    reasoning that a cost you can predict is one you can trust.
 *
 * **Browsers only** (decided in `decideBlock`, not here) — *"just the browsers not the apps not the
 * maps not the bank app"*. That is also what makes having **no escape hatch** safe: an hour without
 * a browser is survivable in a way that an hour without maps or a banking app is not.
 *
 * ## Why the strikes are stored by hash
 *
 * The key is [key], never the word. Two reasons, and the second is the better one:
 *
 *  1. [GuardedDeadline.encode] joins fields with `|`, so a key containing one would corrupt the
 *     record — the same trap that stopped the keyword lockout persisting its word at all.
 *  2. **Nothing on this phone should hold a list of what he searched for.** Counting to three needs
 *     identity, not text, and a hash gives identity. So the file that survives on disk says "three
 *     different things" and cannot say which — which is the same promise Recovery makes, applied
 *     somewhere it would have been easy not to bother.
 *
 * ## Why [GuardedDeadline] rather than a timestamp
 *
 * Both the strike window and the hour are deadlines the user should not be able to skip, and that
 * type's own KDoc says anything of that shape belongs there "not in a third copy" — the wall clock
 * was a working bypass for the keyword lockout and the adult-pack delay before it. A strike is
 * simply a deadline that expires when it leaves the window.
 */
internal object DangerZone {

    /** How long a strike counts for. His choice: the tightest window offered. */
    const val STRIKE_WINDOW_MS = 30 * 60_000L

    /** Different words inside the window that arm the zone. */
    const val STRIKES_TO_TRIP = 3

    /** How long every browser stays shut. Flat — never doubled, by his choice. */
    const val LOCKDOWN_MS = 60 * 60_000L

    /**
     * The storage key for a word: stable across restarts, and not the word.
     *
     * `String.hashCode` is specified by the language rather than left to the runtime, so it is
     * the same number on every launch and every device — which is all this needs. Rendered
     * unsigned in hex so it can never contain `-`, `|` or `;` and cannot corrupt the record it
     * is stored in.
     */
    fun key(word: String): String = word.trim().lowercase().hashCode().toUInt().toString(16)

    /** Strikes still inside the window, dropping the rest. */
    fun prunedAt(
        strikes: Map<String, GuardedDeadline>,
        bootCount: Int,
        nowRt: Long,
        nowWall: Long,
    ): Map<String, GuardedDeadline> =
        strikes.filterValues { it.remainingAt(bootCount, nowRt, nowWall) > 0L }

    /** How many different words are on the board. The map is keyed by word, so a word repeated
     *  refreshes its own strike instead of adding another — the distinctness rule *is* the
     *  storage choice, which is why the test for it belongs on this function. */
    fun liveStrikesAt(
        strikes: Map<String, GuardedDeadline>,
        bootCount: Int,
        nowRt: Long,
        nowWall: Long,
    ): Int = prunedAt(strikes, bootCount, nowRt, nowWall).size

    /** Whether the board now arms the zone. */
    fun tripsAt(
        strikes: Map<String, GuardedDeadline>,
        bootCount: Int,
        nowRt: Long,
        nowWall: Long,
    ): Boolean = liveStrikesAt(strikes, bootCount, nowRt, nowWall) >= STRIKES_TO_TRIP

    /**
     * What the block screen says. His choice, from three options: **name it honestly**, with the
     * countdown — over a neutral "browsers are closed" and over no number at all.
     *
     * It says what he did and what it costs, and stops. No total, no streak, no history of how
     * often this has happened: [com.appblocker.data.CleanStreak]'s rule that the worst moment is
     * not the moment for a scoreboard applies here more than anywhere, because unlike that screen
     * this one appears without being asked for.
     */
    fun message(remainingMs: Long): String {
        val mins = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        return "You hit three of these in a row. Browsers are closed for the next $mins " +
            if (mins == 1L) "minute." else "minutes."
    }
}
