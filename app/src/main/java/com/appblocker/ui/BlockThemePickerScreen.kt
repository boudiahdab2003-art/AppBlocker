package com.appblocker.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.appblocker.R
import com.appblocker.data.AppIcons
import com.appblocker.data.BlockArrangement
import com.appblocker.data.BlockLayouts
import com.appblocker.data.BlockThemes
import com.appblocker.data.Quotes
import com.appblocker.data.SettingsStore
import com.appblocker.data.backdropSolid
import com.appblocker.service.BlockScreenRenderer
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.AppShapes

/**
 * Profile ▸ Block screen: choose what the block screen shows (the layout) and what colour it is.
 * The two are independent, so any layout works in any colour.
 *
 * Behaviour is identical in every combination — "Got it" does exactly the same thing — so nothing
 * chosen here can affect blocking.
 */
@Composable
fun BlockThemePickerScreen(strictActive: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(BlockThemes.current(context).id) }
    var layout by remember { mutableStateOf(BlockLayouts.current(context).id) }
    var arrangement by remember { mutableStateOf(BlockArrangement.load(context)) }
    // Locked during Strict: hiding the pieces that make the screen persuasive is no longer
    // purely cosmetic, so it belongs with everything else Strict Mode freezes.
    val editable = !strictActive
    val theme = BlockThemes.OPTIONS.firstOrNull { it.id == selected } ?: BlockThemes.OPTIONS.first()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "Block screen", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(
                    "Pick a starting point, choose a colour, then show, hide and reorder the " +
                        "pieces. The preview is the real block screen, not a picture of one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                if (!editable) {
                    Box(
                        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(AppShapes.medium)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(14.dp),
                    ) {
                        Text(
                            "🔒 Strict Mode is on — the block screen is locked until it ends.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                BlockScreenPreview(
                    layoutId = layout,
                    themeId = selected,
                    arrangement = arrangement,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                )
            }

            item { PickerSectionLabel("Layout") }
            items(BlockLayouts.OPTIONS.size) { i ->
                val option = BlockLayouts.OPTIONS[i]
                LayoutRow(
                    option = option,
                    // Previews use the colour currently selected, so the two choices are seen
                    // together rather than each in isolation.
                    theme = theme,
                    selected = option.id == layout,
                    enabled = editable,
                    onClick = {
                        SettingsStore.setBlockLayout(context, option.id)
                        layout = option.id
                    },
                )
            }

            item { PickerSectionLabel("Colour") }
            items(BlockThemes.OPTIONS.size) { i ->
                val option = BlockThemes.OPTIONS[i]
                ThemeRow(
                    option = option,
                    selected = option.id == selected,
                    enabled = editable,
                    onClick = {
                        SettingsStore.setBlockTheme(context, option.id)
                        selected = option.id
                    },
                )
            }
            item { PickerSectionLabel("Pieces") }
            // Only the pieces this layout actually has. Listing all four everywhere meant Focus
            // offered a "Quote" switch that did nothing, because that layout has no quote in it —
            // a control that lies about what it does is worse than a missing one.
            val piecesHere = arrangement.order.filter { it in BlockLayouts.byId(layout).elements }
            items(piecesHere.size) { i ->
                val element = piecesHere[i]
                ElementRow(
                    element = element,
                    visible = arrangement.isVisible(element),
                    size = arrangement.sizeOf(element),
                    quoteAlign = arrangement.quoteAlign,
                    canMoveUp = i > 0,
                    canMoveDown = i < piecesHere.lastIndex,
                    enabled = editable,
                    onToggle = {
                        arrangement = arrangement.copy(
                            hidden = if (arrangement.isVisible(element)) arrangement.hidden + element
                            else arrangement.hidden - element,
                        ).also { BlockArrangement.save(context, it) }
                    },
                    onMove = { delta ->
                        // Indices here are into the FILTERED list, so translate through the full
                        // order — otherwise moving a piece on a layout that hides one would
                        // reorder the wrong element.
                        val list = arrangement.order.toMutableList()
                        val from = list.indexOf(element)
                        val to = list.indexOf(piecesHere[i + delta])
                        list.add(to, list.removeAt(from))
                        arrangement = arrangement.copy(order = list)
                            .also { BlockArrangement.save(context, it) }
                    },
                    onSize = { newSize ->
                        arrangement = arrangement.copy(
                            sizes = arrangement.sizes + (element to newSize),
                        ).also { BlockArrangement.save(context, it) }
                    },
                    onQuoteAlign = { newAlign ->
                        arrangement = arrangement.copy(quoteAlign = newAlign)
                            .also { BlockArrangement.save(context, it) }
                    },
                )
            }

            item { PickerSectionLabel("Alignment") }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlockArrangement.Align.entries.forEach { a ->
                        AlignChip(
                            label = when (a) {
                                BlockArrangement.Align.DEFAULT -> "As designed"
                                BlockArrangement.Align.LEFT -> "Left"
                                BlockArrangement.Align.CENTRE -> "Centred"
                            },
                            selected = arrangement.align == a,
                            enabled = editable,
                            modifier = Modifier.weight(1f),
                        ) {
                            arrangement = arrangement.copy(align = a)
                                .also { BlockArrangement.save(context, it) }
                        }
                    }
                }
                if (arrangement != BlockArrangement.DEFAULT) {
                    TextButton(
                        onClick = {
                            BlockArrangement.reset(context)
                            arrangement = BlockArrangement.DEFAULT
                        },
                        enabled = editable,
                    ) { Text("Reset the pieces to this layout's own arrangement") }
                }
            }

            item {
                Text(
                    "The next block screen you see will use it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun PickerSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
    )
}

