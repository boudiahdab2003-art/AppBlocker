package com.appblocker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.ui.theme.AppGradients

/** One expandable help topic: a title + one-line summary, unfolding into the full story. */
private data class Topic(
    val icon: ImageVector,
    val title: String,
    val summary: String,
    val paragraphs: List<String>,
    val bullets: List<String> = emptyList(),
)

/** Profile ▸ Instructions: every feature explained in detail, one expandable topic each. */
@Composable
fun InstructionsScreen(onBack: () -> Unit) {
    // No Scaffold here, so pad the system bars ourselves (edge-to-edge is forced on Android 15+).
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "Instructions", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(
                    "How AppBlocker works",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Every feature, explained in detail. Tap a topic to unfold it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )
            }
            items(TOPICS.size) { i ->
                TopicCard(TOPICS[i])
                Spacer(Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun TopicCard(topic: Topic) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .clickable { expanded = !expanded }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(AppGradients.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(topic.icon, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(topic.title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(topic.summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ExpandMore, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevron))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                topic.paragraphs.forEach { p ->
                    Text(p, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp))
                }
                topic.bullets.forEach { point ->
                    Row(Modifier.padding(top = 8.dp)) {
                        Box(
                            Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape)
                                .background(AppGradients.accent)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(point, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

private val TOPICS = listOf(
    Topic(
        icon = Icons.Filled.Shield,
        title = "Getting started & protection",
        summary = "What powers the blocking, and how it protects itself.",
        paragraphs = listOf(
            "AppBlocker watches which app or page is on screen and covers it with a block " +
                "screen the moment something you've blocked appears. That needs two special " +
                "permissions, both requested in the setup wizard:",
        ),
        bullets = listOf(
            "Accessibility service — the engine. It's how the app sees which app is in front " +
                "and reads browser pages for blocked words. Nothing is sent anywhere; it all " +
                "stays on your phone.",
            "Device admin (\"Prevent uninstall\") — optional but recommended: it stops the app " +
                "from being uninstalled in a weak moment, and Strict Mode turns it on " +
                "automatically.",
            "If protection is ever switched off, a banner appears in the app and a " +
                "notification is posted so you can turn it back on. Profile ▸ Setup & " +
                "permissions re-checks everything.",
        ),
    ),
    Topic(
        icon = Icons.Filled.Block,
        title = "Quick Block",
        summary = "Your main on/off block list.",
        paragraphs = listOf(
            "Quick Block is one list of apps that are blocked whenever it's on. Edit the list " +
                "from the Blocking tab; opening any app on it shows the block screen instead.",
        ),
        bullets = listOf(
            "Sessions — start a Timer (block for a set duration) or a Pomodoro (blocked focus " +
                "rounds with unblocked breaks) from the Quick Block card.",
            "Pause — Quick Block can be paused when no session is running. Pausing also lifts " +
                "the websites that were auto-blocked with your apps.",
            "Extra options — block in-app purchase popups, and auto-add newly installed apps " +
                "to the block list so a fresh install can't sidestep you.",
            "Blocking a popular social app also blocks its website and short links in Chrome " +
                "automatically (Instagram → instagram.com, YouTube → youtube.com and youtu.be, " +
                "X → x.com and t.co…).",
        ),
    ),
    Topic(
        icon = Icons.Filled.Widgets,
        title = "Templates",
        summary = "One-tap presets for common situations.",
        paragraphs = listOf(
            "A template bundles a ready-made set of apps and blocked words — Social Detox, " +
                "Study, Sleep and friends. Tapping one applies the whole set at once and can " +
                "flip on matching extras (like blocking YouTube Shorts). You can customise " +
                "what any template includes before applying it; nothing changes until you tap " +
                "Save.",
        ),
    ),
    Topic(
        icon = Icons.Filled.Schedule,
        title = "Schedules",
        summary = "Five kinds of automatic, conditional blocking.",
        paragraphs = listOf(
            "A schedule blocks its chosen apps only while its condition is met — so the same " +
                "app can be free at lunch and blocked all evening. The five types:",
        ),
        bullets = listOf(
            "Time — blocked during a daily time window, on the weekdays you pick.",
            "Daily limit — a minutes-per-day allowance; once it's used up, blocked till midnight.",
            "Open limit — a number of opens per day; past it, blocked till midnight.",
            "Wi-Fi — blocked while you're on a chosen network (or any Wi-Fi).",
            "Location — blocked within a radius of a place you pick (school, office…).",
        ),
    ),
    Topic(
        icon = Icons.Filled.Lock,
        title = "Strict Mode",
        summary = "The no-way-out commitment timer.",
        paragraphs = listOf(
            "Strict Mode is for when you don't trust future-you. Set a timer and every app on " +
                "your block list is blocked outright until it runs out — no stopping early, " +
                "that's the point.",
            "While it's active you can add blocks but not remove them, the app's dangerous " +
                "settings pages (accessibility, device admin, app info) bounce you straight " +
                "back out, uninstalling is prevented, and turning off the built-in adult " +
                "filter is locked.",
        ),
    ),
    Topic(
        icon = Icons.Filled.Language,
        title = "Blocked words & websites",
        summary = "Word and site filtering, in Chrome and beyond.",
        paragraphs = listOf(
            "Add words you never want to see under Blocking ▸ Blocked words. In Chrome, a " +
                "word blocks the sites and searches that match it — it reads the address bar, " +
                "so a page that merely mentions the word doesn't trigger it. In every other " +
                "app (when \"block in every app\" is on), the word appearing on screen blocks " +
                "immediately.",
        ),
        bullets = listOf(
            "Adult filter — a built-in adult site list plus a word pack (English + Arabic) " +
                "that works everywhere, on by default. Turning the pack off is deliberately " +
                "hard and impossible during Strict Mode.",
            "Unsupported browsers — AppBlocker can only read pages in Chrome. Turn on " +
                "\"Block unsupported browsers\" so other browsers can't be used as a loophole.",
            "Auto site blocking — blocking a social app adds its domains and short links " +
                "(fb.watch, redd.it, pin.it, t.co…) without you typing anything.",
        ),
    ),
    Topic(
        icon = Icons.Filled.Block,
        title = "The block screen",
        summary = "What you see when something is blocked — and why.",
        paragraphs = listOf(
            "When a blocked app or page opens, a full-screen cover appears instantly. It " +
                "always tells you exactly why: which schedule (by name), which limit you hit, " +
                "Quick Block, Strict Mode, or the matched word.",
        ),
        bullets = listOf(
            "\"Minutes reclaimed today\" counts every blocked attempt as roughly three " +
                "minutes of scrolling you didn't do.",
            "Each attempt is counted once — sitting on the block screen doesn't inflate " +
                "your stats.",
            "\"Got it\" takes you to your home screen, never back into the blocked app.",
        ),
    ),
    Topic(
        icon = Icons.Filled.PlayCircle,
        title = "YouTube Shorts",
        summary = "Kill the Shorts feed, keep the rest of YouTube.",
        paragraphs = listOf(
            "With the Shorts option on (and Quick Block active), the Shorts player is " +
                "covered the moment it appears — in the YouTube app and at youtube.com/shorts " +
                "in Chrome — while search, subscriptions and normal videos keep working. " +
                "Scroll out of Shorts and the cover lifts by itself.",
        ),
    ),
    Topic(
        icon = Icons.Filled.BarChart,
        title = "Insights, goals & AI Coach",
        summary = "Your numbers, your goals, and a coach who knows both.",
        paragraphs = listOf(
            "The Insights tab tracks screen time, app opens, phone unlocks, notifications " +
                "and blocked attempts, and lets you set goals (daily screen time, an app " +
                "limit, unlocks) plus a quick daily mood check-in.",
            "The AI Coach reads those numbers and writes you a short daily take — and you " +
                "can chat with it. It runs on Google's Gemini and needs an API key; " +
                "everything else in the app works without one.",
        ),
    ),
    Topic(
        icon = Icons.Filled.Password,
        title = "PIN lock",
        summary = "Lock AppBlocker itself.",
        paragraphs = listOf(
            "Set a PIN under Profile ▸ Protection and AppBlocker asks for it every time it " +
                "opens — so nobody (including a weaker moment of you) can quietly loosen your " +
                "blocks. Remove or change it from the same place.",
        ),
    ),
    Topic(
        icon = Icons.Filled.SystemUpdate,
        title = "Updates",
        summary = "How new versions reach you.",
        paragraphs = listOf(
            "The app updates itself: it checks its GitHub releases, and when a new version " +
                "exists you get a prompt (or tap the version row in Profile to check " +
                "manually). After an update installs, blocking pauses until you reactivate it " +
                "with one tap — deliberately, so an update can never change what's enforced " +
                "without you seeing it.",
        ),
    ),
    Topic(
        icon = Icons.Filled.Palette,
        title = "Make it yours",
        summary = "Icon styles and theme.",
        paragraphs = listOf(
            "Profile ▸ Appearance lets you pick the app's launcher icon (six styles — the " +
                "block screen shows the same icon you picked) and choose light, dark or " +
                "system theme. You can also set your name so the app greets you properly.",
        ),
    ),
)
