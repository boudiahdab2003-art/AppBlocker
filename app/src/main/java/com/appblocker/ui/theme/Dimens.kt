package com.appblocker.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The sizing half of the design system — the layer that was missing beside [AppBlockerTheme]'s
 * colours and type.
 *
 * These are **not** a rule that every dimension in the app must come from here. Plenty of the
 * app's numbers are deliberately proportional rather than scale-based, and they should stay that
 * way: an icon's corner radius tracks the icon's size (a 40dp icon rounds at 10dp), and a pill
 * button's radius is exactly half its height ([GradientButton] is 54dp tall and rounds at 27dp).
 * Forcing those onto a scale would make them wrong.
 *
 * What this exists for is the case that genuinely drifted: **surfaces that play the same role
 * picking different numbers** — large cards rounded at 20dp on one screen, 22dp on another and
 * 24dp on a third. New code should reach for these; old code converges when it is touched anyway.
 */
object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
}

/**
 * Corner radii for surfaces, named by the role they play.
 *
 * [card] is 20dp because that is what the app already overwhelmingly uses (34 sites against 18 for
 * the next value) — the scale is describing the existing house style, not replacing it.
 */
object Radius {
    /** Chips, badges, small inline surfaces. */
    val small: Dp = 12.dp
    /** Mid-size surfaces: inline panels, list rows, input fields. */
    val medium: Dp = 16.dp
    /** The standard card — the app's default surface. */
    val card: Dp = 20.dp
    /** The gradient hero panels (Insights' headline figure, Profile's header). Rounder than a
     *  plain card on purpose: they carry the accent gradient and read as a feature, not a list
     *  item. Both existing hero panels already agree on this. */
    val hero: Dp = 24.dp
    /** Full-bleed sheets and the largest panels. */
    val large: Dp = 28.dp
}

object AppShapes {
    val small = RoundedCornerShape(Radius.small)
    val medium = RoundedCornerShape(Radius.medium)
    val card = RoundedCornerShape(Radius.card)
    val large = RoundedCornerShape(Radius.large)
}

/**
 * The app's standard card surface.
 *
 * Every screen had grown its own version of this — `SettingCard`, `GuideCard`, `VersionCard`,
 * `ScheduleCard` and the Insights cards were six separate implementations of the same idea, which
 * is exactly why their corner radii drifted apart. The values here are the majority spelling of
 * that shared idea, so adopting it is visually a no-op for most callers.
 *
 * Deliberately minimal: a surface, a hairline outline and an optional lift. Anything a single card
 * needs beyond that stays at the call site rather than becoming a parameter here.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.card,
    /** Soft coloured lift. 0.dp for a flat card (inside an already-elevated container). */
    elevation: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(Space.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (elevation > 0.dp) Modifier.softGlow(shape, elevation = elevation) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(contentPadding),
        content = content,
    )
}