@Composable
private fun LayoutRow(
    option: BlockLayouts.BlockLayout,
    theme: BlockThemes.BlockTheme,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp)
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) AppGradients.accent
                else SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                AppShapes.card,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayoutPreview(option, theme)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                option.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** A miniature of each arrangement, in the currently chosen colour. Same reasoning as
 *  [ThemePreview]: drawn from the real values, so it can't advertise a layout that isn't. */
@Composable
private fun LayoutPreview(option: BlockLayouts.BlockLayout, theme: BlockThemes.BlockTheme) {
    val shape = RoundedCornerShape(10.dp)
    val background = if (theme.id == "aurora") AppGradients.accent
    else SolidColor(Color(theme.backdropSolid()))
    Column(
        Modifier.size(width = 54.dp, height = 78.dp).clip(shape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
            .padding(7.dp),
        horizontalAlignment = if (option.appCentred) Alignment.CenterHorizontally
        else Alignment.Start,
    ) {
        if (option.appCentred) {
            // Focus: the app icon large and centred, its name, then the quote if this layout
            // carries one — read from `elements` rather than assumed, so the miniature can't go
            // on advertising the bare Focus after the layout gained a quote.
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(theme.badge)))
            Spacer(Modifier.height(6.dp))
            Bar(width = 32.dp, height = 4.dp, color = Color(theme.primaryText))
            Spacer(Modifier.height(3.dp))
            Bar(width = 18.dp, height = 3.dp, color = Color(theme.secondaryText))
            if (BlockArrangement.Element.QUOTE in option.elements) {
                // Narrower and centred, mirroring how small this layout keeps its quote.
                Spacer(Modifier.height(7.dp))
                Bar(width = 30.dp, height = 3.dp,
                    color = Color(theme.primaryText).copy(alpha = 0.8f))
                Spacer(Modifier.height(2.dp))
                Bar(width = 20.dp, height = 3.dp,
                    color = Color(theme.primaryText).copy(alpha = 0.8f))
            }
        } else {
            val hasNumber = BlockArrangement.Element.NUMBER in option.elements
            val hasQuote = BlockArrangement.Element.QUOTE in option.elements
            Bar(width = 20.dp, height = 3.dp, color = Color(theme.secondaryText))
            if (hasNumber) {
                Spacer(Modifier.height(if (hasQuote) 6.dp else 14.dp))
                Bar(width = if (hasQuote) 26.dp else 34.dp,
                    height = if (hasQuote) 13.dp else 18.dp,
                    color = Color(theme.primaryText))
                Spacer(Modifier.height(3.dp))
                Bar(width = 15.dp, height = 3.dp, color = Color(theme.accent))
            }
            if (hasQuote) {
                Spacer(Modifier.height(7.dp))
                Bar(width = 40.dp, height = 3.dp,
                    color = Color(theme.primaryText).copy(alpha = 0.8f))
                Spacer(Modifier.height(2.dp))
                Bar(width = 30.dp, height = 3.dp,
                    color = Color(theme.primaryText).copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.weight(1f))
        Bar(width = 40.dp, height = 8.dp, color = Color(theme.button), radius = 4.dp)
    }
}

@Composable
private fun ThemeRow(
    option: BlockThemes.BlockTheme,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp)
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) AppGradients.accent
                else SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                AppShapes.card,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemePreview(option)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                option.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(15.dp))
            }
        }
    }
}

