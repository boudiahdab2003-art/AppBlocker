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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.appblocker.service.ProtectionWatchdog
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.appBackground
import androidx.compose.ui.res.stringResource
import com.appblocker.R

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
    data object Keywords : Overlay
    data class EditTemplate(val template: Template) : Overlay
    data object Permissions : Overlay
    data object Onboarding : Overlay
    data object Account : Overlay
    data object CoachChat : Overlay
    data object Changelog : Overlay
    data object Instructions : Overlay
    data object Diagnostics : Overlay
    data object Repair : Overlay
    data object DetoxGuide : Overlay
    data object Scenarios : Overlay
    data object TwelveSteps : Overlay
    data object IconPicker : Overlay
    data object CleanCounter : Overlay
    /** [startDay] opens straight onto one day — the counter's "write about today". */
    data class Journal(val startDay: Int? = null) : Overlay
    data object BlockThemePicker : Overlay
    data class NewSchedule(val type: ScheduleType) : Overlay
    data class EditSchedule(val schedule: Schedule) : Overlay
}

@Composable
fun AppRoot(
    openPermissionsOnStart: Boolean = false,
    /** Tapping the standing "Blocking has stopped" alert. Separate from the permissions route
     *  because a stalled watcher has every permission it needs — see MainActivity.EXTRA_OPEN_REPAIR. */
    openRepairOnStart: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // The typed-paragraph gate, requested from a tab and drawn over the window. See the layer
    // below for why it cannot be drawn where it is asked for.
    var gate by remember { mutableStateOf<Pair<GateCopy, () -> Unit>?>(null) }
    // The accessibility disclosure, requested by whichever screen holds a "Grant" button and drawn
    // over the window for the same reason as the gate. Holds the action to run on agreement — for
    // every caller that is `Perm.onFix`, which opens Android's Accessibility settings.
    var disclosure by remember { mutableStateOf<(() -> Unit)?>(null) }
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

    // Tapping the "protection turned off" notification (cold start or, since MainActivity is
    // singleTask, a fresh onNewIntent while already running) routes straight here.
    LaunchedEffect(openPermissionsOnStart) {
        if (openPermissionsOnStart) overlay = Overlay.Permissions
    }

    // …and the "blocking has stopped" alert routes to the screen that can actually end it.
    LaunchedEffect(openRepairOnStart) {
        if (openRepairOnStart) overlay = Overlay.Repair
    }

    // Check whether the accessibility service got silently turned off on EVERY app open/resume,
    // no matter which tab is showing (resumeTick fires on ON_RESUME). This is the fast path that
    // posts/cancels the "protection turned off" notification the moment the user returns to the
    // app; the 15-min background worker is only the fallback when the app isn't opened at all.
    val protectionTick = resumeTick()
    LaunchedEffect(protectionTick) { ProtectionWatchdog.checkAndNotify(context, force = true) }

    // Coming back from the "allow install unknown apps" screen resumes an update that was
    // waiting on it. No-op unless one is waiting.
    LaunchedEffect(protectionTick) { updateVm.onResumed() }

    // System back closes an open editor overlay instead of exiting the app.
    BackHandler(enabled = overlay != null) { overlay = null }

    Box(Modifier.fillMaxSize().background(appBackground())) {
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
                BlockEditorScreen(
                    strictActive = strictActive,
                    onBack = { overlay = null },
                    onRequestDisclosure = { onAgree -> disclosure = onAgree },
                )
            is Overlay.Keywords ->
                KeywordsScreen(strictActive = strictActive, onBack = { overlay = null })
            is Overlay.EditTemplate ->
                TemplateEditorScreen(template = o.template, strictActive = strictActive,
                    onBack = { overlay = null })
            is Overlay.Permissions ->
                PermissionsScreen(
                    onBack = { overlay = null },
                    onRequestDisclosure = { onAgree -> disclosure = onAgree },
                )
            is Overlay.Onboarding ->
                OnboardingScreen(
                    onDone = {
                        SettingsStore.setSetupSeen(context)
                        overlay = null
                    },
                    onRequestDisclosure = { onAgree -> disclosure = onAgree },
                )
            is Overlay.Account ->
                AccountScreen(
                    onBack = { overlay = null },
                    // Replays the walkthrough only. "Setup seen" is cleared as well so that
                    // quitting part-way through leaves the wizard waiting on the next launch,
                    // exactly as it behaves on a first install — Overlay.Onboarding's onDone is
                    // what sets it again.
                    onRunSetupAgain = {
                        SettingsStore.clearSetupSeen(context)
                        overlay = Overlay.Onboarding
                    },
                )
            is Overlay.CoachChat ->
                CoachChatScreen(onBack = { overlay = null })
            // Wrapped in EnglishOnly: these six are the long-form reading material the owner
            // chose to leave in English, and English text inside a right-to-left layout puts its
            // punctuation at the wrong end. See [EnglishOnly].
            is Overlay.Changelog ->
                EnglishOnly { ChangelogScreen(onBack = { overlay = null }) }
            is Overlay.Instructions ->
                EnglishOnly { InstructionsScreen(onBack = { overlay = null }) }
            is Overlay.Diagnostics ->
                EnglishOnly { DiagnosticsScreen(onBack = { overlay = null }) }
            is Overlay.Repair ->
                RepairScreen(onBack = { overlay = null })
            is Overlay.DetoxGuide ->
                EnglishOnly { DopamineDetoxScreen(onBack = { overlay = null }) }
            is Overlay.Scenarios ->
                EnglishOnly { ScenariosScreen(onBack = { overlay = null }) }
            is Overlay.TwelveSteps ->
                EnglishOnly { TwelveStepsScreen(onBack = { overlay = null }) }
            is Overlay.IconPicker ->
                IconPickerScreen(onBack = { overlay = null })
            is Overlay.CleanCounter ->
                CleanCounterScreen(
                    onBack = { overlay = null },
                    onOpenJournal = { day -> overlay = Overlay.Journal(day) },
                )
            is Overlay.Journal ->
                JournalScreen(onBack = { overlay = null }, startDay = o.startDay)
            is Overlay.BlockThemePicker ->
                BlockThemePickerScreen(
                    strictActive = strictActive, onBack = { overlay = null })
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
                onOpenKeywords = { overlay = Overlay.Keywords },
                onEditTemplate = { overlay = Overlay.EditTemplate(it) },
                onNewSchedule = { overlay = Overlay.NewSchedule(it) },
                onEditSchedule = { overlay = Overlay.EditSchedule(it) },
                onOpenPermissions = { overlay = Overlay.Permissions },
                onOpenAccount = { overlay = Overlay.Account },
                onOpenCoach = { overlay = Overlay.CoachChat },
                onOpenChangelog = { overlay = Overlay.Changelog },
                onOpenInstructions = { overlay = Overlay.Instructions },
                onOpenDiagnostics = { overlay = Overlay.Diagnostics },
                onOpenRepair = { overlay = Overlay.Repair },
                onOpenDetox = { overlay = Overlay.DetoxGuide },
                onOpenScenarios = { overlay = Overlay.Scenarios },
                onOpenSteps = { overlay = Overlay.TwelveSteps },
                onOpenIconPicker = { overlay = Overlay.IconPicker },
                onOpenBlockThemePicker = { overlay = Overlay.BlockThemePicker },
                onOpenCounter = { overlay = Overlay.CleanCounter },
                onOpenJournal = { overlay = Overlay.Journal() },
                onRequestGate = { copy, confirm -> gate = copy to confirm },
            )
        }
    }

    // **The typed gate is drawn here, over everything, and not where it is asked for.**
    //
    // [FrictionGate] divides the space it is handed between the paragraph, the field and the
    // button, so it has to be handed the window. Profile asked for it from inside the scaffold,
    // where "fill the screen" means the tab content area: the bottom tab bar stayed visible
    // underneath it, and the keyboard's height came off twice — once in the padding the scaffold
    // hands down (which `Modifier.padding` does not consume) and again in the gate's own
    // `safeDrawingPadding`. The field ended up below the fold with the keyboard open, which is the
    // bug five rewrites inside FrictionGate.kt could not reach.
    //
    // A layer rather than an [Overlay]: an overlay would unmount MainScaffold, and Profile's
    // device-admin badge is `remember(resumeTick)` — rebuilt on return by re-reading a state that
    // `removeActiveAdmin` has not finished changing yet, which is exactly what its `adminOn =
    // false` exists to avoid. Keeping the scaffold composed underneath keeps that behaviour.
    //
    // The clickable is a tap sink, not a button: it catches taps that would otherwise fall through
    // to the tab bar now hidden behind the gate. Children are hit first, so the field and the
    // buttons inside are unaffected.
    // The accessibility disclosure, drawn here for the same reason as the gate below: it is asked
    // for by a Grant button that lives inside a scrolling column, and a full-screen page emitted
    // from inside one does not lay out correctly. It is drawn AFTER the AnimatedContent, so it
    // covers the overlays (Permissions, Onboarding) that request it — which is also why it cannot
    // itself be an Overlay: there is only one of those at a time.
    disclosure?.let { onAgree ->
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
        ) {
            AccessibilityDisclosureScreen(
                onAgree = { disclosure = null; onAgree() },
                onDecline = { disclosure = null },
            )
        }
    }

    gate?.let { (copy, confirm) ->
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
        ) {
            FrictionGate(
                title = copy.title,
                blurb = copy.blurb,
                detail = copy.detail,
                confirmLabel = copy.confirmLabel,
                onDismiss = { gate = null },
                onConfirm = { confirm(); gate = null },
            )
        }
    }

    // Big, unmissable prompt when a newer version is found — shown ONCE per launch (the
    // launch check feeds it). Manual checks from Profile only update that row, no popup.
    updatePrompt?.let { release ->
        AlertDialog(
            onDismissRequest = { updateVm.dismissPrompt() },
            title = { Text(stringResource(R.string.update_available)) },
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
                ) { Text(stringResource(R.string.update_now)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { updateVm.dismissPrompt() }
                ) { Text(stringResource(R.string.update_later)) }
            },
        )
    }

    // Global download progress while an update is being fetched.
    (updateState as? UpdateState.Downloading)?.let { dl ->
        AlertDialog(
            // Not dismissable by tapping away — an accidental tap shouldn't abandon a download.
            // Cancel is explicit, and it has to exist: this dialog covers the screen, so without
            // it a stalled transfer leaves nothing to tap.
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = { updateVm.cancelDownload() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = { Text(stringResource(R.string.update_downloading)) },
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
    onOpenKeywords: () -> Unit,
    onEditTemplate: (Template) -> Unit,
    onNewSchedule: (ScheduleType) -> Unit,
    onEditSchedule: (Schedule) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenCoach: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenInstructions: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRepair: () -> Unit,
    onOpenDetox: () -> Unit,
    onOpenScenarios: () -> Unit,
    onOpenSteps: () -> Unit,
    onOpenIconPicker: () -> Unit,
    onOpenBlockThemePicker: () -> Unit,
    onOpenCounter: () -> Unit,
    onOpenJournal: () -> Unit,
    /** Ask for the typed gate. It is drawn by AppRoot, over the window — not here; see the layer
     *  in [AppRoot] and the window note in [FrictionGate]. */
    onRequestGate: (GateCopy, () -> Unit) -> Unit,
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
                    onOpenKeywords = onOpenKeywords,
                    onEditTemplate = onEditTemplate,
                    onNewSchedule = onNewSchedule,
                    onEditSchedule = onEditSchedule,
                    onOpenPermissions = onOpenPermissions,
                    onOpenRepair = onOpenRepair,
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
                    onOpenAccount = onOpenAccount,
                    onOpenChangelog = onOpenChangelog,
                    onOpenInstructions = onOpenInstructions,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenRepair = onOpenRepair,
                    onOpenDetox = onOpenDetox,
                    onOpenScenarios = onOpenScenarios,
                    onOpenSteps = onOpenSteps,
                    onOpenIconPicker = onOpenIconPicker,
                    onOpenBlockThemePicker = onOpenBlockThemePicker,
                    onOpenCounter = onOpenCounter,
                    onOpenJournal = onOpenJournal,
                    onRequestGate = onRequestGate,
                    updateVm = updateVm,
                )
            }
            }
        }
    }
}
