package com.appblocker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * A single snap-to-row scroll wheel (AppBlock "Set the timer" style). The value whose row sits in
 * the centered highlight band is the selection; neighbours fade with distance.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 44.dp,
    visibleCount: Int = 7,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val fling = rememberSnapFlingBehavior(state)
    val pad = itemHeight * (visibleCount / 2)

    val centerIndex by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - mid) }?.index
                ?: selectedIndex
        }
    }
    val haptic = LocalHapticFeedback.current
    var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(centerIndex) {
        onSelectedChange(centerIndex)
        // Light tick as each row snaps under the centre band (skips the initial value).
        if (centerIndex != lastHapticIndex) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastHapticIndex = centerIndex
        }
    }

    LazyColumn(
        modifier.height(itemHeight * visibleCount),
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = pad),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(items) { i, label ->
            val dist = abs(i - centerIndex)
            val alpha = when (dist) { 0 -> 1f; 1 -> 0.5f; 2 -> 0.28f; else -> 0.16f }
            Box(Modifier.height(itemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    style = if (dist == 0) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = if (dist == 0) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Three wheels (days / hours / min) with their unit labels pinned in the centre band, behind a
 * single rounded highlight. Emits total minutes = days*1440 + hours*60 + min.
 */
@Composable
fun DurationWheel(initialMinutes: Int, onChange: (Int) -> Unit) {
    val itemHeight = 44.dp
    val visibleCount = 7
    var days by rememberSaveable { mutableIntStateOf(initialMinutes / 1440) }
    var hours by rememberSaveable { mutableIntStateOf((initialMinutes % 1440) / 60) }
    var mins by rememberSaveable { mutableIntStateOf(initialMinutes % 60) }

    LaunchedEffect(days, hours, mins) { onChange(days * 1440 + hours * 60 + mins) }

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Centre highlight band.
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(itemHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn("days", (0..30).map { it.toString() }, days, { days = it }, itemHeight, visibleCount)
            WheelColumn("hours", (0..23).map { it.toString() }, hours, { hours = it }, itemHeight, visibleCount)
            WheelColumn("min", (0..59).map { it.toString() }, mins, { mins = it }, itemHeight, visibleCount)
        }
    }
}

/** A number wheel plus its unit label, the label pinned level with the centre row. */
@Composable
private fun WheelColumn(
    unit: String,
    values: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    itemHeight: Dp,
    visibleCount: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WheelPicker(
            values, selected, onSelected,
            modifier = Modifier.width(48.dp), itemHeight = itemHeight, visibleCount = visibleCount,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            unit,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
    }
}

/** Full-screen "Set the timer" picker: wheel + live "Ends …" preview + Save. */
@Composable
fun DurationPickerDialog(
    title: String,
    initialMinutes: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // The dialog's own window reports ZERO insets on some OEMs (HyperOS) even though it is
    // drawn edge-to-edge, so inset modifiers INSIDE the dialog are no-ops — that's why the
    // Save button kept landing in the gesture zone across several fixes. Capture the real
    // insets here, in the ACTIVITY window's composition scope (always correct), and apply
    // them manually inside the dialog.
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var minutes by remember { mutableIntStateOf(initialMinutes) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                modifier = Modifier.padding(safeInsets),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0),
                topBar = { EditorTopBar(title, onBack = onDismiss) },
                // The activity-window insets above already clear the nav bar; the 24dp is
                // extra comfort above the gesture area. (Scaffold never insets bottomBar.)
                bottomBar = {
                    GradientButton(
                        text = "Save",
                        enabled = minutes > 0,
                        onClick = { onSave(minutes); onDismiss() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 8.dp, bottom = 24.dp),
                    )
                },
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(24.dp))
                    DurationWheel(initialMinutes) { minutes = it }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (minutes <= 0) "Choose a duration." else "Ends ${endsAtText(minutes)}.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun endsAtText(minutes: Int): String {
    val end = Date(System.currentTimeMillis() + minutes * 60_000L)
    return SimpleDateFormat("M/d, h:mm a", Locale.getDefault()).format(end)
}
