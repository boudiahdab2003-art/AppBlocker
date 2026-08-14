package com.appblocker

import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.service.BlockInputs
import com.appblocker.service.BlockWhy
import com.appblocker.service.decideBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block/allow decision — what the whole app exists to do, and until now the only core piece
 * with no tests (the timers, time windows and word matcher all have them). These state the rules
 * each layer promises, so a future change to one layer can't quietly move another.
 */
class BlockDecisionTest {

    private val app = "com.example.social"

    private fun inputs(
        pkg: String = app,
        updatePaused: Boolean = false,
        lockoutRemainingMs: Long = 0L,
        lockoutWord: String? = null,
        strict: Boolean = false,
        quickEnforcing: Boolean = true,
        rule: AppRule? = null,
        allowlistMode: Boolean = false,
        isEssential: Boolean = false,
        schedules: List<Schedule> = emptyList(),
        isUnsupportedBrowser: Boolean = false,
        unsupportedBrowserBlocking: Boolean = false,
        usedMinutes: Int = 0,
        opens: Int = 0,
        conditionMet: Boolean = false,
    ) = BlockInputs(
        pkg = pkg,
        updatePaused = updatePaused,
        lockoutRemainingMs = lockoutRemainingMs,
        lockoutWord = lockoutWord,
        strict = strict,
        quickEnforcing = quickEnforcing,
        rule = rule,
        allowlistMode = allowlistMode,
        isEssential = { isEssential },
        schedules = schedules,
        isUnsupportedBrowser = isUnsupportedBrowser,
        unsupportedBrowserBlockingActive = { unsupportedBrowserBlocking },
        usedMinutesToday = { usedMinutes },
        opensToday = { opens },
        scheduleConditionMet = { conditionMet },
        scheduleLabel = { "your “Focus” schedule" },
        hourMinuteLabel = { "17:00" },
    )

    private fun rule(
        blocked: Boolean = true,
        mode: BlockMode = BlockMode.HARD,
        limit: Int = -1,
        allowed: Boolean = false,
    ) = AppRule(
        packageName = app, appLabel = "Social", isBlocked = blocked, isAllowed = allowed,
        mode = mode, dailyLimitMinutes = limit,
    )

    private fun schedule(
        type: ScheduleType,
        enabled: Boolean = true,
        packages: List<String> = listOf(app),
        limitMinutes: Int = 0,
        limitCount: Int = 0,
        endMinutes: Int = 1020,
        ssid: String = "",
    ) = Schedule(
        name = "Focus", type = type, enabled = enabled, startMinutes = 540, endMinutes = endMinutes,
        daysMask = 0b1111111, limitMinutes = limitMinutes, limitCount = limitCount,
        wifiSsid = ssid, packages = packages,
    )

    // ---- the after-update pause outranks everything ------------------------------------

    @Test fun updatePauseBlocksNothing() {
        assertNull(decideBlock(inputs(updatePaused = true, strict = true, rule = rule())))
    }

    // ---- keyword lockout ----------------------------------------------------------------

    @Test fun lockoutWinsOverEveryOtherLayer() {
        val hit = decideBlock(inputs(lockoutRemainingMs = 5 * 60_000L, lockoutWord = "casino", rule = null))
        assertEquals("Locked", hit?.title)
        assertEquals("“casino” was found here. Locked for 5 more min.", hit?.message)
    }

    /** Remaining minutes round UP, so a lockout never reads "0 more min" while it's still on. */
    @Test fun lockoutMinutesRoundUp() {
        val hit = decideBlock(inputs(lockoutRemainingMs = 1_000L, lockoutWord = null))
        assertEquals("A blocked word was found here. Locked for 1 more min.", hit?.message)
    }

    // ---- Strict / Quick Block ------------------------------------------------------------

    @Test fun strictBlocksAChosenApp() {
        val hit = decideBlock(inputs(strict = true, rule = rule(mode = BlockMode.LIMIT, limit = 999)))
        assertEquals("Strict Mode", hit?.title)
    }

    @Test fun hardRuleBlocksWhileQuickBlockIsEnforcing() {
        assertEquals("Blocked", decideBlock(inputs(rule = rule()))?.title)
    }

    @Test fun hardRuleDoesNotBlockWhilePaused() {
        assertNull(decideBlock(inputs(rule = rule(), quickEnforcing = false)))
    }

    @Test fun anUnblockedAppIsNeverBlocked() {
        assertNull(decideBlock(inputs(rule = rule(blocked = false))))
    }

    @Test fun dailyLimitFiresAtTheLimitAndNotBefore() {
        assertNull(decideBlock(inputs(rule = rule(mode = BlockMode.LIMIT, limit = 30), usedMinutes = 29)))
        val hit = decideBlock(inputs(rule = rule(mode = BlockMode.LIMIT, limit = 30), usedMinutes = 30))
        assertEquals("Daily limit reached", hit?.title)
        assertEquals("You've used your 30 min for today.", hit?.message)
    }

