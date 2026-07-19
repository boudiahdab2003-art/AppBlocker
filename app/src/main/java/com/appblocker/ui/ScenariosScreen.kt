package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appblocker.ui.theme.AppGradients
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
            item { HeroPanel(scenario) }

            var ruleNo = 1
            scenario.sections.forEachIndexed { si, section ->
                item { SectionLabel(ROMAN[si + 1], section.label) }
                when (section.kind) {
                    GuideKind.RULES -> {
                        val first = ruleNo
                        items(section.items.size) { i ->
                            RuleCard(first + i, section.items[i], top = if (i == 0) 14.dp else 10.dp)
                        }
                        ruleNo += section.items.size
                    }
                    GuideKind.TRUTHS -> items(section.items.size) { i ->
                        MarkCard(section.items[i], top = if (i == 0) 14.dp else 10.dp)
                    }
                    GuideKind.STEPS -> item { StepsCard(section.items) }
                    GuideKind.PLAIN -> items(section.items.size) { i ->
                        PlainCard(section.items[i], top = if (i == 0) 14.dp else 10.dp)
                    }
                }
            }

            item { ClosingPanel(scenario.closing, onBack) }
        }
    }
}

@Composable
private fun HeroPanel(scenario: Scenario) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxWidth()
            .softGlow(shape, glow = scenario.colors.first())
            .clip(shape)
            .background(Brush.verticalGradient(scenario.colors)),
    ) {
        // A large, faint icon watermark gives each guide its own visual signature.
        Icon(
            scenario.icon, contentDescription = null,
            tint = Color.White.copy(alpha = 0.13f),
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(104.dp),
        )
        Column(Modifier.padding(24.dp)) {
            Text(scenario.kicker, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                color = Color.White.copy(alpha = 0.75f))
            Text(scenario.title, fontSize = 42.sp, lineHeight = 46.sp,
                fontFamily = FontFamily.Serif, color = Color.White,
                modifier = Modifier.padding(top = 6.dp))
            Text(scenario.subtitle, style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Editorial section header: roman numeral in the accent gradient, uppercase title, hairline. */
@Composable
private fun SectionLabel(numeral: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 30.dp),
    ) {
        Text(numeral, fontSize = 17.sp, fontFamily = FontFamily.Serif,
            style = TextStyle(brush = AppGradients.accent))
        Spacer(Modifier.width(10.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black, letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
    }
}

@Composable
private fun RuleCard(number: Int, item: GuideItem, top: Dp) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = top).clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(46.dp)) {
            Text(number.toString().padStart(2, '0'), fontSize = 30.sp,
                fontFamily = FontFamily.Serif, style = TextStyle(brush = AppGradients.accent))
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(item.body, style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun MarkCard(item: GuideItem, top: Dp) {
    val shape = RoundedCornerShape(20.dp)
    val accentBorder = BorderStroke(
        1.dp,
        Brush.linearGradient(
            listOf(
                AppGradients.AccentStart.copy(alpha = 0.55f),
                AppGradients.AccentEnd.copy(alpha = 0.55f),
            )
        ),
    )
    Column(
        Modifier.fillMaxWidth().padding(top = top).clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(accentBorder, shape).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(item.term.orEmpty(), fontSize = 22.sp, fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic, style = TextStyle(brush = AppGradients.accent))
            Spacer(Modifier.width(10.dp))
            Text(item.title.uppercase(), style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(item.body, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StepsCard(items: List<GuideItem>) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(16.dp),
    ) {
        items.forEachIndexed { i, step ->
            if (i > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 2.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    top = if (i == 0) 0.dp else 12.dp,
                    bottom = if (i == items.lastIndex) 0.dp else 12.dp,
                ),
            ) {
                Text("${i + 1}", fontSize = 26.sp, fontFamily = FontFamily.Serif,
                    style = TextStyle(brush = AppGradients.accent))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(step.body, style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PlainCard(item: GuideItem, top: Dp) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = top).clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(16.dp),
    ) {
        if (item.title.isNotBlank()) {
            Text(item.title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(item.body, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f))
    }
}

@Composable
private fun ClosingPanel(quote: String, onBack: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 30.dp).softGlow(shape).clip(shape)
            .background(AppGradients.accentVertical).padding(24.dp),
    ) {
        Text("REMEMBER", style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black, letterSpacing = 2.sp,
            color = Color.White.copy(alpha = 0.7f))
        Text(quote, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif,
            lineHeight = 30.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp))
    }
    GradientButton(text = "I'm ready", onClick = onBack,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp))
}

/** Section numerals as the guide reads top to bottom. */
private val ROMAN = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
