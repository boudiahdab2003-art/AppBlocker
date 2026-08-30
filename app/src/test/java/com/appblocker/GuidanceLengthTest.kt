package com.appblocker

import com.appblocker.data.DeviceVendor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A ceiling on the words shown to someone whose blocking has stopped.**
 *
 * 30 Aug 2026: *"sometimes when the app guides you to the accessibility there is a lot of talk a
 * lot of unneeded one can we make it less"*. He was right, and it had happened one honest paragraph
 * at a time — each explanation was added for a real reason, and nobody ever added up the total. The
 * repair screen reached ~460 words with roughly 200 of them **above** the button that fixes the
 * problem, and three blocks were being shown to him twice across two screens.
 *
 * Layout is what fixed it: the steps and the button now come first, and the explanations sit behind
 * [com.appblocker.ui.ExpandableNote] headings. But layout is not what *keeps* it fixed — the next
 * paragraph will look just as justified as the last thirty did. So this is the mechanical half:
 * a budget, per string, that fails the build rather than a note asking people to be brief.
 *
 * ⚠️ **When this fails, shortening the text is the fix.** Raising a ceiling is the same mistake as
 * widening a `CodeShapeTest` check — see its class KDoc. If a longer explanation is genuinely
 * needed, it belongs in an expandable body with a short heading, which is what the whole pass was
 * about; the ceiling is on what someone must *read past*, not on what the app is allowed to know.
 */
class GuidanceLengthTest {

    private fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    /**
     * The prose someone meets on the way to the accessibility switch.
     *
     * Ceilings are set just above what each string says today, so the check is a ratchet: it
     * accepts the current wording and refuses growth. The numbers are not sacred, the direction is.
     */
    private val ceilings = mapOf(
        // The repair screen, in the order he meets it. The steps and the button are exempt by
        // construction — they are the short ones, and they are what he came for.
        "repair_what_body_1" to 75,
        "repair_what_body_2" to 45,
        "repair_why_body" to 55,
        "repair_held_body" to 45,
        "repair_floating_body" to 70,
        "repair_record_first" to 35,
        // Setup, where the pictures are the instruction and the words are the caption. The tighter
        // ceiling here is deliberate: SetupStepVisibilityTest requires the first screenshot to be
        // fully visible without scrolling, so every word above it costs the thing that works.
        "onboarding_leaves_app" to 45,
        "restricted_body" to 50,
        "restricted_android15" to 35,
        // Permissions, all now behind headings but still read on the way past.
        "permissions_alert_body" to 45,
        "permissions_clone_note" to 35,
    )

    @Test
    fun `no guidance paragraph outgrows its budget`() {
        val over = ceilings.mapNotNull { (key, max) ->
            val text = EnglishStrings.stringKeys.takeIf { key in it }
                ?.let { EnglishStrings.read(EnglishStrings.resourceFile("values")).first[key] }
                ?: error("R.string.$key is in the budget but not in values/strings.xml")
            val n = words(text)
            if (n > max) "$key: $n words (max $max)" else null
        }
        assertTrue(
            "Guidance text has grown past its budget:\n  ${over.joinToString("\n  ")}\n" +
                "Shorten it, or move it into an ExpandableNote body behind a short heading. " +
                "Do not raise the ceiling — see this class's KDoc.",
            over.isEmpty(),
        )
    }

    /**
     * The brand advice is hardcoded Kotlin rather than a resource, so it escapes the check above —
     * and it is the text most likely to grow, because every phone quirk anyone discovers wants to
     * be written down somewhere and this is the somewhere.
     *
     * It is also the text with the strongest reason to be short: he has **already** set Auto-start,
     * Battery ▸ No restrictions and the Recents lock on both his devices. Walking him through them
     * again is the clearest possible sign that a screen is not reading his situation.
     */
    @Test
    fun `the brand advice stays short`() {
        for (advice in DeviceVendor.allAdvice()) {
            assertTrue(
                "${advice.brand} extraTips is ${words(advice.extraTips)} words (max 45).",
                words(advice.extraTips) <= 45,
            )
            advice.spacesWarning?.let {
                assertTrue(
                    "${advice.brand} spacesWarning is ${words(it)} words (max 50).",
                    words(it) <= 50,
                )
            }
        }
    }
}
