package com.appblocker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.appblocker.data.SettingsStore
import com.appblocker.data.TemplateOptionsStore

/** A Quick Block "Extra option" a template can switch on when applied. */
enum class QuickOption(val key: String, val label: String) {
    ADULT("adult", "Block adult sites"),
    ADD_NEW("add_new", "Auto-block newly installed apps"),
    PURCHASES("purchases", "Block in-app purchases"),
    UNSUPPORTED("unsupported", "Block unsupported browsers");

    companion object {
        fun fromKey(key: String): QuickOption? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A one-tap preset: blocks a curated set of apps + keywords, and switches on any Quick Block
 * extra options it carries. Packages are matched by name; ones not installed are stored
 * harmlessly so they block if installed later.
 */
data class Template(
    val id: String,
    val title: String,
    val subtitle: String,
    /**
     * The card's identity, and the thing you actually recognise it by.
     *
     * The six templates used to differ only by a two-stop gradient, which is why they read as
     * interchangeable: same icon chip, same layout, same everything but a hue. A glyph is
     * recognisable before a word is read, which a coloured rectangle is not.
     */
    val emoji: String,
    /** The italic line under the card — why you would want this, in one sentence. */
    val tagline: String,
    /** Kept for the small type chip in the card's corner, not as the card's main image. */
    val icon: ImageVector,
    /**
     * The template's accent. **No longer the card's fill** — the cards are now the same restrained
     * surface as everything else on the tab, and this tints the emoji's halo, the type chip and
     * the glow that marks the applied one.
     */
    val colors: List<Color>,
    val packages: List<Pair<String, String>> = emptyList(), // package to label
    val options: Set<QuickOption> = emptySet(),
    // Time window applied when this template's apps are scheduled (0/0 = none).
    val startMinutes: Int = 0,
    val endMinutes: Int = 0,
    val daysMask: Int = 0b1111111,
) {
    /** True when this template creates a time-window app schedule. */
    val hasSchedule: Boolean get() = packages.isNotEmpty() && (startMinutes != endMinutes)

    /** "Mon–Fri · 9:00 AM – 5:00 PM" / "Every day · 10:00 PM – 7:00 AM" / "" for adult-only. */
    val timeLabel: String
        get() = if (!hasSchedule) "" else "${daysText(daysMask)} · ${fmtWindow(startMinutes, endMinutes)}"

    /**
     * The same fact as [timeLabel] in the card's voice — "Mon–Fri, 9:00 AM – 5:00 PM".
     *
     * Separate from [timeLabel] because that one is punctuated for a cramped tile and is also read
     * by [templateSummary]'s prose; this one is a sentence a person reads at a glance. A template
     * with no window still says something rather than leaving a hole where the line should be.
     */
    val scheduleLine: String
        get() = if (hasSchedule) {
            "${daysText(daysMask)}, ${fmtWindow(startMinutes, endMinutes)}"
        } else {
            "Every day, all day"
        }
}

/** The options this template will turn on — the user's per-template edit, or its defaults. */
fun Template.effectiveOptions(context: Context): Set<QuickOption> =
    TemplateOptionsStore.optionsFor(context, id)
        ?.mapNotNull { QuickOption.fromKey(it) }?.toSet()
        ?: options

/** Whether a Quick Block extra option is currently switched on. */
fun QuickOption.isOn(context: Context): Boolean = when (this) {
    QuickOption.ADULT -> SettingsStore.blockAdult(context)
    QuickOption.ADD_NEW -> SettingsStore.addNewApps(context)
    QuickOption.PURCHASES -> SettingsStore.blockPurchases(context)
    QuickOption.UNSUPPORTED -> SettingsStore.blockUnsupportedBrowsers(context)
}

/** Switch a Quick Block extra option on (templates only ever turn options ON, never off). */
fun QuickOption.turnOn(context: Context) = when (this) {
    QuickOption.ADULT -> SettingsStore.setBlockAdult(context, true)
    QuickOption.ADD_NEW -> SettingsStore.setAddNewApps(context, true)
    QuickOption.PURCHASES -> SettingsStore.setBlockPurchases(context, true)
    QuickOption.UNSUPPORTED -> SettingsStore.setBlockUnsupportedBrowsers(context, true)
}

private val SOCIAL = listOf(
    "com.instagram.android" to "Instagram",
    "com.zhiliaoapp.musically" to "TikTok",
    "com.facebook.katana" to "Facebook",
    "com.twitter.android" to "X",
    "com.snapchat.android" to "Snapchat",
    "com.reddit.frontpage" to "Reddit",
)
private val VIDEO = listOf(
    "com.google.android.youtube" to "YouTube",
    "com.netflix.mediaclient" to "Netflix",
)
private val GAMES = listOf(
    "com.supercell.clashofclans" to "Clash of Clans",
    "com.king.candycrushsaga" to "Candy Crush",
    "com.mojang.minecraftpe" to "Minecraft",
    "com.roblox.client" to "Roblox",
)

private const val WEEKDAYS = 0b0111110 // Mon–Fri

val appTemplates: List<Template> = listOf(
    Template(
        "social", "Social Detox", "Block the social feeds",
        emoji = "💬", tagline = "The feed will still be there later.",
        icon = Icons.Filled.Groups, colors = listOf(Color(0xFFF0598A), Color(0xFFB5179E)),
        packages = SOCIAL,
        startMinutes = 9 * 60, endMinutes = 17 * 60, daysMask = WEEKDAYS,
    ),
    Template(
        "focus", "Deep Focus", "Social + video, gone",
        emoji = "🎯", tagline = "Deep work needs a quiet phone.",
        icon = Icons.Filled.Bolt, colors = listOf(Color(0xFF2E7BFF), Color(0xFF7C5CFF)),
        packages = SOCIAL + VIDEO,
        options = setOf(QuickOption.UNSUPPORTED),
        startMinutes = 9 * 60, endMinutes = 12 * 60,
    ),
    Template(
        "clean", "Stay Clean", "Adult content filter on",
        emoji = "🛡️", tagline = "One less thing to fight on your own.",
        icon = Icons.Filled.Shield, colors = listOf(Color(0xFFFB7185), Color(0xFFE11D48)),
        options = setOf(QuickOption.ADULT, QuickOption.UNSUPPORTED),
    ),
    Template(
        "sleep", "Sleep Well", "Wind down, no scrolling",
        emoji = "🌙", tagline = "Your best mornings start the night before.",
        icon = Icons.Filled.Bedtime, colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
        packages = VIDEO + SOCIAL.take(2),
        startMinutes = 22 * 60, endMinutes = 7 * 60,
    ),
    Template(
        "study", "Study Mode", "Block fun, keep tools",
        emoji = "📚", tagline = "Everything fun, paused until you're done.",
        icon = Icons.Filled.School, colors = listOf(Color(0xFF14B8A6), Color(0xFF22C55E)),
        packages = SOCIAL + VIDEO + GAMES,
        options = setOf(QuickOption.UNSUPPORTED),
        startMinutes = 8 * 60, endMinutes = 16 * 60, daysMask = WEEKDAYS,
    ),
    Template(
        "gaming", "Gaming Break", "Step away from games",
        emoji = "🎮", tagline = "Step away now, come back sharper.",
        icon = Icons.Filled.SportsEsports, colors = listOf(Color(0xFFFB923C), Color(0xFFF97316)),
        packages = GAMES,
        options = setOf(QuickOption.PURCHASES),
        startMinutes = 9 * 60, endMinutes = 18 * 60,
    ),
)
