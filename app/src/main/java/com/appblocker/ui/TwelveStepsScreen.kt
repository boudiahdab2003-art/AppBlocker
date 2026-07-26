package com.appblocker.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Profile ▸ The Twelve Steps — the programme, in plain English, in the same editorial style as
 * the detox and scenario guides.
 *
 * **Why the wording here is ours.** The owner asked for the official SAA text verbatim. It is not
 * reproduced, because SAA's own site prohibits it in terms that leave nothing to interpret: *"No
 * written material, graphic image, or any other data may be copied, reproduced, duplicated, or
 * conveyed in any other way without the express written permission of International Service
 * Organization of SAA, Inc."* This repository is public and a Play release is planned, so the
 * steps below are AppBlocker's own summary, credited, with a link to the real text. Falling back
 * to AA's original steps would not help — those are copyrighted by AA World Services.
 *
 * If written permission is ever obtained, only [STEPS]`.title` needs replacing; the layout and the
 * plain-language `body` lines are built to sit under either wording.
 *
 * The higher-power language is kept deliberately. The owner chose that, and stripping it would
 * leave something that is no longer the programme it claims to summarise.
 */
@Composable
fun TwelveStepsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(com.appblocker.ui.theme.appBackground())) {
        EditorTopBar(title = "The Twelve Steps", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                GuideHero(
                    kicker = "A PROGRAMME, NOT A FEATURE",
                    title = "The Twelve Steps",
                    subtitle = "Millions of people have walked out of a compulsion using these. " +
                        "They are not a checklist — they are worked slowly, usually with others.",
                    colors = listOf(Color(0xFF2E7D6B), Color(0xFF1B4D45)),
                )
            }

            // Above the steps, not buried under them: someone reading this should know whose
            // programme it is and where the real words live before they read our version of them.
            item { SourceCard(onOpen = { openSaa(context) }) }

            item { GuideSectionLabel(ROMAN[1], "Admitting") }
            STEPS.take(3).forEachIndexed { i, step ->
                item { GuideRuleCard(i + 1, step, top = 14.dp) }
            }

            item { GuideSectionLabel(ROMAN[2], "Clearing the past") }
            STEPS.subList(3, 9).forEachIndexed { i, step ->
                item { GuideRuleCard(i + 4, step, top = 14.dp) }
            }

            item { GuideSectionLabel(ROMAN[3], "Living it") }
            STEPS.subList(9, 12).forEachIndexed { i, step ->
                item { GuideRuleCard(i + 10, step, top = 14.dp) }
            }

            item { GuideSectionLabel(ROMAN[4], "One more thing") }
            item { GuidePlainCard(FELLOWSHIP, top = 14.dp) }

            item { GuideClosingPanel(CLOSING, onBack) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Tappable credit — the source of the programme and where its exact words are. */
@Composable
private fun SourceCard(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Text(
            "WHERE THIS COMES FROM",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "These are the Twelve Steps of Sex Addicts Anonymous, themselves adapted from the " +
                "Twelve Steps of Alcoholics Anonymous. The wording below is this app's own plain-" +
                "English summary, not the official text — SAA doesn't permit its words to be " +
                "reprinted, and I'd rather point you at the real thing than a rough copy.\n\n" +
                "Tap here for the official steps at saa-recovery.org.\n\n" +
                "AppBlocker isn't affiliated with, endorsed by, or connected to SAA or AA.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun openSaa(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(SAA_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private const val SAA_URL = "https://saa-recovery.org/our-program/the-twelve-steps/"

/**
 * The twelve steps in plain English, each with what it actually asks of a person.
 *
 * `title` is the step; `body` is the clarification — which is the half that matters when someone
 * is reading these alone at midnight rather than hearing them explained in a room.
 */
private val STEPS = listOf(
    GuideItem(
        "We admitted we were powerless over our sexual behaviour, and that our lives had become unmanageable.",
        "Not that you are weak — that willpower alone has already been tried, repeatedly, and it " +
            "did not hold. This step is the end of arguing with the evidence.",
    ),
    GuideItem(
        "We came to believe that a Power greater than ourselves could restore us to sanity.",
        "The admission that the way out is not something you can think your way to on your own. " +
            "For most people this is God; the programme lets you start with nothing more than " +
            "being open to it.",
    ),
    GuideItem(
        "We decided to turn our will and our lives over to the care of God, as we understood God.",
        "A decision, not a feeling. It is handing over the steering rather than promising to " +
            "drive better.",
    ),
    GuideItem(
        "We made a searching and fearless moral inventory of ourselves.",
        "Written down, not thought about. Everything: what you did, who it touched, what you have " +
            "been protecting. Fearless means you don't leave out the part you most want to.",
    ),
    GuideItem(
        "We admitted to God, to ourselves, and to another human being the exact nature of our wrongs.",
        "Out loud, to one trustworthy person. Shame survives on being unspeakable, and this is " +
            "the step where that stops. Usually the hardest and the most freeing.",
    ),
    GuideItem(
        "We were entirely ready to have God remove all these defects of character.",
        "Readiness is its own step because most people find they are still fond of some of what " +
            "they are asking to lose.",
    ),
    GuideItem(
        "We humbly asked God to remove our shortcomings.",
        "Asking, having become willing. Humbly means without conditions attached and without a " +
            "deadline.",
    ),
    GuideItem(
        "We made a list of all the people we had harmed, and became willing to make amends to them all.",
        "The list first, and the willingness second. Naming the harm is separate from being ready " +
            "to face it, and this step allows both to take their time.",
    ),
    GuideItem(
        "We made direct amends wherever possible, except when to do so would injure them or others.",
        "Amends are repair, not confession — apologising to make yourself feel lighter can cost " +
            "someone else dearly. The exception in this step is not a loophole; it is protection " +
            "for the people you harmed.",
    ),
    GuideItem(
        "We continued to take personal inventory, and when we were wrong, promptly admitted it.",
        "The daily version of steps four and five. Small and current, so nothing has years to " +
            "accumulate again.",
    ),
    GuideItem(
        "We sought through prayer and meditation to improve our conscious contact with God, praying only for knowledge of God's will for us and the power to carry it out.",
        "Maintenance. The programme's view is that contact is what keeps the compulsion at bay, " +
            "and that it fades without attention, like anything else.",
    ),
    GuideItem(
        "Having had a spiritual awakening as a result of these steps, we tried to carry this message to other addicts, and to practise these principles in all our affairs.",
        "Helping the next person is not a favour to them — the programme holds that it is how you " +
            "keep what you were given. And *all our affairs*: the change is meant to reach your " +
            "work, your family and your temper, not just this one behaviour.",
    ),
)

/** Said once, plainly. An app that let someone believe it could replace a room full of people
 *  would be doing them a quiet disservice. */
private val FELLOWSHIP = GuideItem(
    "These are worked with people, not with a phone.",
    "This screen is a reference, and this app is a lock on a door. The steps do their work in " +
        "meetings, with a sponsor, in conversations that are hard to have. If they speak to you, " +
        "SAA runs free meetings online and in person, and you can attend without giving your " +
        "name. Blocking buys you the time to get there — it was never meant to be the whole plan.",
)

private const val CLOSING =
    "You don't have to believe all of this today. Step one is enough to start with."
