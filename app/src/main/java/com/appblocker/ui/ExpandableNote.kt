package com.appblocker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.R
import com.appblocker.ui.theme.AppCard
import com.appblocker.ui.theme.Space

/**
 * A card that shows its heading and hides its explanation until asked.
 *
 * ## Why this exists
 *
 * *"sometimes when the app guides you to the accessibility there is a lot of talk a lot of unneeded
 * one can we make it less"* — 30 Aug 2026. He was right, and the numbers were worse than they
 * looked: the repair screen ran to about 460 words, roughly **200 of them above the one button
 * that fixes the problem**, on the screen someone reaches precisely when they want blocking back
 * in seconds. Three of those blocks were also being shown to him twice, on two different screens.
 *
 * He chose "short everywhere, details on tap" over deleting the text. That distinction is the
 * whole design: the explanations are good, they are just not what someone in a hurry needs in
 * front of them. Collapsed, the screen becomes a list of headings and one button; expanded, nothing
 * has been lost.
 *
 * ## Why not [CollapsibleHeader]
 *
 * That one is a section header for editor lists — a bold `titleLarge` with a count badge, sized to
 * introduce a list of rows. This is a paragraph hiding inside a card. They share the chevron idiom
 * and the `expand` / `collapse` strings so the gesture reads the same, and nothing else.
 *
 * [startExpanded] exists for the one case where the text is the point rather than the footnote.
 * The state is [rememberSaveable], so opening one and rotating, or coming back from Settings,
 * does not silently close it again.
 */
@Composable
fun ExpandableNote(
    title: String,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
    /** Drawn below the heading and *above* the fold — for a button that must stay reachable
     *  whether or not the explanation is open. */
    alwaysVisible: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(startExpanded) }
    AppCard(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(Space.sm))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.collapse else R.string.expand,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        alwaysVisible?.let {
            Spacer(Modifier.height(Space.md))
            it()
        }
        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(Space.sm))
                body()
            }
        }
    }
}