/**
 * A miniature of the block screen, drawn from the theme's **own** colour values rather than from
 * a shipped screenshot. A picture would be a second copy of the design: retune a theme and the
 * preview would keep advertising the old one. This can only ever show what the theme actually is.
 */
@Composable
private fun ThemePreview(option: BlockThemes.BlockTheme) {
    val shape = RoundedCornerShape(10.dp)
    // Mirrors the real backdrops: Aurora is the accent gradient, the rest are flat fills close
    // enough at this size (the real Midnight/Paper glows are invisible in a 54dp thumbnail).
    val background = if (option.id == "aurora") AppGradients.accent
    else SolidColor(Color(option.backdropSolid()))
    Column(
        Modifier.size(width = 54.dp, height = 78.dp).clip(shape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
            .padding(7.dp),
    ) {
        // badge + kicker
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(option.badge)))
            Spacer(Modifier.width(3.dp))
            Bar(width = 18.dp, height = 3.dp, color = Color(option.secondaryText))
        }
        Spacer(Modifier.height(6.dp))
        // the big "minutes reclaimed" numeral
        Bar(width = 26.dp, height = 13.dp, color = Color(option.primaryText))
        Spacer(Modifier.height(3.dp))
        Bar(width = 15.dp, height = 3.dp, color = Color(option.accent))
        Spacer(Modifier.height(7.dp))
        // the quote
        Bar(width = 40.dp, height = 3.dp, color = Color(option.primaryText).copy(alpha = 0.8f))
        Spacer(Modifier.height(2.dp))
        Bar(width = 30.dp, height = 3.dp, color = Color(option.primaryText).copy(alpha = 0.8f))
        Spacer(Modifier.weight(1f))
        // "Got it"
        Bar(width = 40.dp, height = 8.dp, color = Color(option.button), radius = 4.dp)
    }
}

@Composable
private fun Bar(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp,
                color: Color, radius: androidx.compose.ui.unit.Dp = 2.dp) {
    Box(Modifier.width(width).height(height).clip(RoundedCornerShape(radius)).background(color))
}


@Composable
private fun ElementRow(
    element: BlockArrangement.Element,
    visible: Boolean,
    size: BlockArrangement.Size,
    quoteAlign: BlockArrangement.QuoteAlign,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
    onSize: (BlockArrangement.Size) -> Unit,
    onQuoteAlign: (BlockArrangement.QuoteAlign) -> Unit,
) {
  Column(
      Modifier.fillMaxWidth().padding(bottom = 10.dp)
          .clip(AppShapes.card)
          .background(MaterialTheme.colorScheme.surface)
          .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), AppShapes.card),
  ) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                element.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (visible) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                element.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Up/down rather than drag: reliable, reachable, and far less code than a drag
        // implementation that has to behave inside a LazyColumn.
        IconButton(onClick = { onMove(-1) }, enabled = enabled && canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = { onMove(1) }, enabled = enabled && canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
        }
        Switch(checked = visible, onCheckedChange = { onToggle() }, enabled = enabled)
    }
    // Size only matters for a piece that is actually on screen.
    if (visible) {
        // The quote has two chip rows, so both are named. Two unlabelled rows of three chips
        // give no clue which one does what.
        val quote = element == BlockArrangement.Element.QUOTE
        if (quote) ChipRowLabel("Size")
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BlockArrangement.Size.entries.forEach { option ->
                AlignChip(
                    label = option.label,
                    selected = size == option,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { onSize(option) }
            }
        }
        // Only the quote gets a side of its own. The screen-wide Alignment below moves the stack,
        // which the full-width quote never visibly follows — so this is the only control that
        // actually shifts it.
        if (quote) {
            ChipRowLabel("Side")
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BlockArrangement.QuoteAlign.entries.forEach { option ->
                    AlignChip(
                        label = option.label,
                        selected = quoteAlign == option,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { onQuoteAlign(option) }
                }
            }
        }
    }
  }
}

