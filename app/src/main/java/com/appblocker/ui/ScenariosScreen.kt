package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.ui.theme.softGlow

/** Profile ▸ Scenarios: an index of guides for hard moments; each opens as a full guide in the
 *  block screen's editorial poster style. */
@Composable
fun ScenariosScreen(onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    BackHandler(enabled = open != null) { open = null }

    AnimatedContent(
        targetState = open,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut()
            } else {
                fadeIn() togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            }
        },
        label = "scenario",
    ) { idx ->
        if (idx == null) ScenarioIndex(onBack = onBack, onOpen = { open = it })
        else ScenarioGuide(SCENARIOS[idx], onBack = { open = null })
    }
}

@Composable
private fun ScenarioIndex(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "Scenarios", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(
                    "Guides for the hard moments",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Open the one you need. Clear steps, no lecture.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )
            }
            items(SCENARIOS.size) { i ->
                ScenarioRow(SCENARIOS[i]) { onOpen(i) }
                Spacer(Modifier.padding(top = 12.dp))
            }
            item { Spacer(Modifier.padding(top = 16.dp)) }
        }
    }
}

@Composable
private fun ScenarioRow(scenario: Scenario, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val tileShape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            // Surface first, then a faint wash of the scenario's own colors so the list
            // reads as a colour-coded set at a glance.
            .background(MaterialTheme.colorScheme.surface)
            .background(Brush.horizontalGradient(scenario.colors.map { it.copy(alpha = 0.12f) }))
            .border(1.dp, scenario.colors.first().copy(alpha = 0.30f), shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp)
                .softGlow(tileShape, glow = scenario.colors.first(), elevation = 8.dp)
                .clip(tileShape)
                .background(Brush.linearGradient(scenario.colors)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(scenario.icon, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(scenario.hubTitle, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(scenario.hubSubtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- The guide renderer ----

@Composable
private fun ScenarioGuide(scenario: Scenario, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = scenario.hubTitle, onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                GuideHero(
                    kicker = scenario.kicker,
                    title = scenario.title,
                    subtitle = scenario.subtitle,
                    colors = scenario.colors,
                    icon = scenario.icon,
                )
            }

            var ruleNo = 1
            scenario.sections.forEachIndexed { si, section ->
                item { GuideSectionLabel(ROMAN[si + 1], section.label) }
                when (section.kind) {
                    GuideKind.RULES -> {
                        val first = ruleNo
                        items(section.items.size) { i ->
                            GuideRuleCard(first + i, section.items[i], top = if (i == 0) 14.dp else 10.dp)
                        }
                        ruleNo += section.items.size
                    }
                    GuideKind.TRUTHS -> items(section.items.size) { i ->
                        GuideMarkCard(section.items[i], top = if (i == 0) 14.dp else 10.dp)
                    }
                    GuideKind.STEPS -> item { GuideStepsCard(section.items) }
                    GuideKind.PLAIN -> items(section.items.size) { i ->
                        GuidePlainCard(section.items[i], top = if (i == 0) 14.dp else 10.dp)
                    }
                }
            }

            item { GuideClosingPanel(scenario.closing, onBack) }
        }
    }
}

