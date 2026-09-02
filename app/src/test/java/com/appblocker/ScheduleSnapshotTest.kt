package com.appblocker

import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleSnapshot
import com.appblocker.data.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schedules must survive the trip to prefs and back, or come back as nothing at all. */
class ScheduleSnapshotTest {

    private val time = Schedule(
        id = 3, name = "Evenings", type = ScheduleType.TIME, enabled = true,
        startMinutes = 1_260, endMinutes = 1_440, daysMask = 0b0111110,
        packages = listOf("com.a", "com.b"),
    )
    private val place = Schedule(
        id = 9, name = "At work", type = ScheduleType.LOCATION, enabled = false,
        latitude = 52.52, longitude = 13.405, radiusMeters = 200, packages = emptyList(),
    )

    @Test fun aScheduleSurvivesTheRoundTrip() =
        assertEquals(listOf(time), ScheduleSnapshot.decode(ScheduleSnapshot.encode(listOf(time))))

    @Test fun everyFieldSurvives() {
        val back = ScheduleSnapshot.decode(ScheduleSnapshot.encode(listOf(time, place)))
        assertEquals(listOf(time, place), back)
        // Named explicitly: a silent default here is a block the owner never set, or a lost one.
        assertEquals(0b0111110, back[0].daysMask)
        assertEquals(listOf("com.a", "com.b"), back[0].packages)
        assertEquals(52.52, back[1].latitude, 0.0)
        assertTrue(!back[1].enabled)
    }

    @Test fun noSchedulesRoundTripsToNothing() {
        assertEquals(emptyList<Schedule>(), ScheduleSnapshot.decode(ScheduleSnapshot.encode(emptyList())))
        assertEquals(emptyList<Schedule>(), ScheduleSnapshot.decode(null))
        assertEquals(emptyList<Schedule>(), ScheduleSnapshot.decode(""))
    }

    /** A row that cannot be read is dropped, never half-built into a block nobody asked for. */
    @Test fun aMalformedRowIsDroppedNotGuessed() {
        val good = ScheduleSnapshot.encode(listOf(time))
        assertEquals(emptyList<Schedule>(), ScheduleSnapshot.decode("nonsense"))
        assertEquals(emptyList<Schedule>(), ScheduleSnapshot.decode(good.replace("TIME", "NOT_A_TYPE")))
        assertEquals(listOf(time), ScheduleSnapshot.decode(good + "\u001e" + "broken"))
    }

    /** A name with a separator in it must not shift every later field by one. */
    @Test fun aSeparatorInTheNameCannotCorruptTheRow() {
        val nasty = time.copy(name = "Ev\u001fen\u001eings")
        val back = ScheduleSnapshot.decode(ScheduleSnapshot.encode(listOf(nasty)))
        assertEquals(1, back.size)
        assertEquals(nasty.packages, back[0].packages)
        assertEquals(nasty.startMinutes, back[0].startMinutes)
    }
}
