package com.appblocker.ui

import android.os.Build
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appblocker.Dist
import com.appblocker.R
import com.appblocker.data.DeviceVendor
import com.appblocker.data.DisplayName
import com.appblocker.data.SettingsStore
import com.appblocker.data.SetupGuides
import com.appblocker.service.SetupStepsNotifier
import com.appblocker.ui.theme.AppShapes
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.appBackground

/** Test tags for the rendering test — the name field and the way onward from it. */
const val ONBOARDING_NAME_FIELD_TAG = "onboarding_name_field"
const val ONBOARDING_NAME_CONTINUE_TAG = "onboarding_name_continue"

/**
 * First-run wizard: walks the user through the essential permissions one step at a time, then the
 * recommended optional ones, with a progress indicator. Reuses [rememberPermissions] so each step's
 * granted-state is live (re-checked on resume after the user returns from Settings). Calls [onDone]
 * when the user reaches the end (Finish) or skips out — AppRoot owns persisting "setup seen".
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    /** Ask AppRoot for [AccessibilityDisclosureScreen] — see [gatedFix]. */
    onRequestDisclosure: (() -> Unit) -> Unit = {},
) {
    val perms = rememberPermissions()
    val essentials = perms.filter { it.essential }
    // "autostart" joins the recommended rows rather than adding a step: on Xiaomi, Samsung, Oppo
    // and the rest, the OEM's own battery manager is what switches blocking off days later, and
    // this wizard is the only time most people will read setup advice. It is one more row on a
    // step that already scrolls, and on a stock-Android phone it reads as harmless belt-and-braces.
    val recommended = perms.filter {
        it.key == "usage" || it.key == "battery" || it.key == "autostart"
    }

    // Layout: Welcome, Name, AI Coach intro, one step per essential permission, the recommended
    // ones, Done. Named rather than written out as arithmetic at each use — the offsets were
    // spelled `essentials[step - 2]` and `essentials.size + 2` in three places, which is one
    // inserted step away from an off-by-one that lands on the wrong screen.
    val welcomeStep = 0
    val nameStep = 1
    val coachStep = 2
    val firstEssentialStep = 3
    val lastEssentialStep = firstEssentialStep + essentials.size - 1
    val recommendedStep = lastEssentialStep + 1
    val doneStep = recommendedStep + 1
    val totalSteps = doneStep // steps shown in the progress header (Welcome excluded)

    var step by rememberSaveable { mutableIntStateOf(0) }

    // In-wizard back walks to the previous step; at the Welcome step let the host handle back.
    BackHandler(enabled = step > 0) { step-- }

    // background BEFORE safeDrawingPadding so the app color still paints behind the system bars
    // (targetSdk 35 = forced edge-to-edge on Android 15+); padding is 0 on older devices.
    Box(Modifier.fillMaxSize().background(appBackground()).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            if (step > 0) {
                ProgressHeader(current = step, total = totalSteps)
                Spacer(Modifier.height(28.dp))
            } else {
                Spacer(Modifier.height(48.dp))
            }

            when {
                step == welcomeStep -> WelcomeStep(onNext = { step++ })
                step == nameStep -> NameStep(onNext = { step++ })
                step == coachStep -> CoachStep(onNext = { step++ })
                step <= lastEssentialStep -> EssentialStep(
                    perm = essentials[step - firstEssentialStep],
                    onContinue = { step++ },
                    onSkip = { step = doneStep },
                    onRequestDisclosure = onRequestDisclosure,
                )
                step == recommendedStep -> RecommendedStep(
                    perms = recommended,
                    onContinue = { step++ },
                    onRequestDisclosure = onRequestDisclosure,
                )
                else -> DoneStep(
                    grantedEssentials = essentials.count { it.granted },
                    totalEssentials = essentials.size,
                    onFinish = onDone,
                )
            }
        }
    }
}

