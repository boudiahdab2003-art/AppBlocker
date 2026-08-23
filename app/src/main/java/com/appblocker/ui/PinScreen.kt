package com.appblocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appblocker.data.PinStore
import com.appblocker.ui.theme.AppBlockerTheme
import androidx.compose.ui.res.stringResource
import com.appblocker.R

/**
 * Wraps the app: if a PIN is set, the user must enter it before getting in — and again after the
 * app has been away for a while. See [PinStore.shouldRelock] for why "again" is the point.
 */
@Composable
fun LockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var unlocked by rememberSaveable { mutableStateOf(!PinStore.isSet(context)) }
    // Monotonic, per the rule this project has had to relearn three times: a wall-clock stamp
    // would let a clock change either strand the lock open or spring it shut.
    var leftAt by rememberSaveable { mutableStateOf(0L) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> leftAt = SystemClock.elapsedRealtime()
                // ON_START, not ON_RESUME: a system dialog over the app pauses without stopping
                // it, and being asked for the PIN because a permission prompt appeared would be
                // absurd. Stopping means the app genuinely left the screen.
                Lifecycle.Event.ON_START ->
                    if (leftAt > 0L && PinStore.shouldRelock(
                            PinStore.isSet(context), SystemClock.elapsedRealtime() - leftAt,
                        )
                    ) unlocked = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    if (unlocked) {
        content()
    } else {
        AppBlockerTheme {
            PinEntry(
                title = stringResource(R.string.pin_locked_title),
                subtitle = stringResource(R.string.pin_locked_subtitle),
                onSubmit = { pin ->
                    PinStore.check(context, pin).also { if (it) unlocked = true }
                }
            )
        }
    }
}

@Composable
private fun PinEntry(title: String, subtitle: String, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // safeDrawing keeps the centered content clear of the keyboard and system bars
        // (edge-to-edge is forced on Android 15+ and this screen has no Scaffold).
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🔒", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pin = it; error = false } },
                label = { Text("PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = error,
            )
            if (error) {
                Text(
                    stringResource(R.string.pin_incorrect),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { if (!onSubmit(pin)) { error = true; pin = "" } },
                enabled = pin.length >= 4,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(stringResource(R.string.pin_unlock), fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Dialog to set or change the PIN (requires confirming it). */
@Composable
fun SetPinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length in 4..8 && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSet(pin) }) {
                Text(stringResource(R.string.pin_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        title = { Text(stringResource(R.string.profile_pin_set)) },
        text = {
            Column {
                Text(
                    "4–8 digits. You'll need this to change your blocks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                    label = { Text(stringResource(R.string.pin_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) confirm = it },
                    label = { Text(stringResource(R.string.pin_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        }
    )
}
