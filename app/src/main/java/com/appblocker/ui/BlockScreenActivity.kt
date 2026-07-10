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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.data.Quote
import com.appblocker.data.Quotes
import com.appblocker.ui.theme.AppBlockerTheme
import com.appblocker.ui.theme.AppGradients

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
        drawable.toBitmap(96, 96)
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

/** Editorial-poster block screen: the quote is the hero, app context is a small footer. */
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
    val quoteSize = Quotes.sizeSpFor(quote.text)
    Column(
        Modifier
            .fillMaxSize()
            .background(AppGradients.background)
            .padding(start = 28.dp, end = 28.dp, top = 72.dp, bottom = 36.dp),
    ) {
        // Kicker: shield badge + what happened.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                title.uppercase(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        // The hero quote.
        Column(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                quote.text,
                fontSize = quoteSize.sp,
                lineHeight = (quoteSize * 1.18f).sp,
                fontFamily = FontFamily.Serif,
                color = Color.White,
            )
            Text(
                "— ${quote.author}".uppercase(),
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                style = TextStyle(brush = AppGradients.accent),
                modifier = Modifier.padding(top = 18.dp),
            )
        }

        // Context footer: small app icon + what's blocked + attempt counts.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    message,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    "$today× today  ·  $total× total",
                    fontSize = 15.sp,
                    color = Color(0xFF8A93A5),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        GradientButton(text = "Got it", onClick = onClose)
    }
}
