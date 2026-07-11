package com.appblocker.data

/**
 * A daily "how did your phone use feel?" check-in: a 0–100 rating (0 = very distracted,
 * 100 = in control) plus an optional short note. Stored per day-stamp (rating_/note_ key
 * pairs, pruned after ~35 days) so a short history is kept. Rating -1 = not checked in yet.
 */
import android.content.Context
import androidx.compose.ui.graphics.Color

object MoodStore {
    private const val PREFS = "mood_checkins"
    private const val KEEP_DAYS = 35

    fun todayRating(context: Context): Int =
        prefs(context).getInt("rating_${todayStamp()}", -1)

    fun todayNote(context: Context): String =
        prefs(context).getString("note_${todayStamp()}", "") ?: ""

    /** Check-ins for the last [days] days as (daysAgo, rating, note), newest first —
     *  only days that were actually rated. Feeds the AI Coach's context. */
    fun history(context: Context, days: Int = 7): List<Triple<Int, Int, String>> {
        val prefs = prefs(context)
        val today = todayStamp()
        return (0 until days).mapNotNull { ago ->
            val rating = prefs.getInt("rating_${today - ago}", -1)
            if (rating < 0) null
            else Triple(ago, rating, prefs.getString("note_${today - ago}", "") ?: "")
        }
    }

    /** Save today's check-in (rating 0..100 + optional note), pruning stale days. */
    fun setToday(context: Context, rating: Int, note: String) {
        val prefs = prefs(context)
        val today = todayStamp()
        val editor = prefs.edit()
            .putInt("rating_$today", rating.coerceIn(0, 100))
            .putString("note_$today", note.trim())
        prefs.all.keys.forEach { k ->
            val stamp = k.substringAfter('_').toIntOrNull() ?: return@forEach
            if ((k.startsWith("rating_") || k.startsWith("note_")) && today - stamp > KEEP_DAYS) {
                editor.remove(k)
            }
        }
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** A rating's label + colour: Very distracted (pink) → Mixed (violet) → In control (green). */
fun moodLabel(rating: Int): Pair<String, Color> = when {
    rating < 0 -> "Not checked in" to Color(0xFF9AA3B2)
    rating < 34 -> "Very distracted" to Color(0xFFEC4899)
    rating < 67 -> "Mixed" to Color(0xFF7C5CFF)
    else -> "In control" to Color(0xFF22C55E)
}
