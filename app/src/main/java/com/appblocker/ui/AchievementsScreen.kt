package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.Achievement
import com.appblocker.data.Gamification
import com.appblocker.data.GamifyState
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Every badge — earned ones in full glory, locked ones dimmed with a progress hint. */
@Composable
fun AchievementsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<GamifyState?>(null) }
    LaunchedEffect(Unit) { state = runCatching { Gamification.evaluate(context) }.getOrNull() }

    Column(Modifier.fillMaxSize()) {
        EditorTopBar(title = "Achievements", onBack = onBack)
        val g = state
        if (g != null) LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item { LevelHeader(g); Spacer(Modifier.height(18.dp)) }
            val rows = Gamification.ACHIEVEMENTS.chunked(2)
            items(rows.size) { i ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows[i].forEach { a ->
                        Box(Modifier.weight(1f)) { BadgeCard(a, g) }
                    }
                    if (rows[i].size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun LevelHeader(g: GamifyState) {
    Column(
        Modifier.fillMaxWidth()
            .softGlow(RoundedCornerShape(20.dp), elevation = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(AppGradients.accent)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Level ${g.levelIndex + 1} · ${g.level.name}",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                    color = Color.White)
                Text("${g.unlocked.size} of ${Gamification.ACHIEVEMENTS.size} achievements · " +
                    (if (g.streak > 1) "${g.streak}-day streak" else "no streak yet"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(g.levelProgress.coerceIn(0.03f, 1f)).fillMaxHeight()
                .clip(RoundedCornerShape(50)).background(Color.White))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            g.nextLevel?.let { "${g.xp} XP — ${it.threshold - g.xp} more to ${it.name}" }
                ?: "${g.xp} XP — max level reached",
            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun BadgeCard(a: Achievement, g: GamifyState) {
    val unlockedDay = g.unlocked[a.id]
    val earned = unlockedDay != null
    Column(
        Modifier.fillMaxWidth()
            .then(if (earned) Modifier.softGlow(RoundedCornerShape(18.dp), elevation = 6.dp)
            else Modifier)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                if (earned) 1.5.dp else 1.dp,
                if (earned) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                RoundedCornerShape(18.dp),
            )
            .padding(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(
                    if (earned) AppGradients.accent
                    else androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.surfaceVariant)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(a.id), contentDescription = null,
                tint = if (earned) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = if (earned) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(a.desc, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                earned -> "+${a.xp} XP · ${dateOf(unlockedDay!!)}"
                else -> g.progress[a.id] ?: "+${a.xp} XP"
            },
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = if (earned) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun iconFor(id: String): ImageVector = when (id) {
    "first_block" -> Icons.Filled.Block
    "blocks_50" -> Icons.Filled.Shield
    "blocks_250" -> Icons.Filled.Security
    "blocks_1000" -> Icons.Filled.EmojiEvents
    "first_schedule" -> Icons.Filled.Event
    "schedules_3" -> Icons.Filled.CalendarMonth
    "strict_first" -> Icons.Filled.Lock
    "strict_10h" -> Icons.Filled.SelfImprovement
    "coach_chat" -> Icons.Filled.ChatBubble
    "goal_set" -> Icons.Filled.Flag
    "score_80" -> Icons.Filled.Star
    "under_2h" -> Icons.Filled.Spa
    "low_unlocks" -> Icons.Filled.DoNotDisturbOn
    "streak_3" -> Icons.Filled.LocalFireDepartment
    "streak_7" -> Icons.Filled.Whatshot
    "streak_30" -> Icons.Filled.Bolt
    else -> Icons.Filled.Shield // full_armor + any future id
}

/** "Jul 3" from a yyyy*1000+dayOfYear day-stamp. */
private fun dateOf(dayStamp: Int): String {
    val cal = Calendar.getInstance()
    cal.set(Calendar.YEAR, dayStamp / 1000)
    cal.set(Calendar.DAY_OF_YEAR, dayStamp % 1000)
    return SimpleDateFormat("MMM d", Locale.US).format(cal.time)
}
