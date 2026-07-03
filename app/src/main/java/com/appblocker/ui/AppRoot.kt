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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    data object Onboarding : Overlay
    data object CoachChat : Overlay
    data object Changelog : Overlay
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
    val updatePrompt by updateVm.prompt.collectAsState()
    val context = LocalContext.current

    // First launch: walk the user through the step-by-step setup wizard. "Setup seen" is only
    // persisted once they finish/skip the wizard (see Overlay.Onboarding's onDone), so quitting
    // mid-setup re-shows it next launch instead of stranding the user.
    LaunchedEffect(Unit) {
        if (!SettingsStore.setupSeen(context)) overlay = Overlay.Onboarding
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
            is Overlay.Onboarding ->
                OnboardingScreen(onDone = {
                    SettingsStore.setSetupSeen(context)
                    overlay = null
                })
            is Overlay.CoachChat ->
                CoachChatScreen(onBack = { overlay = null })
            is Overlay.Changelog ->
                ChangelogScreen(onBack = { overlay = null })
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
                onOpenCoach = { overlay = Overlay.CoachChat },
                onOpenChangelog = { overlay = Overlay.Changelog },
            )
        }
    }

    // Big, unmissable prompt when a newer version is found — shown ONCE per launch (the
    // launch check feeds it). Manual checks from Profile only update that row, no popup.
    updatePrompt?.let { release ->
        AlertDialog(
            onDismissRequest = { updateVm.dismissPrompt() },
            title = { Text("Update available") },
            text = {
                Text(
                    "Version ${release.version} is ready." +
                        if (release.notes.isNotBlank()) "\n\n${release.notes}" else ""
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateVm.dismissPrompt()
                        updateVm.downloadAndInstall(release)
                    }
                ) { Text("Update now") }
            },
            dismissButton = {
                TextButton(
                    onClick = { updateVm.dismissPrompt() }
                ) { Text("Later") }
            },
        )
    }

    // Global download progress while an update is being fetched.
    (updateState as? UpdateState.Downloading)?.let { dl ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Downloading update") },
            text = { Text("${dl.percent}%") },
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
    onOpenCoach: () -> Unit,
    onOpenChangelog: () -> Unit,
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
                2 -> InsightsScreen(
                    onOpenCoach = onOpenCoach,
                    onNewGoalSchedule = { onNewSchedule(ScheduleType.USAGE_LIMIT) },
                )
                else -> ProfileScreen(
                    strictActive = strictActive,
                    onOpenPermissions = onOpenPermissions,
                    onOpenChangelog = onOpenChangelog,
                    updateVm = updateVm,
                )
            }
            }
        }
    }
}
