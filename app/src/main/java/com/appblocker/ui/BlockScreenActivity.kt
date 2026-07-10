package com.appblocker.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.data.Quote
import com.appblocker.data.Quotes
import com.appblocker.ui.theme.AppBlockerTheme
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

/** Full-screen page shown when a blocked app or web page is opened. */
class BlockScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Blocked"
        val today = intent.getIntExtra(EXTRA_TODAY, 0)
        val total = intent.getIntExtra(EXTRA_TOTAL, 0)

        // For an app block, load its icon + label so we can show "{App} is blocked".
        val appLabel = pkg?.let { loadLabel(it) }
        val appIcon = pkg?.let { loadIcon(it) }
        val message = intent.getStringExtra(EXTRA_MESSAGE)
            ?: appLabel?.let { "$it is blocked" }
            ?: "This is blocked right now."
        val quote = Quotes.random()

        // Leaving the block screen should go to the home screen, never back to
        // the blocked app sitting behind us.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            // The block cover always stays dark, regardless of the app's light/dark setting —
            // it's a full-screen "stop" screen and reads best dark (matches the overlay layout).
            AppBlockerTheme(darkTheme = true) {
                BlockScreen(
                    appIcon = appIcon,
                    title = title,
                    message = message,
                    today = today,
                    total = total,
                    quote = quote,
                    onClose = ::goHome,
                )
            }
        }
    }

    private fun loadLabel(pkg: String): String? = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun loadIcon(pkg: String): Bitmap? = runCatching {
        val drawable: Drawable = packageManager.getApplicationIcon(pkg)
        drawable.toBitmap(144, 144)
    }.getOrNull()

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TODAY = "today"
        const val EXTRA_TOTAL = "total"
    }
}

@Composable
private fun BlockScreen(
    appIcon: Bitmap?,
    title: String,
    message: String,
    today: Int,
    total: Int,
    quote: Quote,
    onClose: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxSize()
            .background(AppGradients.background)
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // App icon (or shield for web) on a soft glow, with a brand shield badge.
        Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            listOf(primary.copy(alpha = 0.24f), Color.Transparent)
                        ),
                        CircleShape,
                    )
            )
            if (appIcon != null) {
                Image(
                    bitmap = appIcon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(104.dp).clip(RoundedCornerShape(26.dp)),
                )
            } else {
                ShieldBadge(size = 104, primary = primary)
            }
            // Brand badge overlapping the top-start of the icon.
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 22.dp)
            ) {
                ShieldBadge(size = 44, primary = primary)
            }
        }

        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Attempts pill.
        Column(
            Modifier
                .padding(top = 22.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF181F2E))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "You tried to open it",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "$today× today  ·  $total× total",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // Motivational quote card.
        Column(
            Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .softGlow(RoundedCornerShape(20.dp), elevation = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF181B22))
                .border(1.dp, AppGradients.AccentEnd.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
        ) {
            Text(
                "“",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = AppGradients.AccentEnd,
            )
            Text(
                quote.text,
                fontSize = 20.sp,
                fontStyle = FontStyle.Italic,
                lineHeight = 28.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "— ${quote.author}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        GradientButton(
            text = "Close",
            onClick = onClose,
            modifier = Modifier.padding(top = 28.dp),
        )
    }
}

/** A blue circle with a white shield — our brand mark. */
@Composable
private fun ShieldBadge(size: Int, primary: Color) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Shield,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((size * 0.6f).dp),
        )
    }
}
