package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.appblocker.data.DisplayName
import com.appblocker.data.SettingsStore
import com.appblocker.ui.theme.AppCard
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.appBackground

/** Test tags for the rendering test — the field and the way out of the screen. */
const val ACCOUNT_NAME_FIELD_TAG = "account_name_field"
const val ACCOUNT_SAVE_TAG = "account_save"

/**
 * "Your profile" — the one page that is about the *person* using the app rather than about
 * blocking.
 *
 * It exists because AppBlocker used to be one person's app: the display name defaulted to the
 * original owner's own name, and the only way to change it was a pencil in the Profile header
 * that opened an [androidx.compose.material3.AlertDialog] with a text field in it. Two problems,
 * one page fixes both — a stranger now has somewhere to say who they are, and the typing no
 * longer happens inside a Dialog.
 *
 * **A full screen, not a Dialog, and that is not a style choice.** Dialog windows report zero
 * insets while drawing edge-to-edge on the owner's HyperOS phone (see `CLAUDE.md` and
 * [DurationPickerDialog]), so the keyboard draws straight over a dialog's text field with nothing
 * to push it up. [ReportProblemSheet] and [FrictionGate] are full screens for the same reason.
 * The content scrolls and the actions are pinned below it, so the Save button stays reachable
 * with the keyboard up and at large system font sizes.
 *
 * There is deliberately **no sign-in, account or password** here. Nothing in this app leaves the
 * phone, and the name is not an identity — it is only what the Profile header and the AI Coach
 * call you.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onRunSetupAgain: () -> Unit = {},
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val saved = remember { SettingsStore.userName(context) }
    var text by remember { mutableStateOf(saved) }
    // Compare sanitized forms so trailing spaces alone don't light up "Save".
    val pending = DisplayName.sanitize(text)
    val dirty = pending != DisplayName.sanitize(saved)

    fun save() {
        SettingsStore.setUserName(context, text)
        // Drop focus before leaving so the keyboard goes down with the screen rather than
        // hanging over whatever is underneath it.
        focus.clearFocus()
        onBack()
    }

    BackHandler { onBack() }

    // background BEFORE safeDrawingPadding so the app colour still paints behind the system bars
    // (targetSdk 35 = forced edge-to-edge on Android 15+), same as every other full-screen page.
    Column(Modifier.fillMaxSize().background(appBackground()).safeDrawingPadding()) {
        EditorTopBar("Your profile", onBack = onBack)
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                NamePreview(pending)

                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(DisplayName.MAX_LENGTH) },
                    label = { Text("Your name") },
                    placeholder = { Text("First name is fine") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().testTag(ACCOUNT_NAME_FIELD_TAG),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Only used to greet you. Leave it empty and the app just says \"You\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))
                SectionLabel("Where your name shows up")
                AppCard(elevation = 4.dp, contentPadding = PaddingValues(0.dp)) {
                    InfoRow(
                        icon = Icons.Filled.Person,
                        title = "The Profile page",
                        subtitle = "The greeting and the circle of initials at the top.",
                    )
                    RowDivider()
                    InfoRow(
                        icon = Icons.Filled.AutoAwesome,
                        title = "The AI Coach",
                        subtitle = "It uses your first name when it talks to you.",
                    )
                }

                Spacer(Modifier.height(24.dp))
                SectionLabel("On this phone")
                AppCard(elevation = 4.dp, contentPadding = PaddingValues(0.dp)) {
                    InfoRow(
                        icon = Icons.Filled.PhoneAndroid,
                        title = "No account, no sign-in",
                        subtitle = "AppBlocker has no login and no online account. Your name, " +
                            "your blocks and your statistics are stored on this phone only, and " +
                            "uninstalling the app removes them.",
                    )
                    RowDivider()
                    InfoRow(
                        icon = Icons.Filled.Restore,
                        title = "Run setup again",
                        subtitle = "Walk through the welcome and permission steps once more. " +
                            "Your blocks, schedules, words and PIN are left exactly as they are.",
                        chevron = true,
                        onClick = onRunSetupAgain,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // Pinned below the scrolling content — the reason this page is not a Dialog. Keeping the
        // button out of the scroll is what stops the keyboard, or a large display size, from
        // leaving the user on a screen with nothing they can press.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            GradientButton(
                text = "Save",
                enabled = dirty,
                onClick = { save() },
                modifier = Modifier.testTag(ACCOUNT_SAVE_TAG),
            )
            if (DisplayName.isSet(text)) {
                TextButton(
                    onClick = { text = "" },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Clear my name", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** The gradient hero: initials + the name as it will actually appear, updating as you type. */
@Composable
private fun NamePreview(name: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(AppGradients.accent)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    DisplayName.initials(name), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = Color.White,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    DisplayName.display(name), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                )
                Text(
                    if (DisplayName.isSet(name)) "This is how you'll be greeted"
                    else "Add your name and the app will greet you by it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/** Hairline between rows in a card, indented past the icon — the Profile page's spelling. */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        modifier = Modifier.padding(start = 70.dp),
    )
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

/**
 * A card row that explains something. Same shape as `ProfileScreen`'s row, but its own copy
 * because that one is private to a 900-line file and takes a `enabled`/`badge`/`destructive`
 * vocabulary this page has no use for.
 */
@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
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
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
            )
        }
    }
}
