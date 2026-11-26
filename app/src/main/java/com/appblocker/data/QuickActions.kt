package com.appblocker.data

import android.content.Context
import java.util.Calendar

/**
 * **The one-tap actions that only ever make blocking stronger** — shared by the smart suggestions
 * on the Blocking tab (invariant 113) and the AI Coach's action buttons (invariant 114).
 *
 * One place, so the two can never disagree about what "lock" or "limit" means:
 *  - a lock goes through [ItemLocks.lockFor], which lengthens and never shortens;
 *  - a daily limit already set is only ever lowered, never raised;
 *  - Strict is started or lengthened, never shortened ([StrictSessions]).
 * Nothing here removes, pauses or loosens anything, and nothing here should ever be made to.
 */
object QuickActions {

    /** Locks [pkg] for [durationMs] (or lengthens its lock). */
    fun lockApp(ctx: Context, pkg: String, durationMs: Long) =
        ItemLocks.lockFor(ctx, ItemLocks.Kind.APP, pkg, durationMs)

    /** Locks [pkg] until the next [SmartSuggestions.MORNING_HOUR]:00. */
    fun lockAppUntilMorning(ctx: Context, pkg: String) {
        val now = Calendar.getInstance()
        lockApp(
            ctx, pkg,
            SmartSuggestions.msUntilMorning(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)),
        )
    }

    /** The daily limit a schedule should hold after asking for [wanted] when [existing] is set:
     *  only ever the lower of the two. */
    fun lowerLimit(existing: Int?, wanted: Int): Int = minOf(existing ?: Int.MAX_VALUE, wanted)

    /**
     * A daily usage limit of [minutes] on [pkg], kept in its own schedule named
     * [scheduleName]. An existing one is enabled again and only ever lowered.
     */
    suspend fun dailyLimit(ctx: Context, pkg: String, scheduleName: String, minutes: Int) {
        if (minutes <= 0) return
        val dao = BlockerDatabase.get(ctx).scheduleDao()
        val existing = dao.findByName(scheduleName)
        dao.upsert(
            Schedule(
                id = existing?.id ?: 0L,
                name = scheduleName,
                type = ScheduleType.USAGE_LIMIT,
                enabled = true,
                limitMinutes = lowerLimit(existing?.limitMinutes, minutes),
                packages = listOf(pkg),
            ),
        )
    }

    /** Starts Strict Mode for [minutes], or lengthens the running session by that much. */
    suspend fun strict(ctx: Context, minutes: Int) = StrictSessions.startOrExtend(ctx, minutes)
}
