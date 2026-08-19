package com.appblocker.data

import com.appblocker.service.WebContentFilter

/**
 * What may still be edited while a Strict session is running.
 *
 * Strict Mode locks *weakening* only (docs/BLOCKING_INVARIANTS.md, invariant 16). Everything here
 * is pure so it can be tested, and — this is the point — every rule is applied on the **write**
 * rather than on the button that starts it. A disabled button is a suggestion: `BlockEditorScreen`
 * stages its lists and commits them on Save, so a stale staging can delete a word or clear an app's
 * block mid-session without any locked button ever being tapped.
 */

/** A website word blocked because its app is, and the app that contributes it. */
data class SiteWord(val word: String, val appLabel: String)

object StrictEdits {

    /**
     * The website words currently blocked *because their app is* — `SOCIAL_DOMAINS` for every
     * blocked app. This is the one definition; the watcher calls it too
     * (`BlockerAccessibilityService.autoSocialKeywords`), because a second copy of this answer is
     * exactly the shape that keeps producing bugs here.
     *
     * Empty in Allowlist mode (auto-blocking a blocked app's website is a Blocklist concept) and
     * empty when nothing is enforcing, so a paused Quick Block relieves the web block as well.
     */
    fun liveSiteWords(
        rules: Collection<AppRule>,
        allowlistMode: Boolean,
        enforcing: Boolean,
    ): List<SiteWord> {
        if (allowlistMode || !enforcing) return emptyList()
        return rules.asSequence()
            .filter { it.isBlocked && it.mode != BlockMode.LIMIT }
            .flatMap { rule ->
                (SOCIAL_DOMAINS[rule.packageName] ?: emptyList())
                    .asSequence()
                    .map { SiteWord(it, rule.appLabel) }
            }
            .toList()
    }

    /**
     * The app whose website block already covers [word], or null if nothing does.
     *
     * The direction matters: the **site word must match inside the user's word**, because that is
     * what proves the site block is the broader rule. `instagram` (contributed by a blocked
     * Instagram) covers the word `instagram.com` and `www.instagram.com/reels`; it does not cover
     * `insta`, which would match addresses `instagram` never would.
     *
     * The implication is exact in both directions, which is why this is a rule and not a guess.
     * [WebContentFilter.containsWord]'s boundaries are non-alphanumeric, so `.` and `/` count:
     *  - *Sound* — an occurrence that is whole-word inside [word] is still whole-word inside any
     *    address that matched [word], because the characters on either side of it are either
     *    interior characters of [word] (already boundaries, by the inner match) or the ends of
     *    [word] (already boundaries in that address, by the outer match). So every address the
     *    word blocked, the site word blocks.
     *  - *Complete* — if the site word does not sit whole-word inside [word], then [word] itself
     *    is an address the word blocks and the site word does not. Nothing safe is refused.
     *
     * Normalisation is `trim().lowercase()` and nothing more, because that is exactly what
     * `WebContentFilter.checkUrl` applies to each keyword. Deliberately no `normalizeArabic`:
     * `checkUrl` does not fold addresses either, so folding here would claim a coverage the
     * matcher would not honour. Whoever extends this: apply the same normalisation as the layer
     * being reasoned about, no more and no less.
     */
    fun coveredBy(word: String, siteWords: List<SiteWord>): String? = coveringSiteWord(word, siteWords)?.appLabel

    /** As [coveredBy], but the whole entry — the covering word and the app it came from. */
    fun coveringSiteWord(word: String, siteWords: List<SiteWord>): SiteWord? {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return null
        return siteWords.firstOrNull { site ->
            val s = site.word.trim().lowercase()
            s.isNotEmpty() && WebContentFilter.containsWord(w, s)
        }
    }

    /**
     * Which of the words in [current] but not in [target] may actually be deleted.
     *
     * Outside a session, all of them. During one, only the words some blocked app's website rule
     * already covers — deleting those takes nothing away, because the coverage that permits it is
     * itself frozen for the rest of the session: an app can't be un-blocked mid-Strict, the
     * Blocklist/Allowlist chip can't be flipped, and Strict keeps Quick Block enforcing whatever
     * else is paused. Everything else stays.
     */
    fun allowedDeletions(
        current: Set<String>,
        target: Set<String>,
        strict: Boolean,
        siteWords: List<SiteWord>,
    ): Set<String> {
        val removed = current - target
        if (!strict) return removed
        return removed.filterTo(mutableSetOf()) { coveringSiteWord(it, siteWords) != null }
    }

    /**
     * The Quick Block selections that may actually be committed, given what is blocked/allowed
     * right now. Tightening always goes through; during a session the weakening direction of
     * whichever list is enforcing is put back:
     *
     *  - Blocklist mode — an app blocked right now stays blocked.
     *  - Allowlist mode — an app not allowed right now stays not allowed.
     *
     * The other list isn't enforcing anything, so it is committed as staged. This exists because
     * the editor's selection stops re-syncing once it has been edited, so an app auto-blocked by
     * `NewAppWatcher` after the editor was opened reads to Save as "the user un-ticked this".
     */
    /**
     * How long the Strict session in [state] has left, 0 when there is none. Wraps
     * [SessionClock.remaining] with the null row so both view models ask the question the same
     * way — a third reckoning of "is Strict on" is how the clock bypasses of v1.99 happened.
     */
    fun strictRemaining(state: FocusState?, bootCount: Int): Long {
        if (state == null) return 0L
        return SessionClock.remaining(
            state.realtimeStartMillis, state.realtimeEndMillis,
            state.startTimeMillis, state.endTimeMillis,
            state.bootCount, bootCount,
        )
    }

    fun allowedSelections(
        currentBlocked: Set<String>,
        currentAllowed: Set<String>,
        targetBlocked: Set<String>,
        targetAllowed: Set<String>,
        strict: Boolean,
        allowlistMode: Boolean,
    ): Pair<Set<String>, Set<String>> {
        if (!strict) return targetBlocked to targetAllowed
        return if (allowlistMode) targetBlocked to (targetAllowed intersect currentAllowed)
        else (targetBlocked + currentBlocked) to targetAllowed
    }
}
