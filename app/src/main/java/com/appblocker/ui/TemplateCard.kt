package com.appblocker.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appblocker.data.Schedule
import com.appblocker.data.TemplateStore
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.AppShapes
import com.appblocker.ui.theme.softGlow

/** Test tag for the card surface — the rendering test measures it. */
const val TEMPLATE_CARD_TAG = "template_card"

/**
 * A one-tap preset, as a full-width card.
 *
 * **What this replaced, and why.** Six templates in a two-column grid, each a full-bleed
 * two-stop gradient with a white icon chip. The owner's verdict was "plain and flat" and "they
 * all look the same", and reading them back he was right on both counts: the six cards had an
 * identical structure and differed *only* by hue — and not even reliably, since Deep Focus used
 * the brand accent verbatim and Gaming Break's two stops were nearly the same colour. At ~154dp
 * wide there was also nowhere to put emphasis: 16sp titles, a 12dp clock glyph with padding
 * inside its own 12dp box.
 *
 * The new shape follows the AppBlock screens the owner asked for, and lands somewhere the rest of
 * this app already lives: **surface fill, hairline border, soft lift** — the same language as
 * `ScheduleCard` and the Quick Block card. The old templates were the only full-bleed gradient
 * surfaces on the tab; the redesign brings them inside the design system rather than further out.
 *
 * Identity now comes from **the emoji**, which is recognisable before a word is read. The
 * template's colour still does work — it tints the halo behind the emoji, the type chip, and the
 * glow that marks the applied one — but it no longer has to carry the whole card.
 *
 * **Applied looks applied.** It used to change nothing but a small pill; it now takes a coloured
 * border and a deeper glow, which is both AppBlock's idiom and the one the Quick Block card
 * directly above already uses (4dp → 14dp plus a border).
 */
@Composable
internal fun TemplateCard(
    modifier: Modifier,
    t: Template,
    active: Boolean,
    onEditApps: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val accent = t.colors.first()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    // Small enough to read as the card yielding rather than as an animation.
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "templatePress")

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .testTag(TEMPLATE_CARD_TAG)
                .scale(scale)
                // heightIn, never height: the card must still grow when a large system font makes
                // its content taller. A fixed height here is the exact bug the old grid's
                // IntrinsicSize.Min comment was added to fix.
                .heightIn(min = 168.dp)
                .softGlow(
                    AppShapes.card,
                    glow = if (active) accent else AppGradients.AccentStart,
                    elevation = if (active) 14.dp else 4.dp,
                )
                .clip(AppShapes.card)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = if (active) 1.5.dp else 1.dp,
                    color = if (active) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    shape = AppShapes.card,
                )
                .clickable(interactionSource = press, indication = null, onClick = onClick)
                .padding(vertical = 18.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Type chip and the edit pencil sit on the same line, at the card's shoulders.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CornerChip(icon = t.icon, tint = accent, description = null, onClick = null)
                if (onEditApps != null) {
                    CornerChip(
                        icon = Icons.Filled.Edit,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        description = "Choose apps for ${t.title}",
                        onClick = onEditApps,
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
            // The halo is what stops the emoji reading as pasted on, and it is where the
            // template's colour earns its keep now that it is not the card's fill.
            Box(
                Modifier.size(76.dp)
                    .background(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent)),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(t.emoji, fontSize = 38.sp)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                t.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                t.scheduleLine,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // The glow and the border carry this visually, but a colour is not a label: on its
            // own it says nothing to a screen reader and nothing to anyone who can't tell this
            // card's border from the next one's. One word, in the accent, keeps it stated.
            if (active) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Active",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                        // Kept from the old card: at large fonts this used to break letter by
                        // letter into "Ac/tiv/e".
                        softWrap = false,
                    )
                }
            }
        }

        // Outside the card, like AppBlock's quotes — it is a reason to tap, not a property of
        // the thing being tapped.
        Spacer(Modifier.height(10.dp))
        Text(
            "“${t.tagline}”",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

/** A round chip at one of the card's top corners — the type marker, or the edit pencil. */
@Composable
private fun CornerChip(
    icon: ImageVector,
    tint: Color,
    description: String?,
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(17.dp))
    }
}

internal fun isTemplateActive(t: Template, schedules: List<Schedule>, context: Context): Boolean {
    // App templates are "active" while their schedule is enabled.
    if (t.packages.isNotEmpty()) return schedules.any { it.name == t.title && it.enabled }
    // Options-only templates (e.g. Stay Clean) are active when all their options are on.
    val opts = t.effectiveOptions(context)
    return opts.isNotEmpty() && opts.all { it.isOn(context) }
}

internal fun templateSummary(t: Template, context: Context): String {
    val parts = mutableListOf<String>()
    val appCount = TemplateStore.packagesFor(context, t.id)?.size ?: t.packages.size
    if (appCount > 0) {
        parts += "$appCount apps" + if (t.timeLabel.isNotEmpty()) " (${t.timeLabel})" else ""
    }
    val summary = if (parts.isEmpty()) "" else "This will block " + parts.joinToString(", ") + "."
    val opts = t.effectiveOptions(context)
    val optLine = if (opts.isEmpty()) ""
    else " It will also turn on: " + opts.joinToString(", ") { it.label.replaceFirstChar { c -> c.lowercase() } } + "."
    return (summary + optLine).trim()
}
