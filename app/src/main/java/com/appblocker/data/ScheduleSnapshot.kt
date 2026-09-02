package com.appblocker.data

/**
 * Schedules to enforce in the window before Room has answered. See [RuleSnapshot] for the whole
 * argument; this is the third field on that one `combine` flow to get the same treatment, after
 * the rules themselves and [StrictSnapshot].
 *
 * A schedule carries more than a package name, so unlike [RuleSnapshot] this one stores the row
 * rather than reconstructing a stand-in from it. **Any malformed entry is dropped, not guessed.**
 * Dropping one leaves exactly the behaviour that shipped before this file existed \u2014 that schedule
 * unenforced for a few hundred milliseconds \u2014 whereas a half-parsed row could invent a block the
 * owner never set, which is the one outcome worse than the bug being fixed.
 */
internal object ScheduleSnapshot {

    private const val FIELD = '\u001f'
    private const val ROW = '\u001e'
    private const val PKG = ','

    fun encode(schedules: List<Schedule>): String =
        schedules.joinToString(ROW.toString()) { s ->
            listOf(
                s.id, s.name.filterNot { it == FIELD || it == ROW }, s.type.name, s.enabled,
                s.startMinutes, s.endMinutes, s.daysMask, s.limitMinutes, s.limitCount,
                s.wifiSsid.filterNot { it == FIELD || it == ROW }, s.latitude, s.longitude,
                s.radiusMeters, s.packages.joinToString(PKG.toString()),
            ).joinToString(FIELD.toString())
        }

    fun decode(raw: String?): List<Schedule> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(ROW).mapNotNull { row ->
            val f = row.split(FIELD)
            if (f.size != 14) return@mapNotNull null
            runCatching {
                Schedule(
                    id = f[0].toLong(),
                    name = f[1],
                    type = ScheduleType.valueOf(f[2]),
                    enabled = f[3].toBooleanStrict(),
                    startMinutes = f[4].toInt(),
                    endMinutes = f[5].toInt(),
                    daysMask = f[6].toInt(),
                    limitMinutes = f[7].toInt(),
                    limitCount = f[8].toInt(),
                    wifiSsid = f[9],
                    latitude = f[10].toDouble(),
                    longitude = f[11].toDouble(),
                    radiusMeters = f[12].toInt(),
                    packages = if (f[13].isEmpty()) emptyList() else f[13].split(PKG),
                )
            }.getOrNull()
        }
    }
}
