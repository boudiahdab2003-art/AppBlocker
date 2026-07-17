package com.appblocker.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblocker.data.AppIcons
import com.appblocker.ui.theme.AppGradients

/** Profile ▸ App icon: full-page chooser. Tapping a tile switches the launcher icon (and the
 *  block screen, which mirrors it); you can try several before leaving. */
@Composable
fun IconPickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppIcons.current(context)) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "App icon", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(
                    "Pick the icon AppBlocker wears on your home screen. The block screen " +
                        "matches your choice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            items(AppIcons.OPTIONS.chunked(2).size) { rowIndex ->
                val rowOptions = AppIcons.OPTIONS.chunked(2)[rowIndex]
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    rowOptions.forEach { option ->
                        IconTile(
                            option = option,
                            selected = option.id == selected.id,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                AppIcons.apply(context, option)
                                selected = option
                                Toast.makeText(
                                    context,
                                    "Icon changed — your home screen may take a moment to refresh",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                    // Keep the last row aligned when it has a single tile.
                    if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { Spacer(Modifier.padding(top = 14.dp)) }
        }
    }
}

@Composable
private fun IconTile(
    option: AppIcons.IconOption,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) AppGradients.accent
                else androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Image(
                painter = painterResource(option.previewRes),
                contentDescription = option.label,
                modifier = Modifier.size(72.dp).clip(CircleShape),
            )
            if (selected) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(15.dp))
                }
            }
        }
        Text(
            option.label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
