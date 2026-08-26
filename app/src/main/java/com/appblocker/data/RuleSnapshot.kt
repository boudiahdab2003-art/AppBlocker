package com.appblocker.data

/**
 * What to enforce in the seconds before Room has answered.
 *
 * **The watcher used to enforce nothing at all in that window, and it was invisible.** The rules
 * arrive as a Room Flow collected in `onServiceConnected`, so until its first emission the
 * watcher's `rules` map is empty — and an empty map is not "nothing is blocked", it is "we have
 * not been told yet". `decideBlock` read it as the former: every app came back unblocked, and
 * `handleAppBlock` took the "not blocked, so take the cover down" branch.
 *
 * Any service rebind hits this: boot, an update, a revive after being killed — and, the one the
 * owner reported on 25 Aug 2026, **switching Xiaomi Second Space**, which kills and rebinds the
 * service every time. His words were "it was slow after switching between spaces". It was not
 * slow; blocking was off.
 *
 * This is `docs/BLOCKING_INVARIANTS.md` invariant 11 — *an empty or failed answer is not data* —
 * applied to the rules themselves, which is the one place it had never been applied. The fix has
 * the same shape as the others: keep the previous value rather than adopting the empty one. The
 * previous value is a plain set of package names written on every emission, small enough to read
 * synchronously on the connect path before a single event can arrive.
 *
 * **The asymmetry is deliberate.** A stale snapshot can only over-block, and only until Room
 * answers milliseconds later — an app unticked a moment ago stays covered for one beat. The
 * failure it replaces unblocked *everything*, for as long as the database took, with no sign on
 * screen that anything was wrong. Refusing to weaken is always the safe direction here.
 */
internal object RuleSnapshot {

    /** The packages worth remembering: the blocked ones. Allowlist mode needs no snapshot — an
     *  empty map already blocks everything there, which fails closed on its own. */
    fun encode(rules: Collection<AppRule>): Set<String> =
        rules.filter { it.isBlocked }.map { it.packageName }.toSet()

    /**
     * The rule to enforce for [pkg] right now.
     *
     * Once [loaded] is true the live map is the only truth and the snapshot is never consulted
     * again — otherwise unblocking an app would not take effect until the next restart.
     */
    fun ruleFor(
        pkg: String,
        live: Map<String, AppRule>,
        loaded: Boolean,
        snapshot: Set<String>,
    ): AppRule? {
        if (loaded) return live[pkg]
        return live[pkg] ?: if (pkg in snapshot) blockedFromSnapshot(pkg) else null
    }

    /** A snapshot entry says one thing — "this was blocked" — so it restores exactly that and
     *  nothing else. HARD is the mode that needs no other column to be meaningful: SCHEDULE and
     *  LIMIT both depend on values the snapshot does not carry, and guessing one would either
     *  invent a block the user never set or drop the one they did. */
    private fun blockedFromSnapshot(pkg: String) =
        AppRule(packageName = pkg, appLabel = "", isBlocked = true, mode = BlockMode.HARD)
}
