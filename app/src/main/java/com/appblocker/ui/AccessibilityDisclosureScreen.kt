package com.appblocker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.appblocker.R
import com.appblocker.ui.theme.AppCard
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.appBackground
import com.appblocker.ui.theme.pageWidth

/** Test tags for the rendering test — the two ways off this screen. */
const val DISCLOSURE_AGREE_TAG = "disclosure_agree"
const val DISCLOSURE_DECLINE_TAG = "disclosure_decline"

/** Where the hosted policy lives. Also in README.md; served by GitHub Pages from `docs/`. */
const val PRIVACY_POLICY_URL = "https://boudiahdab2003-art.github.io/AppBlocker/privacy-policy"

/**
 * **The screen shown before AppBlocker ever sends anyone to Android's Accessibility settings.**
 *
 * The whole app works by watching the screen, which is a genuinely large thing to ask for. Google
 * Play's AccessibilityService policy requires a prominent disclosure and explicit agreement
 * *before* the request — and independently of Play, someone whose blocked list is the most private
 * thing on their phone deserves to be told plainly what this permission lets the app see.
 *
 * **This replaced an `AlertDialog`**, for two reasons. Dialog windows report zero insets while
 * drawing edge-to-edge on the owner's device (`CLAUDE.md`, and the note on [DurationPickerDialog]),
 * so a dialog is the one shape that can put its own buttons out of reach here; and a dismissible
 * pop-up is the reading of "prominent disclosure" a store reviewer is least generous about.
 *
 * **Every door to the accessibility settings page must come through here.** There are four, and
 * one of them — the Blocking tab's "Turn on protection" banner — skipped the old dialog entirely
 * and sent people straight to Settings with no disclosure at all, which is exactly the flow the
 * policy is about. Naming them is the only defence, since no test can see a *new* call site that
 * ignores [gatedFix]:
 *
 * 1. [PermissionsScreen]'s `PermCard` "Grant" button.
 * 2. [OnboardingScreen]'s `EssentialStep` "Grant" button.
 * 3. [OnboardingScreen]'s `RecommendedRow` "Grant" (only ever handed `usage`/`battery` today, but
 *    it is the one site that would bypass the gate by construction).
 * 4. [BlockEditorScreen]'s `ProtectionBanner` "Turn on protection" button.
 *
 * Like [FrictionGate], this is drawn by [AppRoot] over the whole window rather than where it is
 * asked for: `PermCard` lives inside a scrolling column, and a full-screen page emitted from
 * inside one does not lay out correctly.
 */
@Composable
fun AccessibilityDisclosureScreen(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler { onDecline() }

    // background BEFORE safeDrawingPadding so the app colour still paints behind the system bars
    // (targetSdk 35 = forced edge-to-edge on Android 15+), same as every other full-screen page.
    Column(Modifier.fillMaxSize().background(appBackground()).safeDrawingPadding()) {
        EditorTopBar(stringResource(R.string.disclosure_topbar), onBack = onDecline)
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.pageWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Hero()

                Spacer(Modifier.height(24.dp))
                SectionLabel(stringResource(R.string.disclosure_reads))
                AppCard(elevation = 4.dp, contentPadding = PaddingValues(0.dp)) {
                    DisclosureRow(
                        icon = Icons.Filled.Visibility,
                        title = stringResource(R.string.disclosure_front_title),
                        subtitle = stringResource(R.string.disclosure_front_body),
                    )
                    RowLine()
                    DisclosureRow(
                        icon = Icons.Filled.Language,
                        title = stringResource(R.string.disclosure_browser_title),
                        subtitle = stringResource(R.string.disclosure_browser_body),
                    )
                }

                Spacer(Modifier.height(24.dp))
                SectionLabel(stringResource(R.string.disclosure_never))
                AppCard(elevation = 4.dp, contentPadding = PaddingValues(0.dp)) {
                    DisclosureRow(
                        icon = Icons.Filled.PhoneAndroid,
                        title = stringResource(R.string.disclosure_local_title),
                        subtitle = stringResource(R.string.disclosure_local_body),
                    )
                    RowLine()
                    DisclosureRow(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(R.string.disclosure_noaccount_title),
                        subtitle = stringResource(R.string.disclosure_noaccount_body),
                    )
                    RowLine()
                    DisclosureRow(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.disclosure_coach_title),
                        subtitle = stringResource(R.string.disclosure_coach_body),
                    )
                    RowLine()
                    DisclosureRow(
                        icon = Icons.Filled.Block,
                        title = stringResource(R.string.disclosure_onlyblocking_title),
                        subtitle = stringResource(R.string.disclosure_onlyblocking_body),
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.disclosure_policy_link),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { openPrivacyPolicy(context) }
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.disclosure_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        // Pinned below the scrolling content. The consent is the point of the screen, so the way
        // to give it — and the way to refuse — must never be the thing that scrolls off.
        // pageWidth on the footer as well as the content — see AccountScreen for the tablet
        // bug that came from capping only one of the two.
        Column(Modifier.pageWidth().padding(horizontal = 16.dp)) {
            GradientButton(
                text = stringResource(R.string.disclosure_agree),
                onClick = onAgree,
                modifier = Modifier.testTag(DISCLOSURE_AGREE_TAG),
            )
            TextButton(
                onClick = onDecline,
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .testTag(DISCLOSURE_DECLINE_TAG),
            ) {
                Text(
                    stringResource(R.string.disclosure_decline),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Opens the hosted privacy policy in a browser. No-op if the phone has none. */
private fun openPrivacyPolicy(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun Hero() {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(AppGradients.accent)
            .padding(20.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Visibility, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.disclosure_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.disclosure_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun RowLine() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        modifier = Modifier.padding(start = 70.dp),
    )
}

@Composable
private fun DisclosureRow(icon: ImageVector, title: String, subtitle: String) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(16.dp)) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