    /** A zero-minute limit means "not today at all", and says so rather than "you've used 0 min". */
    @Test fun zeroMinuteLimitReadsAsBlockedForToday() {
        val hit = decideBlock(inputs(rule = rule(mode = BlockMode.LIMIT, limit = 0)))
        assertEquals("This app is blocked for today.", hit?.message)
    }

    // ---- Allowlist mode -------------------------------------------------------------------

    @Test fun allowlistBlocksAnythingNotAllowed() {
        val hit = decideBlock(inputs(allowlistMode = true, rule = null))
        assertEquals("Blocked", hit?.title)
        assertEquals("Only your allowed apps work right now.", hit?.message)
    }

    @Test fun allowlistLetsAnAllowedAppThrough() {
        assertNull(decideBlock(inputs(allowlistMode = true, rule = rule(blocked = false, allowed = true))))
    }

    /** The launcher, dialer and keyboard must survive Allowlist mode or the phone is bricked. */
    @Test fun allowlistNeverBlocksEssentials() {
        assertNull(decideBlock(inputs(allowlistMode = true, isEssential = true)))
    }

    /**
     * "Never" has to mean never — including layers checked before the allowlist branch. A
     * keyword lockout used to win, and lockouts are keyed by the last foreground package, which
     * can be stale; one landing on the launcher covered the home screen.
     */
    @Test fun allowlistEssentialsSurviveAKeywordLockout() {
        assertNull(
            decideBlock(
                inputs(
                    allowlistMode = true, isEssential = true,
                    lockoutRemainingMs = 10 * 60_000L, lockoutWord = "porn",
                )
            )
        )
        // Still locked when it isn't an essential — the guarantee is scoped, not a hole.
        assertEquals(
            "Locked",
            decideBlock(
                inputs(allowlistMode = true, lockoutRemainingMs = 10 * 60_000L)
            )?.title,
        )
    }

    /** Same for schedules, which are checked after the allowlist branch. */
    @Test fun allowlistEssentialsSurviveASchedule() {
        assertNull(
            decideBlock(
                inputs(
                    allowlistMode = true, isEssential = true,
                    schedules = listOf(schedule(ScheduleType.TIME)), conditionMet = true,
                )
            )
        )
    }

    @Test fun allowlistSaysStrictWhenStrictIsRunning() {
        assertEquals("Strict Mode", decideBlock(inputs(allowlistMode = true, strict = true))?.title)
    }

    @Test fun allowlistIsInertWhileNotEnforcing() {
        assertNull(decideBlock(inputs(allowlistMode = true, quickEnforcing = false)))
    }

    /** In Allowlist mode the per-app modes are deliberately ignored. */
    @Test fun allowlistIgnoresPerAppLimits() {
        assertNull(
            decideBlock(
                inputs(
                    allowlistMode = true, isEssential = true,
                    rule = rule(mode = BlockMode.LIMIT, limit = 0),
                )
            )
        )
    }

    // ---- schedules -------------------------------------------------------------------------

    @Test fun timeScheduleBlocksInsideItsWindow() {
        val hit = decideBlock(inputs(schedules = listOf(schedule(ScheduleType.TIME)), conditionMet = true))
        assertEquals("Blocked by schedule", hit?.title)
        assertEquals("your “Focus” schedule is on until 17:00.", hit?.message)
    }

    @Test fun timeScheduleIsQuietOutsideItsWindow() {
        assertNull(decideBlock(inputs(schedules = listOf(schedule(ScheduleType.TIME)), conditionMet = false)))
    }

    @Test fun disabledSchedulesAreIgnored() {
        assertNull(
            decideBlock(
                inputs(schedules = listOf(schedule(ScheduleType.TIME, enabled = false)), conditionMet = true)
            )
        )
    }

    @Test fun schedulesOnlyApplyToTheirOwnApps() {
        assertNull(
            decideBlock(
                inputs(
                    schedules = listOf(schedule(ScheduleType.TIME, packages = listOf("com.other"))),
                    conditionMet = true,
                )
            )
        )
    }

    @Test fun usageLimitScheduleFiresAtItsLimit() {
        val s = listOf(schedule(ScheduleType.USAGE_LIMIT, limitMinutes = 45))
        assertNull(decideBlock(inputs(schedules = s, usedMinutes = 44)))
        assertEquals("Daily limit reached", decideBlock(inputs(schedules = s, usedMinutes = 45))?.title)
    }

    @Test fun launchCountScheduleFiresAtItsLimit() {
        val s = listOf(schedule(ScheduleType.LAUNCH_COUNT, limitCount = 5))
        assertNull(decideBlock(inputs(schedules = s, opens = 4)))
        val hit = decideBlock(inputs(schedules = s, opens = 5))
        assertEquals("Open limit reached", hit?.title)
        assertEquals("Opened 5 times today — the limit set by your “Focus” schedule.", hit?.message)
    }

    @Test fun wifiScheduleNamesTheNetworkWhenItHasOne() {
        val hit = decideBlock(
            inputs(schedules = listOf(schedule(ScheduleType.WIFI, ssid = "Home")), conditionMet = true)
        )
        assertEquals("Blocked on this Wi-Fi", hit?.title)
        assertEquals("This app is blocked on “Home”.", hit?.message)
    }

