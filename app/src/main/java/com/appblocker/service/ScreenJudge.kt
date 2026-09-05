package com.appblocker.service

/**
 * **When is the screen in a state where "no event arrived" says anything about the watcher?**
 *
 * Two probes ask this — `canObserveEvents` (may the revive verdict be recorded?) and `probeScreen`
 * (does an unreadable window count as a failure?) — and they used to work it out separately, four
 * lines apart, from the same two system services. Both got the `PowerManager` half right and both
 * got the `KeyguardManager` half wrong, in opposite directions:
 *
 * ```
 * val pm = ... as? PowerManager ?: return false   // absent -> cannot tell -> abstain. Correct.
 * val km = ... as? KeyguardManager
 * return km?.isKeyguardLocked != true             // absent -> "not locked" -> JUDGE. Wrong.
 * ```
 *
 * A missing service is not an answer about the lock screen, and the KDoc four lines above already
 * said so — *"every can't-tell passes"*. It was applied to one null and not to the sibling null in
 * the same expression, which is this project's signature failure: **the correct implementation
 * lived in the same function as the incorrect one.**
 *
 * The stake is not theoretical. `canObserveEvents` gates `recordReviveOutcome`, and
 * `revivesHelped` is the number the whole recovery plan rests on — the one that showed the
 * self-repair is real (6 of 6 on 5 Sep 2026). Judging on a screen we could not confirm was
 * unlocked scores `futile` for a reason that has nothing to do with the repair, which is exactly
 * the failure `recordReviveOutcome`'s own KDoc exists to prevent.
 *
 * So there is one rule, it is pure, and every unknown is `false`: nulls come in as nulls rather
 * than being flattened by the caller, because flattening them at the call site is the bug.
 */
internal fun screenIsJudgeable(interactive: Boolean?, keyguardLocked: Boolean?): Boolean =
    interactive == true && keyguardLocked == false
