package com.appblocker

import com.appblocker.data.dayStampOf
import com.appblocker.ui.combine
import com.appblocker.ui.toPickerDate
import com.appblocker.ui.utcDateToDayStamp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The date picker's timezone conversion, in both directions.
 *
 * **A Material date picker speaks UTC and the rest of this app speaks local time**, so a date can
 * lose or gain a day at every crossing — and the failure is invisible on a machine set to UTC,
 * which is what a CI emulator is. The owner is in Germany, two hours ahead in summer, where the
 * mistake shows up as a picker that opens on yesterday and a relapse recorded on the wrong day.
 *
 * Every case below runs under an explicit timezone rather than the machine's, east and west, so it
 * measures the thing it claims to.
 */
class PickerDateTest {

    private fun inZone(id: String, body: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            body()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /** A real local instant, built inside whatever zone is currently set. */
    private fun localInstant(year: Int, month: Int, dayOfMonth: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, dayOfMonth, hour, minute)
        }.timeInMillis

    /**
     * **The bug this file exists for.** Half past midnight on the 8th, in Berlin, is 22:30 UTC on
     * the 7th. Handed to the picker unconverted it opens on the 7th, and the owner corrects a date
     * that was never wrong.
     */
    @Test
    fun `a time just after local midnight still opens the picker on that day`() {
        inZone("Europe/Berlin") {
            val justAfterMidnight = localInstant(2026, Calendar.AUGUST, 8, 0, 30)
            assertEquals(2026 * 1000 + 220, utcDateToDayStamp(toPickerDate(justAfterMidnight)))
        }
    }

    /** …and the mirror image: late evening west of Greenwich must not roll forward. */
    @Test
    fun `a late evening west of Greenwich stays on its own day`() {
        inZone("America/New_York") {
            val lateEvening = localInstant(2026, Calendar.AUGUST, 8, 23, 30)
            assertEquals(2026 * 1000 + 220, utcDateToDayStamp(toPickerDate(lateEvening)))
        }
    }

    /**
     * The round trip, which is the property the two screens actually depend on: an instant goes to
     * the picker and comes back as the same calendar day it started on.
     */
    @Test
    fun `an instant survives a trip through the picker on the same day`() {
        for (zone in listOf("Europe/Berlin", "America/New_York", "UTC", "Pacific/Kiritimati")) {
            inZone(zone) {
                for (hour in listOf(0, 1, 11, 13, 23)) {
                    val instant = localInstant(2026, Calendar.MARCH, 17, hour, 45)
                    assertEquals(
                        "$zone at ${hour}h: the day changed on the way through the picker",
                        dayStampOf(instant),
                        utcDateToDayStamp(toPickerDate(instant)),
                    )
                }
            }
        }
    }

    /** What the picker hands back becomes a real local moment on the date that was tapped, at the
     *  time that was chosen — not the same clock time shifted by the offset. */
    @Test
    fun `the chosen date and time become that local moment`() {
        inZone("Europe/Berlin") {
            val picked = toPickerDate(localInstant(2026, Calendar.AUGUST, 8, 12, 0))
            val combined = combine(picked, hour = 21, minute = 14)
            val c = Calendar.getInstance().apply { timeInMillis = combined }
            assertEquals(2026, c.get(Calendar.YEAR))
            assertEquals(Calendar.AUGUST, c.get(Calendar.MONTH))
            assertEquals(8, c.get(Calendar.DAY_OF_MONTH))
            assertEquals(21, c.get(Calendar.HOUR_OF_DAY))
            assertEquals(14, c.get(Calendar.MINUTE))
        }
    }
}
