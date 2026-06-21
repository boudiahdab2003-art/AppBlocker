package com.appblocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun FocusScreen(vm: FocusViewModel = viewModel()) {
    val active by vm.isActive.collectAsState()
    val remaining by vm.remainingMillis.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (active) ActiveSession(remaining) else StartSession { vm.start(it) }
    }
}

@Composable
private fun ActiveSession(remainingMillis: Long) {
    val totalSeconds = remainingMillis / 1000
    val mm = totalSeconds / 60
    val ss = totalSeconds % 60
    Text("🧘", fontSize = 72.sp)
    Spacer(Modifier.height(16.dp))
    Text(
        "%02d:%02d".format(mm, ss),
        fontSize = 64.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Focus mode is on.",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Your blocked apps are locked until the timer ends. There's no stopping early — that was the point. You've got this.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun StartSession(onStart: (Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(25) }
    val options = listOf(15, 25, 50, 90)

    Text("🎯", fontSize = 64.sp)
    Spacer(Modifier.height(12.dp))
    Text(
        "Start a focus session",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Lock your blocked apps for a set time. Once you start, it can't be stopped until the timer runs out.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { opt ->
            val selected = opt == minutes
            OutlinedCard(
                onClick = { minutes = opt },
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    Modifier.width(64.dp).padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "$opt",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "min",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    Button(onClick = { onStart(minutes) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("Start $minutes-minute focus", fontWeight = FontWeight.SemiBold)
    }
}
