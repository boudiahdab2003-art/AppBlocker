package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.ui.theme.AppGradients

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Blocking", Icons.Filled.Shield),
    Tab("Strict", Icons.Filled.Lock),
    Tab("Insights", Icons.Filled.BarChart),
    Tab("Profile", Icons.Filled.Person),
)

/** Editor sub-screens shown full-screen over the current tab. */
private sealed interface Overlay {
    data object QuickBlock : Overlay
    data class NewSchedule(val type: ScheduleType) : Overlay
    data class EditSchedule(val schedule: Schedule) : Overlay
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val focusVm: FocusViewModel = viewModel()
    val strictActive by focusVm.isActive.collectAsState()

    // System back closes an open editor overlay instead of exiting the app.
    BackHandler(enabled = overlay != null) { overlay = null }

    Box(Modifier.fillMaxSize().background(AppGradients.background)) {
    when (val o = overlay) {
        is Overlay.QuickBlock ->
            BlockEditorScreen(strictActive = strictActive, onBack = { overlay = null })
        is Overlay.NewSchedule ->
            ScheduleEditorScreen(
                type = o.type, existing = null, strictActive = strictActive,
                onBack = { overlay = null },
            )
        is Overlay.EditSchedule ->
            ScheduleEditorScreen(
                type = o.schedule.type, existing = o.schedule, strictActive = strictActive,
                onBack = { overlay = null },
            )
        null -> MainScaffold(
            tab = tab,
            onTab = { tab = it },
            strictActive = strictActive,
            onEditQuickBlock = { overlay = Overlay.QuickBlock },
            onNewSchedule = { overlay = Overlay.NewSchedule(it) },
            onEditSchedule = { overlay = Overlay.EditSchedule(it) },
        )
    }
    }
}

@Composable
private fun MainScaffold(
    tab: Int,
    onTab: (Int) -> Unit,
    strictActive: Boolean,
    onEditQuickBlock: () -> Unit,
    onNewSchedule: (ScheduleType) -> Unit,
    onEditSchedule: (Schedule) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color.Transparent) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> BlockingScreen(
                    onEditQuickBlock = onEditQuickBlock,
                    onNewSchedule = onNewSchedule,
                    onEditSchedule = onEditSchedule,
                )
                1 -> StrictModeScreen()
                2 -> InsightsScreen()
                else -> ProfileScreen(strictActive = strictActive)
            }
        }
    }
}
