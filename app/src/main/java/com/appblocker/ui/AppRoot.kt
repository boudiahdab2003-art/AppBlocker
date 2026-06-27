package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SettingsStore
import com.appblocker.service.AccessibilityUtil
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
    data object Permissions : Overlay
    data class NewSchedule(val type: ScheduleType) : Overlay
    data class EditSchedule(val schedule: Schedule) : Overlay
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val focusVm: FocusViewModel = viewModel()
    val strictActive by focusVm.isActive.collectAsState()
    val updateVm: UpdateViewModel = viewModel()
    val updateState by updateVm.state.collectAsState()
    val context = LocalContext.current

    // First launch: if the blocker isn't on yet, guide the user through setup once.
    LaunchedEffect(Unit) {
        if (!SettingsStore.setupSeen(context) && !AccessibilityUtil.isEnabled(context)) {
            overlay = Overlay.Permissions
        }
        SettingsStore.setSetupSeen(context)
        updateVm.checkOnLaunch()
    }

    // System back closes an open editor overlay instead of exiting the app.
    BackHandler(enabled = overlay != null) { overlay = null }

    Box(Modifier.fillMaxSize().background(AppGradients.background)) {
    // Editor overlays slide up over the current tab; the main scaffold cross-fades back in.
    AnimatedContent(
        targetState = overlay,
        transitionSpec = {
            if (targetState != null) {
                (slideInVertically { it } + fadeIn()) togetherWith fadeOut()
            } else {
                fadeIn() togetherWith (slideOutVertically { it } + fadeOut())
            }
        },
        label = "overlay",
    ) { o ->
        when (o) {
            is Overlay.QuickBlock ->
                BlockEditorScreen(strictActive = strictActive, onBack = { overlay = null })
            is Overlay.Permissions ->
                PermissionsScreen(onBack = { overlay = null })
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
                updateVm = updateVm,
                onEditQuickBlock = { overlay = Overlay.QuickBlock },
                onNewSchedule = { overlay = Overlay.NewSchedule(it) },
                onEditSchedule = { overlay = Overlay.EditSchedule(it) },
                onOpenPermissions = { overlay = Overlay.Permissions },
            )
        }
    }

    // Global download progress while an update is being fetched.
    (updateState as? UpdateState.Downloading)?.let { dl ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { androidx.compose.material3.Text("Downloading update") },
            text = { androidx.compose.material3.Text("${dl.percent}%") },
        )
    }
    }
}

@Composable
private fun MainScaffold(
    tab: Int,
    onTab: (Int) -> Unit,
    strictActive: Boolean,
    updateVm: UpdateViewModel,
    onEditQuickBlock: () -> Unit,
    onNewSchedule: (ScheduleType) -> Unit,
    onEditSchedule: (Schedule) -> Unit,
    onOpenPermissions: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color.Transparent) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = {
                            Text(
                                t.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
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
        // Preserve each tab's state (sub-tab choice, scroll position) across switches; without
        // this the off-screen tab is disposed and its rememberSaveable state is lost.
        val stateHolder = rememberSaveableStateHolder()
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.padding(padding),
            transitionSpec = {
                val forward = targetState > initialState
                val dir = if (forward) 1 else -1
                (slideInHorizontally { it * dir / 6 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it * dir / 6 } + fadeOut())
            },
            label = "tab",
        ) { current ->
            stateHolder.SaveableStateProvider(current) {
            when (current) {
                0 -> BlockingScreen(
                    onEditQuickBlock = onEditQuickBlock,
                    onNewSchedule = onNewSchedule,
                    onEditSchedule = onEditSchedule,
                    onOpenPermissions = onOpenPermissions,
                    updateVm = updateVm,
                )
                1 -> StrictModeScreen()
                2 -> InsightsScreen()
                else -> ProfileScreen(
                    strictActive = strictActive,
                    onOpenPermissions = onOpenPermissions,
                    updateVm = updateVm,
                )
            }
            }
        }
    }
}
