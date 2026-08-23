package com.appblocker.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.Dist
import com.appblocker.R
import com.appblocker.data.DeviceVendor
import com.appblocker.data.SetupGuides
import com.appblocker.service.ProtectionNotifier
import com.appblocker.ui.theme.AppShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    /** Ask AppRoot for [AccessibilityDisclosureScreen]. This screen must not draw it itself: the
     *  Grant buttons live inside a scrolling column, where a full-screen page does not lay out. */
    onRequestDisclosure: (() -> Unit) -> Unit = {},
) {
    val context = LocalContext.current
    val perms = rememberPermissions()
    val remaining = perms.count { !it.granted && it.essential }
    val vendor = DeviceVendor.advice()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { EditorTopBar(stringResource(R.string.profile_permissions_title), onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                if (remaining == 0) stringResource(R.string.profile_header_all_set)
                else stringResource(R.string.permissions_header),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 12.dp))
            // The one screen where a blocked first run gets explained. Above the cards, because a
            // greyed-out Accessibility toggle is what the user is looking at when they come here.
            if (needsRestrictedSettingsNote(
                    Build.VERSION.SDK_INT,
                    sideloadedBuild = Dist.SELF_UPDATE,
                    accessibilityGranted = perms.any { it.key == ACCESSIBILITY_PERM && it.granted },
                )
            ) {
                RestrictedSettingsNote()
                Spacer(Modifier.padding(top = 12.dp))
            }
            // The same pictures the wizard shows, for the person who skipped setup or is coming
            // back to fix it — one copy of the guide, exactly as RestrictedSettingsNote above is
            // one copy of its explanation. Only while accessibility is still off: once it is on,
            // this is three screenshots of a job already done, in front of every other card.
            if (perms.none { it.key == ACCESSIBILITY_PERM && it.granted }) {
                SetupGuides.forPermission(ACCESSIBILITY_PERM, vendor.brand)?.let {
                    SetupGuideStrip(it)
                    Spacer(Modifier.padding(top = 16.dp))
                }
            }
            perms.forEach { p ->
                PermCard(p, onRequestDisclosure)
                Spacer(Modifier.padding(top = 12.dp))
            }
            Text(
                vendor.extraTips,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            // Named honestly rather than left to be discovered. A cloned app runs as a different
            // Android user and an accessibility service gets no events from it, so this is a hole
            // the app cannot close — and an unblocked app the user believes is blocked is worse
            // than one they know about.
            vendor.clonedAppsFeature?.let { feature ->
                Text(
                    stringResource(R.string.permissions_brand_note, vendor.brand),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    stringResource(R.string.permissions_clone_note, feature),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            // The *other* Second Space failure, and deliberately separate from the cloned-apps
            // note above: that one is "an app inside the other space is invisible to us", this
            // one is "switching space kills us in THIS space and Android still says we're on".
            // Same feature, opposite advice — merging them would leave each reader half wrong.
            vendor.spacesWarning?.let { warning ->
                Text(
                    stringResource(R.string.permissions_spaces_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            Text(
                stringResource(R.string.permissions_alert_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                stringResource(R.string.permissions_alert_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 12.dp))
            GradientButton(
                text = stringResource(R.string.permissions_alert_button),
                onClick = { ProtectionNotifier.notifyTest(context) },
            )
            Spacer(Modifier.padding(top = 24.dp))
        }
    }
}

/**
 * The "Allow restricted settings" explainer, shown when [needsRestrictedSettingsNote] says so.
 *
 * Public and in this file rather than private to the screen, because the same dead end appears in
 * two places — here and the onboarding wizard's accessibility step — and the wizard is where new
 * users actually meet it. One copy, so the wording cannot drift into two half-right versions.
 *
 * Deliberately plain text rather than a button: the ⋮ menu it describes is inside Android's own
 * App info page and there is no intent that opens it, so a button would be a promise the app
 * cannot keep. The steps are short enough to follow.
 */
@Composable
fun RestrictedSettingsNote() {
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface).padding(18.dp),
    ) {
        Text(
            stringResource(R.string.restricted_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.padding(top = 6.dp))
        Text(
            stringResource(R.string.restricted_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(top = 6.dp))
        // **Android 15 redesigned App info and there is no ⋮ on it** (measured on an Android 15
        // emulator, 23 Aug 2026 — the top-right icon is "open in new", not an overflow menu). The
        // instructions above are right for 13 and 14, where this dead end was found; rather than
        // guess at where Android 15 moved the item, say the one thing that is certainly true and
        // is also the reassurance a stuck person needs.
        Text(
            stringResource(R.string.restricted_android15),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermCard(p: Perm, onRequestDisclosure: (() -> Unit) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (p.granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("On", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                } } else if (!p.essential) {
                Text(stringResource(R.string.profile_optional),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.profile_required),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = Color(0xFFFFB020))
            }
        }
        Spacer(Modifier.padding(top = 6.dp))
        Text(p.desc, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!p.granted) {
            Spacer(Modifier.padding(top = 12.dp))
            GradientButton(
                text = stringResource(
                    if (p.key == "autostart") R.string.onboarding_open_settings
                    else R.string.perm_grant,
                ),
                onClick = gatedFix(p, onRequestDisclosure),
            )
        }
    }
}
