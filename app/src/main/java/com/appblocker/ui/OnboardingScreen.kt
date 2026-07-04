package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblocker.ui.theme.AppGradients

/**
 * First-run wizard: walks the user through the essential permissions one step at a time, then the
 * recommended optional ones, with a progress indicator. Reuses [rememberPermissions] so each step's
 * granted-state is live (re-checked on resume after the user returns from Settings). Calls [onDone]
 * when the user reaches the end (Finish) or skips out — AppRoot owns persisting "setup seen".
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val perms = rememberPermissions()
    val essentials = perms.filter { it.essential }
    val recommended = perms.filter { it.key == "usage" || it.key == "battery" }

    // Layout: 0 = Welcome, 1 = AI Coach intro, 2..N+1 = one essential each, then recommended, Done.
    val coachStep = 1
    val recommendedStep = essentials.size + 2
    val doneStep = essentials.size + 3
    val totalSteps = doneStep // steps shown in the progress header (Welcome excluded)

    var step by rememberSaveable { mutableIntStateOf(0) }

    // In-wizard back walks to the previous step; at the Welcome step let the host handle back.
    BackHandler(enabled = step > 0) { step-- }

    Box(Modifier.fillMaxSize().background(AppGradients.background)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            if (step > 0) {
                ProgressHeader(current = step, total = totalSteps)
                Spacer(Modifier.height(28.dp))
            } else {
                Spacer(Modifier.height(48.dp))
            }

            when {
                step == 0 -> WelcomeStep(onNext = { step++ })
                step == coachStep -> CoachStep(onNext = { step++ })
                step <= essentials.size + 1 -> EssentialStep(
                    perm = essentials[step - 2],
                    onContinue = { step++ },
                    onSkip = { step = doneStep },
                )
                step == recommendedStep -> RecommendedStep(
                    perms = recommended,
                    onContinue = { step++ },
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
            "Step $current of $total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Big circular icon used at the top of each step. */
@Composable
private fun StepIcon(icon: ImageVector, granted: Boolean = false) {
    Box(
        Modifier.size(96.dp).clip(CircleShape)
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
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        StepIcon(Icons.Filled.Shield)
        Spacer(Modifier.height(28.dp))
        Text(
            "Welcome to AppBlocker",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "AppBlocker keeps you off distracting apps and sites. Two quick permissions and you're " +
                "ready to block.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        GradientButton(text = "Get started", onClick = onNext)
    }
}

/** The app's signature feature, introduced right after Welcome so new users meet the coach
 *  before the permission chores. Pure showcase — nothing to grant here. */
@Composable
private fun CoachStep(onNext: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        StepIcon(Icons.Filled.AutoAwesome)
        Spacer(Modifier.height(24.dp))
        Text(
            "Meet your AI Coach",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Not just a blocker — a coach in your pocket that helps you win back your time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        CoachFeatureRow(Icons.Filled.QueryStats, "Knows your real numbers",
            "Talks about your actual screen time and habits, never generic advice.")
        Spacer(Modifier.height(12.dp))
        CoachFeatureRow(Icons.Filled.Person, "Gets to know you",
            "Remembers why you're blocking and what tempts you. Ask it anything in chat.")
        Spacer(Modifier.height(12.dp))
        CoachFeatureRow(Icons.Filled.Flag, "Sets goals with you",
            "Agree on targets together, track them live, and get fresh tips through the day.")
        Spacer(Modifier.height(16.dp))
        Text(
            "Free — just add your free Gemini key later in Insights ▸ AI Coach.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        GradientButton(text = "Continue", onClick = onNext)
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

@Composable
private fun EssentialStep(perm: Perm, onContinue: () -> Unit, onSkip: () -> Unit) {
    val icon = if (perm.key == "accessibility") Icons.Filled.Shield else Icons.Filled.Visibility
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        StepIcon(icon, granted = perm.granted)
        Spacer(Modifier.height(24.dp))
        Text(
            perm.label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        StatusChip(granted = perm.granted, requiredLabel = "Required")
        Spacer(Modifier.height(16.dp))
        Text(
            perm.desc,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        if (perm.granted) {
            GradientButton(text = "Continue", onClick = onContinue)
        } else {
            GradientButton(text = "Grant", onClick = rememberGatedFix(perm))
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSkip) {
                Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecommendedStep(perms: List<Perm>, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
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
            RecommendedRow(p)
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
        GradientButton(text = "Continue", onClick = onContinue)
    }
}

@Composable
private fun RecommendedRow(p: Perm) {
    val icon = if (p.key == "usage") Icons.Filled.QueryStats else Icons.Filled.BatteryChargingFull
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
            TextButton(onClick = p.onFix) { Text("Grant") }
        }
    }
}

@Composable
private fun DoneStep(grantedEssentials: Int, totalEssentials: Int, onFinish: () -> Unit) {
    val allEssential = grantedEssentials == totalEssentials
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        StepIcon(Icons.Filled.Shield, granted = allEssential)
        Spacer(Modifier.height(28.dp))
        Text(
            if (allEssential) "You're all set" else "Almost there",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "$grantedEssentials of $totalEssentials essential permissions enabled.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (allEssential) "AppBlocker can now block reliably. Start adding apps to block."
            else "You can finish granting these any time from the “Finish setup” banner.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        GradientButton(text = "Start blocking", onClick = onFinish)
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