    @Test fun locationScheduleBlocksAtThePlace() {
        val hit = decideBlock(
            inputs(schedules = listOf(schedule(ScheduleType.LOCATION)), conditionMet = true)
        )
        assertEquals("Blocked at this location", hit?.title)
    }

    /** First match wins, in list order. */
    @Test fun theFirstMatchingScheduleIsTheOneReported() {
        val hit = decideBlock(
            inputs(
                schedules = listOf(
                    schedule(ScheduleType.LAUNCH_COUNT, limitCount = 1),
                    schedule(ScheduleType.TIME),
                ),
                opens = 5, conditionMet = true,
            )
        )
        assertEquals("Open limit reached", hit?.title)
    }

    // ---- unsupported browsers ---------------------------------------------------------------

    @Test fun unfilterableBrowserIsBlockedOnlyWhileWordFilteringIsOn() {
        assertNull(decideBlock(inputs(isUnsupportedBrowser = true, unsupportedBrowserBlocking = false)))
        assertNotNull(decideBlock(inputs(isUnsupportedBrowser = true, unsupportedBrowserBlocking = true)))
    }

    @Test fun aFilterableBrowserIsLeftAlone() {
        assertNull(decideBlock(inputs(isUnsupportedBrowser = false, unsupportedBrowserBlocking = true)))
    }

    // ---- the ordinary case ------------------------------------------------------------------

    @Test fun anAppWithNoRulesIsNotBlocked() {
        assertNull(decideBlock(inputs()))
    }

    // ---- the reason code that reaches the bug report -----------------------------------------

    /**
     * **Report #5 is why these exist.** "the app blocked claude idk why" arrived with a log saying
     * only that an app rule had fired — so which layer did it, the block list or the
     * unsupported-browser switch, could not be answered, and answering it wrong means fixing the
     * wrong thing. Every decision now carries a stable code into `BlockLog`.
     *
     * Asserted per layer rather than in a loop: the value of a code is that it identifies **which**
     * one, and a loop over "is it non-blank" would pass with every layer reporting the same word.
     */
    @Test fun everyBlockedLayerSaysWhichOneItWas() {
        assertEquals(
            BlockWhy.LOCKOUT,
            decideBlock(inputs(lockoutRemainingMs = 60_000L, lockoutWord = "casino"))?.why,
        )
        assertEquals(BlockWhy.QUICK, decideBlock(inputs(rule = rule()))?.why)
        assertEquals(BlockWhy.STRICT, decideBlock(inputs(strict = true, rule = rule()))?.why)
        assertEquals(
            BlockWhy.LIMIT,
            decideBlock(inputs(rule = rule(mode = BlockMode.LIMIT, limit = 5), usedMinutes = 9))?.why,
        )
        assertEquals(BlockWhy.ALLOWLIST, decideBlock(inputs(allowlistMode = true))?.why)
        assertEquals(
            BlockWhy.SCHED_TIME,
            decideBlock(
                inputs(schedules = listOf(schedule(ScheduleType.TIME)), conditionMet = true),
            )?.why,
        )
        assertEquals(
            BlockWhy.SCHED_USAGE,
            decideBlock(
                inputs(
                    schedules = listOf(schedule(ScheduleType.USAGE_LIMIT, limitMinutes = 30)),
                    usedMinutes = 45,
                ),
            )?.why,
        )
    }

    /**
     * The one that matters most for report #5. If a cover over an ordinary app ever reports
     * `why=browser`, the app decided that app was an unfilterable *browser* — which would point
     * straight at the browser sets rather than at the block list, and is otherwise indisputable
     * only by guessing.
     */
    @Test fun theUnsupportedBrowserBlockIsIdentifiableInAReport() {
        assertEquals(
            BlockWhy.BROWSER,
            decideBlock(
                inputs(isUnsupportedBrowser = true, unsupportedBrowserBlocking = true),
            )?.why,
        )
    }

    /** A code that isn't in the vocabulary would reach a report as a word nobody can look up. */
    @Test fun everyCodeIsInTheVocabulary() {
        val reasons = listOfNotNull(
            decideBlock(inputs(lockoutRemainingMs = 1L)),
            decideBlock(inputs(rule = rule())),
            decideBlock(inputs(strict = true, rule = rule())),
            decideBlock(inputs(allowlistMode = true)),
            decideBlock(inputs(isUnsupportedBrowser = true, unsupportedBrowserBlocking = true)),
            decideBlock(inputs(schedules = listOf(schedule(ScheduleType.WIFI)), conditionMet = true)),
            decideBlock(
                inputs(schedules = listOf(schedule(ScheduleType.LOCATION)), conditionMet = true),
            ),
            decideBlock(
                inputs(schedules = listOf(schedule(ScheduleType.LAUNCH_COUNT, limitCount = 3)), opens = 5),
            ),
        )
        for (r in reasons) {
            assertTrue("${r.why} is not in BlockWhy.ALL", r.why in BlockWhy.ALL)
        }
    }
}
