package com.appblocker

import com.appblocker.data.AiCoach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Model ids rot, and a rotten one at the head of a chain is silent.**
 *
 * On 2 Sep 2026 `gemini-2.5-pro` was found answering 404 \u2014 "no longer available to new users" \u2014
 * from the first position in [AiCoach.COACH_CHAIN]. The fallback meant the coach still replied,
 * so nothing looked wrong: every request simply paid for a guaranteed failure and then served
 * flash, for weeks, while the comment beside it said the id was preferred and merely out of quota.
 *
 * A dead id cannot be caught by a unit test \u2014 only by asking the API, which is a thing to do
 * deliberately and record. This pins what that asking found, so retiring an id is a decision
 * somebody makes rather than something that quietly happens.
 */
class CoachModelChainTest {

    /** Verified 404 against the owner's key, 2 Sep 2026. Re-probing costs a round trip per call. */
    private val retired = listOf("gemini-2.5-pro", "gemini-3.1-pro", "gemini-3-pro", "gemini-3.5-pro")

    @Test fun noRetiredModelIsStillBeingTried() {
        val still = AiCoach.COACH_CHAIN.models.filter { it in retired } +
            AiCoach.UTILITY_CHAIN.models.filter { it in retired }
        assertEquals(
            "these ids answered 404 on the owner's key on 2 Sep 2026, so every attempt at one is " +
                "a round trip bought and thrown away: $still",
            emptyList<String>(),
            still,
        )
    }

    /**
     * The chain is a preference order, and the point of the first slot is that it is the answer
     * worth having. A chain that is flash all the way down is not degraded, it is just flash.
     */
    @Test fun theCoachStillPrefersAProModel() =
        assertTrue(
            "COACH_CHAIN's first model must be a pro tier one: ${AiCoach.COACH_CHAIN.models}",
            AiCoach.COACH_CHAIN.models.first().contains("pro"),
        )

    /** Flash stays behind it, so a bad guess degrades to today's behaviour, never to no coach. */
    @Test fun flashRemainsTheLastResort() =
        assertTrue(
            "the coach must keep a flash fallback: ${AiCoach.COACH_CHAIN.models}",
            AiCoach.COACH_CHAIN.models.last().contains("flash"),
        )
}
