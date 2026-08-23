package com.appblocker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.GuideShot
import com.appblocker.data.SetupGuide
import com.appblocker.ui.theme.AppShapes

const val SETUP_GUIDE_TAG = "setup_guide"
const val SETUP_GUIDE_SHOT_TAG = "setup_guide_shot"

/** The picture itself, tagged apart from its card: a card is still tall when its caption alone
 *  renders, so only this can answer "did the screenshot actually draw?". */
const val SETUP_GUIDE_IMAGE_TAG = "setup_guide_image"

/**
 * "Here is the screen you are about to land on, and here is the row to tap."
 *
 * The wizard hands a first-time user over to Android's Settings and then cannot say another word
 * until they come back. This is what it says on the way out: real screenshots
 * ([com.appblocker.data.SetupGuides]) with the target ringed and one plain instruction each.
 *
 * **The ring is drawn here rather than painted into the image**, for two reasons that both cost
 * nothing now and save work later: it follows the theme instead of being a fixed colour baked
 * against one background, and the stored asset stays a plain screenshot, so a picture can be
 * re-cropped without redoing its annotation. The rectangle arrives as fractions of the image, so
 * it survives any scaling — which matters because the same picture is drawn at whatever width the
 * phone and the user's display-size setting produce.
 *
 * Captions are numbered and must read as instructions on their own: an image that fails to load,
 * or a screen reader, leaves only the text.
 */
@Composable
fun SetupGuideStrip(guide: SetupGuide, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().testTag(SETUP_GUIDE_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        guide.shots.forEachIndexed { index, shot -> GuideShotCard(index + 1, shot) }
        Text(
            guide.takenOn,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GuideShotCard(number: Int, shot: GuideShot) {
    Column(Modifier.fillMaxWidth().testTag(SETUP_GUIDE_SHOT_TAG)) {
        Row(verticalAlignment = Alignment.Top) {
            StepNumber(number)
            Spacer(Modifier.width(12.dp))
            Text(
                shot.caption,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        RingedScreenshot(shot)
    }
}

@Composable
private fun StepNumber(number: Int) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun RingedScreenshot(shot: GuideShot) {
    // FillWidth, so the drawn height is the image's aspect ratio — which makes the Canvas below
    // exactly the same rectangle as the picture, and lets the fractions map straight onto it.
    Box(
        Modifier.fillMaxWidth().clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(SETUP_GUIDE_IMAGE_TAG),
    ) {
        Image(
            painter = painterResource(shot.image),
            // Decorative: the numbered caption directly above says the same thing in words, and a
            // screen reader announcing the screenshot twice helps nobody.
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
        val ringColor = MaterialTheme.colorScheme.primary
        Canvas(Modifier.matchParentSize()) {
            val left = shot.ring.left * size.width
            val top = shot.ring.top * size.height
            val right = shot.ring.right * size.width
            val bottom = shot.ring.bottom * size.height
            drawRoundRect(
                color = ringColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}
