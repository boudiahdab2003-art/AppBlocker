package com.appblocker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

/**
 * The editorial "poster" language shared by every long-form guide — the Dopamine detox
 * rulebook ([DopamineDetoxScreen]) and the situational guides ([ScenariosScreen]). Both
 * screens are the same magazine layout over different content, so the pieces live here once:
 * a gradient hero, numbered section headers, rule/truth/step/plain cards and a closing panel.
 *
 * A guide supplies its own colours and icon; everything else is fixed so the guides read as
 * one set.
 */

/** Section numerals as a guide reads top to bottom (index 1..n). */
internal val ROMAN = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

/**
 * Gradient poster header. [colors] defaults to the brand accent; pass a guide's own pair to
 * colour-code it, and an [icon] to stamp a faint watermark that gives it a visual signature.
 */
@Composable
internal fun GuideHero(
    kicker: String,
    title: String,
    subtitle: String,
    colors: List<Color>? = null,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(28.dp)
    val background = colors?.let { Brush.verticalGradient(it) } ?: AppGradients.accentVertical
    Box(
        Modifier.fillMaxWidth()
            .softGlow(shape, glow = colors?.first() ?: AppGradients.AccentStart)
            .clip(shape)
            .background(background),
    ) {
        if (icon != null) {
            Icon(
                icon, contentDescription = null,
                tint = Color.White.copy(alpha = 0.13f),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(104.dp),
            )
        }
        Column(Modifier.padding(24.dp)) {
            Text(kicker, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                color = Color.White.copy(alpha = 0.75f))
            Text(title, fontSize = 42.sp, lineHeight = 46.sp,
                fontFamily = FontFamily.Serif, color = Color.White,
                modifier = Modifier.padding(top = 6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Section header: roman numeral in the accent gradient, uppercase title, hairline to the edge. */
@Composable
internal fun GuideSectionLabel(numeral: String, text: String) {
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

/** Plain surface card with a hairline border — the base every guide card is built on. */
@Composable
internal fun GuideCard(top: Dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = top).clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(16.dp),
    ) { content() }
}

/**
 * A numbered rule. The numeral sits in a fixed-width column so every rule's text lines up, and
 * beside the rule's TITLE rather than centred on the whole card — on a long rule (or a large font
 * size) a centred numeral drifts into the middle of the paragraph.
 */
@Composable
internal fun GuideRuleCard(number: Int, item: GuideItem, top: Dp) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = top).clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
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

/** A truth/quote card — accent-gradient border sets it apart from the rules below it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuideMarkCard(item: GuideItem, top: Dp) {
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
        // FlowRow, not Row: the name and the headline share a line when they fit, and the
        // headline drops onto its own line underneath when they don't. A plain Row anchored the
        // name to the LAST line of a wrapped headline, leaving a hole beside the first lines —
        // very visible at large font/display sizes.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(item.term.orEmpty(), fontSize = 22.sp, fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic, style = TextStyle(brush = AppGradients.accent))
            Text(item.title.uppercase(), style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Bottom).padding(bottom = 3.dp))
        }
        Text(item.body, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** Numbered steps inside one card, divided by hairlines (the craving SOS / a guide's steps). */
@Composable
internal fun GuideStepsCard(items: List<GuideItem>) {
    GuideCard(top = 14.dp) {
        items.forEachIndexed { i, step ->
            if (i > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 2.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)))
            }
            Row(
                // Top, not centre: the step number belongs beside the step's title, not adrift
                // in the middle of a long one (which is what large font sizes produce).
                verticalAlignment = Alignment.Top,
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

/** A paragraph card; a blank [GuideItem.title] renders body-only. */
@Composable
internal fun GuidePlainCard(item: GuideItem, top: Dp) {
    GuideCard(top = top) {
        if (item.title.isNotBlank()) {
            Text(item.title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(item.body, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f))
    }
}

/** Closing gradient panel + the "I'm ready" button that takes the reader back. */
@Composable
internal fun GuideClosingPanel(quote: String, onBack: () -> Unit) {
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