@Composable
private fun ChipRowLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun AlignChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(AppShapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                AppShapes.small,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The block screen itself, inflated and rendered by the very code the service uses
 * ([BlockScreenRenderer]), then scaled down to fit.
 *
 * Deliberately not a Compose mock-up of the block screen. A mock-up would be a second description
 * of the same design and would drift from the real one — which is the failure mode behind most of
 * this app's bugs, and would be especially cruel in a *preview*, whose entire job is to be
 * trustworthy. What is on screen here is the real layout with the real colours and the real
 * arrangement; only the text is stand-in.
 */
@Composable
private fun BlockScreenPreview(
    layoutId: String,
    themeId: String,
    arrangement: BlockArrangement.Arrangement,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val screenW = cfg.screenWidthDp.dp
    val screenH = cfg.screenHeightDp.dp
    val layoutRes = (BlockLayouts.OPTIONS.firstOrNull { it.id == layoutId }
        ?: BlockLayouts.OPTIONS.first()).layoutRes

    BoxWithConstraints(modifier) {
        val scale = maxWidth / screenW
        Box(
            Modifier.fillMaxWidth().height(screenH * scale)
                .clip(AppShapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), AppShapes.medium),
        ) {
            // key(): AndroidView's factory does not re-run on its own, so switching layout has to
            // recreate the view rather than try to re-render a different layout's widgets.
            key(layoutRes) {
                AndroidView(
                    factory = { ctx -> LayoutInflater.from(ctx).inflate(layoutRes, null) },
                    update = { v ->
                        // themeId/arrangement are read so this re-runs when either changes; the
                        // renderer itself reads the saved values, which the picker writes on tap.
                        @Suppress("UNUSED_EXPRESSION") themeId
                        @Suppress("UNUSED_EXPRESSION") arrangement
                        BlockScreenRenderer.applyTheme(context, v)
                        val a = BlockScreenRenderer.applyArrangement(context, v)
                        v.findViewById<TextView>(R.id.overlay_title)?.text = "Blocked"
                        v.findViewById<TextView>(R.id.overlay_subtitle)?.text =
                            "Instagram is blocked"
                        v.findViewById<TextView>(R.id.overlay_stat_number)?.text = "36"
                        v.findViewById<TextView>(R.id.overlay_quote)?.apply {
                            text = "You have power over your mind — not outside events."
                            // Mirrors BlockOverlay: the quote's length-based size, times this
                            // layout's own scale, times the owner's choice. Doing it here too is
                            // what keeps the preview honest — and this line is the one place the
                            // preview can silently drift from the real screen, since every other
                            // pixel comes from the shared renderer.
                            setTextSize(
                                TypedValue.COMPLEX_UNIT_SP,
                                Quotes.sizeSpFor(text.toString()) *
                                    BlockLayouts.byId(layoutId).quoteScale *
                                    a.factorFor(BlockArrangement.Element.QUOTE),
                            )
                        }
                        v.findViewById<TextView>(R.id.overlay_quote_author)?.text =
                            "— Marcus Aurelius"
                        v.findViewById<ImageView>(R.id.overlay_icon)
                            ?.setImageResource(AppIcons.current(context).previewRes)
                    },
                    modifier = Modifier.size(screenW, screenH).graphicsLayer {
                        scaleX = scale; scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                )
            }
        }
    }
}
