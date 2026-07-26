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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.data.AppIcons
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockThemes
import com.appblocker.data.Quote
import com.appblocker.data.Quotes
import com.appblocker.data.backdropSolid
import com.appblocker.ui.theme.AppBlockerTheme
import com.appblocker.ui.theme.AppGradients

/** Full-screen page shown when a blocked app or web page is opened. */
class BlockScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Blocked"
        // Every dodged open counts as ~3 minutes of life back (mirrors the overlay).
        val reclaimedMinutes = AttemptCounter.totalToday(this) * 3

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
                    reclaimedMinutes = reclaimedMinutes,
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
    }
}

/** Editorial-poster block screen: the quote is the hero, app context is a small footer. */
@Composable
private fun BlockScreen(
    appIcon: Bitmap?,
    title: String,
    message: String,
    reclaimedMinutes: Int,
    quote: Quote,
    onClose: () -> Unit,
) {
    // Follow the chosen block-screen look (Profile ▸ Block screen), exactly as the overlay does.
    // This screen is only the fallback when the overlay can't be drawn — but for anyone who has
    // denied the overlay permission it is the ONLY block screen they ever see, so leaving it on
    // the default look would silently ignore their choice.
    val theme = BlockThemes.current(LocalContext.current)
    val primaryText = Color(theme.primaryText)
    val quoteSize = Quotes.sizeSpFor(quote.text)
    Column(
        Modifier
            .fillMaxSize()
            .background(
                if (theme.accentGradientEnd != null) AppGradients.background
                else SolidColor(Color(theme.backdropSolid())),
            )
            .padding(start = 28.dp, end = 28.dp, top = 72.dp, bottom = 36.dp),
    ) {
        // Kicker: shield badge + what happened.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(theme.badge)),
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
                color = Color(theme.secondaryText),
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        // Masthead: today's win, giant editorial serif.
        Text(
            reclaimedMinutes.toString(),
            fontSize = 120.sp,
            lineHeight = 120.sp,
            fontFamily = FontFamily.Serif,
            color = primaryText,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            "MINUTES RECLAIMED TODAY",
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.2.sp,
            color = Color(theme.accent),
        )

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
                color = primaryText,
            )
            Text(
                "— ${quote.author}".uppercase(),
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                style = TextStyle(
                    brush = theme.accentGradientEnd?.let {
                        Brush.linearGradient(listOf(Color(theme.accent), Color(it)))
                    } ?: SolidColor(Color(theme.accent)),
                ),
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
                // Our own mark: the launcher icon the user actually picked (icon switcher),
                // clipped round like the picker preview — not a generic stand-in.
                Image(
                    painter = painterResource(AppIcons.current(LocalContext.current).previewRes),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                message,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }

        GradientButton(text = "Got it", onClick = onClose)
    }
}