@Composable
private fun ProgressHeader(current: Int, total: Int) {
    Column {
        LinearProgressIndicator(
            progress = { current.toFloat() / total.toFloat() },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(6.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_step, current.toString(), total.toString()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shared step layout: the step's content scrolls, the action button(s) stay pinned below it —
 *  so the button is always reachable even when a large font/display size makes the content
 *  taller than the screen (a weight-spacer-to-bottom layout left the button off-screen there,
 *  hard-stuck with nothing to press). */
@Composable
private fun StepScaffold(
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            footer()
        }
    }
}

/**
 * Big circular icon used at the top of each step.
 *
 * [size] exists because the permission steps cannot afford it at full height: they carry the
 * picture guide, and at 96dp the decoration pushed the first instruction below the fold on an
 * ordinary phone — so the person who does not scroll saw a button and nothing to press it about.
 */
@Composable
private fun StepIcon(icon: ImageVector, granted: Boolean = false, size: Dp = 96.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(
                if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (granted) Icons.Filled.Check else icon,
            contentDescription = null,
            tint = if (granted) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size / 2),
        )
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepScaffold(
        footer = {
            GradientButton(text = stringResource(R.string.onboarding_get_started), onClick = onNext)
        },
    ) {
        Spacer(Modifier.height(24.dp))
        StepIcon(Icons.Filled.Shield)
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * "What should we call you?" — the one question the app asks about the person using it.
 *
 * It is here, second, because until v1.120 there was no question at all: the display name
 * defaulted to the original owner's, so anyone else who installed AppBlocker was greeted by a
 * stranger's name and had no idea where to change it.
 *
 * **Skippable on purpose.** A blocker that demands personal details before it will block anything
 * loses the person it was meant to help, and nothing in the app needs a name to work — leave it
 * empty and the app says "You" ([DisplayName]). The field is saved on Continue rather than on
 * every keystroke so a half-typed name isn't what gets stored if they back out.
 */
@Composable
private fun NameStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(SettingsStore.userName(context)) }
    val typed = DisplayName.sanitize(text)

    fun saveAndGo() {
        SettingsStore.setUserName(context, text)
        onNext()
    }

    StepScaffold(
        footer = {
            GradientButton(
                text = if (typed.isEmpty()) stringResource(R.string.onboarding_continue)
                else stringResource(R.string.onboarding_continue_as, typed),
                onClick = { saveAndGo() },
                modifier = Modifier.testTag(ONBOARDING_NAME_CONTINUE_TAG),
            )
            TextButton(onClick = { saveAndGo() }) {
                Text(
                    stringResource(R.string.onboarding_skip),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        StepIcon(Icons.Filled.Person)
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_name_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        // The field sits directly under the question, ABOVE the reassurance below it. That order
        // is deliberate: the paragraph is reassurance, not instruction, and with the keyboard up
        // this content area is only a couple of hundred dp tall — putting the paragraph first
        // pushed the field off the bottom of it, so the answer to "what should we call you?" was
        // below the fold on the first screen a new user ever sees.
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(DisplayName.MAX_LENGTH) },
            label = { Text(stringResource(R.string.onboarding_name_label)) },
            placeholder = { Text(stringResource(R.string.onboarding_name_placeholder)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveAndGo() }),
            modifier = Modifier.fillMaxWidth().testTag(ONBOARDING_NAME_FIELD_TAG),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_name_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The app's signature feature, introduced right after Welcome so new users meet the coach
 *  before the permission chores. Pure showcase — nothing to grant here. */
@Composable
private fun CoachStep(onNext: () -> Unit) {
    StepScaffold(footer = { GradientButton(text = "Continue", onClick = onNext) }) {
        Spacer(Modifier.height(8.dp))
        StepIcon(Icons.Filled.AutoAwesome)
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_coach_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_coach_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        CoachFeatureRow(
            Icons.Filled.QueryStats,
            stringResource(R.string.onboarding_coach_numbers_title),
            stringResource(R.string.onboarding_coach_numbers_body),
        )
        Spacer(Modifier.height(12.dp))
        CoachFeatureRow(
            Icons.Filled.Person,
            stringResource(R.string.onboarding_coach_knows_title),
            stringResource(R.string.onboarding_coach_knows_body),
        )
        Spacer(Modifier.height(12.dp))
        CoachFeatureRow(
            Icons.Filled.Flag,
            stringResource(R.string.onboarding_coach_goals_title),
            stringResource(R.string.onboarding_coach_goals_body),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_coach_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CoachFeatureRow(icon: ImageVector, title: String, desc: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Internal rather than private so the rendering test can measure the real step — whether the
 *  first picture is on screen without scrolling is the whole point of this screen's layout. */
@Composable
internal fun EssentialStep(
    perm: Perm,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onRequestDisclosure: (() -> Unit) -> Unit,
) {
    val icon = if (perm.key == "accessibility") Icons.Filled.Shield else Icons.Filled.Visibility
    val guide = remember(perm.key) {
        SetupGuides.forPermission(perm.key, DeviceVendor.advice().brand)
    }

    // **Did they go to Settings and come back without it working?** Until now that produced the
    // identical screen with nothing to say they had failed, which is the single most likely place
    // for a first-time user to give up — they cannot tell "I did it wrong" from "this app is
    // broken". `resumeTick` already counts returns to the app; comparing the count taken when
    // Grant was pressed against the current one is what separates "still here" from "been and
    // come back". Plain `remember`, not `rememberSaveable`, deliberately: the tick itself resets
    // if the process is killed in Settings, and a saved attempt marker would then outlive its
    // counter and accuse someone who has only just arrived.
    val tick = resumeTick()
    var attemptTick by remember(perm.key) { mutableIntStateOf(NO_ATTEMPT) }
    val attempt = setupAttempt(attemptTick, tick, perm.granted)
    val stuck = attempt == SetupAttempt.STUCK
    val justSucceeded = attempt == SetupAttempt.SUCCEEDED

    val context = LocalContext.current
    val grant = gatedFix(perm, onRequestDisclosure)
    // **The steps go with them.** Once Settings opens, this screen is behind them and every
    // picture on it is out of reach; a notification is the only channel that still carries the
    // instructions there (see SetupStepsNotifier for why an overlay is not).
    val grantAndRemember = {
        attemptTick = tick
        guide?.let { SetupStepsNotifier.show(context, it, stepHeadline(context, perm)) }
        grant()
    }

    // Down again the moment they are back, whether it worked or not — and when the step is
    // left by any route at all, so a half-finished setup cannot leave litter in the shade.
    DisposableEffect(tick, perm.granted) {
        if (attempt != SetupAttempt.NONE || perm.granted) SetupStepsNotifier.clear(context)
        onDispose { SetupStepsNotifier.clear(context) }
    }

    StepScaffold(footer = {
        if (perm.granted) {
            GradientButton(text = stringResource(R.string.onboarding_continue), onClick = onContinue)
        } else {
            // The button says what pressing it does. "Grant" is this app's word for it, not the
            // reader's, and it gives no hint that the next thing to happen is being moved into a
            // different app entirely.
            GradientButton(
                text = when {
                    stuck -> stringResource(R.string.onboarding_try_again)
                    // Accessibility shows the disclosure first (gatedFix), so "Open Settings"
                    // would promise a screen that is one tap further away than it says.
                    perm.key == ACCESSIBILITY_PERM ->
                        stringResource(R.string.onboarding_turn_on_blocking)
                    else -> stringResource(R.string.onboarding_open_settings)
                },
                onClick = grantAndRemember,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSkip) {
                Text(
                    stringResource(R.string.onboarding_skip_for_now),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }) {
        // Half height, and no "Required" chip while it is still off: between them they were ~130dp
        // of decoration above the first instruction, which is exactly the difference between a
        // picture being on screen and being below the fold. The chip stays for the granted case,
        // where it is the reward rather than a label repeating the sentence underneath it.
        Spacer(Modifier.height(4.dp))
        StepIcon(icon, granted = perm.granted, size = 56.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            stepHeadline(LocalContext.current, perm),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (perm.granted) {
            Spacer(Modifier.height(8.dp))
            StatusChip(granted = true, requiredLabel = stringResource(R.string.profile_required))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            perm.desc,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (justSucceeded) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.onboarding_done),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(SETUP_SUCCESS_TAG),
            )
        }
        if (stuck) {
            Spacer(Modifier.height(20.dp))
            StuckNote(perm.key)
        }
        // The pictures of the screen they are about to land on. Not shown once the permission is
        // on: at that point they are a wall of instructions for something already done.
        //
        // **Above the greyed-out note deliberately.** These are the main path, that note is a
        // contingency for one Android version, and it is four paragraphs long — putting it first
        // pushed the pictures off the bottom of the screen, so the person who needs them most
        // (the one who does not read four paragraphs) never saw them.
        if (!perm.granted && guide != null) {
            Spacer(Modifier.height(20.dp))
            // **The two lines that were missing entirely.** A heading, so it is obvious at a glance
            // that instructions exist below — and a warning that the button moves them into a
            // different app, which is the most disorienting moment in the whole product and was
            // never mentioned anywhere on this screen.
            Text(
                stringResource(R.string.onboarding_steps_heading, guide.shots.size.toString()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.onboarding_leaves_app),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            SetupGuideStrip(guide)
        }
        // The dead end this wizard walks new users straight into on Android 13+: Grant opens the
        // Accessibility page, and the toggle is greyed out with no explanation. Still on screen
        // *before* they press Grant and conclude it's broken — just after the pictures.
        if (perm.key == ACCESSIBILITY_PERM &&
            needsRestrictedSettingsNote(
                Build.VERSION.SDK_INT,
                sideloadedBuild = Dist.SELF_UPDATE,
                accessibilityGranted = perm.granted,
            )
        ) {
            Spacer(Modifier.height(20.dp))
            RestrictedSettingsNote()
        }
    }
}


const val SETUP_SUCCESS_TAG = "setup_success"
const val SETUP_STUCK_TAG = "setup_stuck"

/**
 * The headline for a permission step, in the reader's words rather than Android's.
 *
 * **The Android name is not thrown away — it moves.** "Accessibility" and "Display over other apps"
 * are meaningless as a promise of what the app will do for you, and essential as the label to hunt
 * for once you are inside Settings. So the headline states the benefit and [Perm.desc] names the
 * setting; the OS's vocabulary in the biggest text on the screen served neither purpose.
 *
 * Falls back to the permission's own label, so a permission added later is never headline-less.
 */
private fun stepHeadline(ctx: Context, perm: Perm): String = when (perm.key) {
    // Deliberately not the button's words ("Turn on blocking"): a headline repeating its own button
    // tells the reader nothing new. This one states the size of the job, which is the reassuring
    // part — it really is one switch.
    ACCESSIBILITY_PERM -> ctx.getString(R.string.onboarding_headline_accessibility)
    "overlay" -> ctx.getString(R.string.onboarding_headline_overlay)
    else -> perm.label
}

/**
 * Shown only after someone has gone to Settings and come back with the permission still off.
 *
 * It has to do one thing before anything else: say that **nothing is broken**. Everything else on
 * this screen looks identical to the first visit, so without this the reasonable conclusion is
 * that the app does not work — and that conclusion is the end of the setup.
 */
@Composable
private fun StuckNote(permKey: String) {
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface).padding(18.dp)
            .testTag(SETUP_STUCK_TAG),
    ) {
        Text(
            stringResource(R.string.onboarding_stuck_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (permKey == ACCESSIBILITY_PERM) {
                stringResource(
                    R.string.onboarding_stuck_accessibility,
                    DeviceVendor.accessibilityHint(DeviceVendor.advice()),
                )
            } else {
                stringResource(R.string.onboarding_stuck_other)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecommendedStep(
    perms: List<Perm>,
    onContinue: () -> Unit,
    onRequestDisclosure: (() -> Unit) -> Unit,
) {
    StepScaffold(
        footer = {
            GradientButton(text = stringResource(R.string.onboarding_continue), onClick = onContinue)
        },
    ) {
        Spacer(Modifier.height(8.dp))
        StepIcon(Icons.Filled.BatteryChargingFull)
        Spacer(Modifier.height(24.dp))
        Text(
            "Recommended",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Optional, but they make blocking more reliable. You can skip these.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        perms.forEach { p ->
            RecommendedRow(p, onRequestDisclosure)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RecommendedRow(p: Perm, onRequestDisclosure: (() -> Unit) -> Unit) {
    // Three rows now, so the icon has to distinguish them: battery optimisation and the OEM
    // keep-alive setting are different chores and two identical icons read as a duplicate row.
    val icon = when (p.key) {
        "usage" -> Icons.Filled.QueryStats
        "autostart" -> Icons.Filled.PowerSettingsNew
        else -> Icons.Filled.BatteryChargingFull
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(p.desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        if (p.granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("On", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            // Through the gate like every other Grant button. This row is only ever handed
            // usage/battery today, so it cannot currently reach accessibility — but it was the
            // one call site that bypassed the disclosure by construction, and "currently" is not
            // a property anybody checks when adding a permission to the list.
            TextButton(onClick = gatedFix(p, onRequestDisclosure)) { Text("Grant") }
        }
    }
}

@Composable
private fun DoneStep(grantedEssentials: Int, totalEssentials: Int, onFinish: () -> Unit) {
    val allEssential = grantedEssentials == totalEssentials
    StepScaffold(footer = { GradientButton(text = "Start blocking", onClick = onFinish) }) {
        Spacer(Modifier.height(24.dp))
        StepIcon(Icons.Filled.Shield, granted = allEssential)
        Spacer(Modifier.height(28.dp))
        // **"Almost there" was what this said when blocking would not work at all**, under a line
        // counting "essential permissions enabled" — a phrase nobody outside this repo uses. If
        // someone skipped the switch, the last thing they see has to say so in words they cannot
        // misread, because everything after this screen quietly does nothing.
        Text(
            if (allEssential) "You're all set" else "Blocking is not on yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (allEssential) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (allEssential) {
                "AppBlocker can now block reliably. Start adding apps to block."
            } else {
                "You skipped ${totalEssentials - grantedEssentials} of the $totalEssentials " +
                    "switches AppBlocker needs, so nothing will be blocked yet. You can finish " +
                    "any time — tap “Finish setup” on the home screen, and it will show you the " +
                    "pictures again."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusChip(granted: Boolean, requiredLabel: String) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(
                if (granted) MaterialTheme.colorScheme.primaryContainer
                else Color(0xFF3A2A12)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (granted) {
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Granted", style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        } else {
            Text(requiredLabel, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = Color(0xFFFFB020))
        }
    }
}
