package com.appblocker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One "create a schedule" tile. [modifier] carries the width — a fixed 116dp on phones
 *  (scrolling row) or weight(1f) on wide screens where the five tiles share the full row. */
@Composable
internal fun ScheduleTile(
    modifier: Modifier,
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Grow the tile with the system font size so two-line labels ("Usage limit", "Launch
    // count") never clip on phones set to a larger font. Padding + icon are dp (not font-scaled),
    // so only the text needs the extra room; all tiles share the value, staying uniform.
    val fontScale = LocalDensity.current.fontScale
    val tileHeight = (134f + ((fontScale - 1f).coerceIn(0f, 1.2f) * 48f)).dp
    Column(
        modifier
            .height(tileHeight)
            .softGlow(RoundedCornerShape(20.dp), elevation = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(20.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            // Slim side padding: at larger system fonts a one-word label ("Location") needs
            // the extra width or it breaks mid-word ("Locatio/n").
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Icon in a soft circle with a small "+" badge — reads as "tap to create".
        Box {
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp))
            }
            Box(
                Modifier.size(18.dp).align(Alignment.TopEnd).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.padding(top = 10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * One saved schedule: what it does, whether it's running, and — by swiping it left — a way to
 * delete it.
 *
 * **Why the bin icon is gone.** It sat between the summary and the switch, and at the owner's
 * system font size that left the summary about half the row: "9:00 AM – 12:00 PM · Every day ·
 * 8 apps" wrapped onto three lines, breaking after the separators, while a grey bin crowded the
 * one control anyone actually uses. Moving delete onto a swipe gives the width back to the text,
 * which is the thing being read.
 *
 * **The swipe still confirms.** A gesture is much easier to make by accident than a deliberate
 * tap on a bin, and a deleted schedule cannot be undone — so passing the threshold slides the
 * card away and asks, and cancelling slides it back. The gesture replaces the button, not the
 * confirmation.
 *
 * **And it is off during Strict**, exactly as the bin was. Deleting a schedule weakens blocking,
 * and a new gesture that could do it during a Strict session would be a new door in the one mode
 * whose whole point is that there isn't one.
 */
@Composable
internal fun ScheduleCard(
    schedule: Schedule,
    strictActive: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    // During Strict you may turn a schedule ON (strengthen) but not OFF; the card still opens
    // (the editor itself stays read-only for an existing schedule during Strict).
    val toggleEnabled = !strictActive || !schedule.enabled
    val canDelete = onDelete != null && !strictActive
    var confirmDelete by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxWidth()) {
        // The backdrop the swipe reveals. Sized to the card rather than given a height of its
        // own, so it can never be the thing that decides how tall the row is.
        Box(
            Modifier.matchParentSize().clip(shape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(
                Modifier.padding(end = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Delete, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Delete", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .offset { IntOffset(slide.value.roundToInt(), 0) }
                .height(IntrinsicSize.Min)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
                .clickable(onClick = onClick)
                .pointerInput(canDelete) {
                    if (!canDelete) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                // A third of the row: far enough that a stray sideways nudge
                                // during a scroll doesn't reach it, near enough that deleting
                                // doesn't need a full-width drag.
                                if (-slide.value > size.width / 3f) {
                                    slide.animateTo(-size.width.toFloat(), tween(160))
                                    confirmDelete = true
                                } else {
                                    slide.animateTo(0f, tween(160))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { slide.animateTo(0f, tween(160)) } },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            scope.launch {
                                // Left only, and never past the card's own width. Swiping right
                                // does nothing: there is nothing on that side to reveal, and a
                                // card that slides off its own list looks like a bug.
                                slide.snapTo(
                                    (slide.value + delta).coerceIn(-size.width.toFloat(), 0f),
                                )
                            }
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Full-height accent rail. The switch already states on/off, but it is one small
            // control at the far end of a row read left-to-right; this makes the answer the first
            // thing on the card rather than the last.
            Box(
                Modifier.fillMaxHeight().width(5.dp).background(
                    if (schedule.enabled) AppGradients.accentVertical
                    else SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                ),
            )
            Row(
                Modifier.weight(1f).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    // 10dp, matching every other 40dp icon tile in the app (the app rounds icons
                    // at about a quarter of their size).
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(
                        if (schedule.enabled) AppGradients.accentVertical
                        else SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        scheduleIcon(schedule.type), contentDescription = null,
                        tint = if (schedule.enabled) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                val (headline, detail) = scheduleDetail(schedule)
                Column(Modifier.weight(1f)) {
                    Text(
                        schedule.name, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (schedule.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Two deliberate lines rather than one long string left to wrap where it
                    // likes. The old single summary broke after a "·" at the owner's font size,
                    // stranding "PM ·" and "8 apps" on lines of their own.
                    Text(
                        headline, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = schedule.enabled,
                    enabled = toggleEnabled,
                    onCheckedChange = onToggle,
                )
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = {
                confirmDelete = false
                scope.launch { slide.animateTo(0f, tween(160)) }
            },
            title = { Text("Delete this schedule?") },
            text = { Text("“${schedule.name}” — ${scheduleSummary(schedule)}. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // Put the card back. Without this it would sit swiped-open with no way to
                    // close it — the cancel would have looked like it did nothing.
                    scope.launch { slide.animateTo(0f, tween(160)) }
                }) { Text("Cancel") }
            },
        )
    }
}

private fun scheduleIcon(type: ScheduleType): ImageVector = when (type) {
    ScheduleType.TIME -> Icons.Filled.Schedule
    ScheduleType.USAGE_LIMIT -> Icons.Filled.HourglassEmpty
    ScheduleType.LAUNCH_COUNT -> Icons.AutoMirrored.Filled.OpenInNew
    ScheduleType.WIFI -> Icons.Filled.Wifi
    ScheduleType.LOCATION -> Icons.Filled.LocationOn
}

/**
 * A schedule in two lines: **what triggers it**, then the qualifiers.
 *
 * One string was fine until a large system font turned it into three ragged lines that broke
 * after the "·" separators. Splitting it at the point the sentence already divides means the
 * important half — the time window, the limit, the place — gets a line of its own and stays
 * readable at any font size.
 */
private fun scheduleDetail(s: Schedule): Pair<String, String> {
    val apps = "${s.packages.size} app${if (s.packages.size == 1) "" else "s"}"
    return when (s.type) {
        ScheduleType.TIME ->
            fmtWindow(s.startMinutes, s.endMinutes) to "${daysText(s.daysMask)} · $apps"
        ScheduleType.USAGE_LIMIT -> "${fmtDuration(s.limitMinutes)}/day" to apps
        ScheduleType.LAUNCH_COUNT -> "${s.limitCount} opens/day" to apps
        ScheduleType.WIFI ->
            (if (s.wifiSsid.isBlank()) "Any Wi-Fi" else "Wi-Fi: ${s.wifiSsid}") to apps
        ScheduleType.LOCATION -> "Within ${s.radiusMeters} m" to apps
    }
}

/** The same facts on one line, for the delete confirmation — where it is a phrase inside a
 *  sentence rather than a layout. */
private fun scheduleSummary(s: Schedule): String =
    scheduleDetail(s).let { (headline, detail) -> "$headline · $detail" }

