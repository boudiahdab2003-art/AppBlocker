package com.appblocker.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.R

/**
 * Draws the hero banner shown when the protection notification is expanded (BigPictureStyle).
 *
 * Rendered as a plain bitmap so it looks pixel-identical on every device — notably Xiaomi/MIUI,
 * which mangles custom RemoteViews notification layouts. Uses the app's own blue→violet accent
 * (AppGradients.AccentStart/End) so it matches every hero and CTA in the app.
 */
object NotificationBanner {
    // 2:1 is the sweet spot for BigPicture; generous px keeps text crisp when the system scales it.
    private const val W = 1024
    private const val H = 512

    // Mirror ui/theme/Gradients.kt (AccentStart #2E7BFF → AccentEnd #7C5CFF).
    private const val ACCENT_START = 0xFF2E7BFF.toInt()
    private const val ACCENT_END = 0xFF7C5CFF.toInt()

    /**
     * @param headline short all-caps wordmark, e.g. "PROTECTION OFF".
     * @param subtitle one short supporting line, e.g. "Blocking has stopped".
     */
    fun build(context: Context, headline: String, subtitle: String): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Diagonal brand gradient backdrop.
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, W.toFloat(), H.toFloat(),
                ACCENT_START, ACCENT_END, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        // A soft lighter glow top-left so the flat gradient reads as a lit surface, not a swatch.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                W * 0.28f, H * 0.32f, W * 0.55f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), glow)

        // White shield on the left, vertically centered.
        val shieldSize = (H * 0.62f).toInt()
        val shieldBitmap = ContextCompat.getDrawable(context, R.drawable.ic_shield_white)
            ?.toBitmap(shieldSize, shieldSize)
        if (shieldBitmap != null) {
            val shieldLeft = W * 0.08f
            val shieldTop = (H - shieldSize) / 2f
            // Drop shadow under the shield for depth.
            val shieldShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = android.graphics.PorterDuffColorFilter(
                    0x552A1247, android.graphics.PorterDuff.Mode.SRC_IN,
                )
            }
            canvas.drawBitmap(shieldBitmap, shieldLeft + 6f, shieldTop + 10f, shieldShadow)
            canvas.drawBitmap(shieldBitmap, shieldLeft, shieldTop, null)
        }

        val textLeft = W * 0.40f
        // Never let the wordmark run off the right edge — shrink to fit the space beside the shield.
        val maxTextWidth = W - textLeft - W * 0.06f

        // Headline wordmark (auto-shrunk to fit maxTextWidth).
        val headlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            letterSpacing = 0.02f
            setShadowLayer(10f, 0f, 4f, 0x552A1247)
        }
        fitTextSize(headlinePaint, headline, maxTextWidth, H * 0.20f)
        // Subtitle (also fit, though it's usually well within bounds).
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF2FFFFFF.toInt()
            typeface = Typeface.DEFAULT
            setShadowLayer(6f, 0f, 2f, 0x442A1247)
        }
        fitTextSize(subtitlePaint, subtitle, maxTextWidth, H * 0.095f)

        // Vertically center the two lines as a block against the shield.
        val headlineHeight = headlinePaint.descent() - headlinePaint.ascent()
        val subtitleHeight = subtitlePaint.descent() - subtitlePaint.ascent()
        val gap = H * 0.05f
        val blockHeight = headlineHeight + gap + subtitleHeight
        val blockTop = (H - blockHeight) / 2f
        val headlineBaseline = blockTop - headlinePaint.ascent()
        val subtitleBaseline = blockTop + headlineHeight + gap - subtitlePaint.ascent()

        canvas.drawText(headline, textLeft, headlineBaseline, headlinePaint)
        canvas.drawText(subtitle, textLeft, subtitleBaseline, subtitlePaint)

        return bitmap
    }

    /** Sets [paint].textSize to [desired], then shrinks it just enough that [text] fits [maxWidth]. */
    private fun fitTextSize(paint: Paint, text: String, maxWidth: Float, desired: Float) {
        paint.textSize = desired
        val measured = paint.measureText(text)
        if (measured > maxWidth) paint.textSize = desired * (maxWidth / measured)
    }
}
