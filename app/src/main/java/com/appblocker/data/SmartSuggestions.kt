package com.appblocker.data

/**
 * **Smart suggestions: one-tap buttons for the apps he uses most** (invariant 113).
 *
 * 26 Nov 2026, his ask: "smart suggestions as fast buttons with emojis and app logo based on what i
 * use most". His picks: **"Smart mix"** — each button chooses the action that fits that app —
 * **no undo** (a tap is a lock, like every other lock in the app), and **the top of the Blocking
 * tab**, three buttons.
 *
 * Every action only ever STRENGTHENS blocking, so none of them is refused during Strict:
 *  - [Action.LOCK_TONIGHT] 🌙 — late at night (from [NIGHT_FROM_HOUR] until [NIGHT_UNTIL_HOUR]), an
 *    app he has already spent [MIN_TODAY] minutes in today: locked until [MORNING_HOUR]:00.
 *  - [Action.DAILY_LIMIT] ⏱ — an app averaging [LIMIT_FROM_WEEK_AVG]+ minutes a day this week and
 *    not limited yet: a daily limit of half that average, in quarter hours, 15–60 minutes.
 *  - [Action.LOCK_HOUR] 🔒 — anything else he has used [MIN_TODAY]+ minutes today: locked an hour.
 *
 * Pure: the screen gathers the numbers, this decides. The phone-only parts (which apps are
 * essential, launchable, already locked) arrive as [eligible].
 */
object SmartSuggestions {

    enum class Action { LOCK_HOUR, LOCK_TONIGHT, DAILY_LIMIT }

    data class Suggestion(
        val pkg: String,
        val action: Action,
        val minutesToday: Int,
        val weekAvgMinutes: Int,
        /** Minutes a day, for [Action.DAILY_LIMIT]; 0 otherwise. */
        val limitMinutes: Int = 0,
        /** How long [Action.LOCK_HOUR] locks for — sized to the use, not always an hour. */
        val lockMinutes: Int = 60,
    )

    const val MAX = 3
    const val MIN_TODAY = 15
    const val LIMIT_FROM_WEEK_AVG = 45
    const val NIGHT_FROM_HOUR = 22
    const val NIGHT_UNTIL_HOUR = 5
    const val MORNING_HOUR = 6
    const val LOCK_HOUR_MS = 60 * 60_000L

    fun isNight(hour: Int): Boolean = hour >= NIGHT_FROM_HOUR || hour < NIGHT_UNTIL_HOUR

    /** Half the daily average, down to a quarter hour, kept within 15–60 minutes. */
    fun limitFor(weekAvgMinutes: Int): Int = ((weekAvgMinutes / 2) / 15 * 15).coerceIn(15, 60)

    /**
     * How long a lock should be for an app used [todayMinutes] today — v2 (invariant 115): the
     * complaint was "always the same 1 h". A three-hour binge gets three hours; a short one, one.
     */
    fun lockMinutesFor(todayMinutes: Int): Int = when {
        todayMinutes >= 120 -> 180
        todayMinutes >= 60 -> 120
        else -> 60
    }

    /**
     * Messengers people need, which the category map files under Social media with the feeds — v2's
     * first fix: "it suggests apps I need". Never suggested, whatever their minutes.
     */
    val MESSENGERS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
        "org.thoughtcrime.securesms", "com.facebook.orca", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.android.mms", "com.viber.voip", "com.discord",
        "com.tencent.mm", "jp.naver.line.android", "com.skype.raider", "com.microsoft.teams",
        "com.Slack", "com.google.android.gm", "com.google.android.apps.dynamite",
    )

    /** The categories worth a suggestion: the feeds, the videos and the games. */
    val DISTRACTING = setOf(AppCategory.SOCIAL, AppCategory.ENTERTAINMENT, AppCategory.GAMES)

    /**
     * **Only distracting apps** (his pick, 26 Nov 2026): social media, video and games — and
     * browsers, which are how a late night becomes a relapse. Never a messenger, maps, banking,
     * school, work or a tool. Pure.
     */
    fun distracting(pkg: String, category: AppCategory, isBrowser: Boolean): Boolean =
        pkg !in MESSENGERS && (isBrowser || category in DISTRACTING)

    /**
     * @param today minutes per package today.
     * @param weekTotal minutes per package over the past seven full days
     *   (`UsageTracker.pastWeekAppMinutes` — not the week before it).
     * @param hour the hour of the day now, 0–23.
     * @param eligible may this package be suggested at all (launchable, not essential, not
     *   AppBlocker, not already blocked or locked).
     * @param limits the strictest daily limit each package already has, in minutes. An app that
     *   has used its limit up today is blocked until tomorrow already, so it is not suggested at
     *   all — a button on an app that cannot be opened is a wasted button. One with a limit left
     *   is never offered a second limit.
     */
    fun suggest(
        today: Map<String, Int>,
        weekTotal: Map<String, Int>,
        hour: Int,
        eligible: (String) -> Boolean,
        limits: Map<String, Int> = emptyMap(),
        /** Browsers are offered only at night: by day they are often study and work. */
        browsers: Set<String> = emptySet(),
    ): List<Suggestion> {
        val night = isNight(hour)
        return (today.keys + weekTotal.keys)
            .asSequence()
            .filter(eligible)
            .filter { pkg -> night || pkg !in browsers }
            .filter { pkg -> limits[pkg]?.let { (today[pkg] ?: 0) < it } ?: true }
            .map { pkg ->
                val t = today[pkg] ?: 0
                val avg = (weekTotal[pkg] ?: 0) / 7
                Triple(pkg, t, avg)
            }
            // Today counts double: the button is about now.
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second * 2 + it.third }.thenBy { it.first })
            .mapNotNull { (pkg, t, avg) ->
                when {
                    night && t >= MIN_TODAY -> Suggestion(pkg, Action.LOCK_TONIGHT, t, avg)
                    avg >= LIMIT_FROM_WEEK_AVG && pkg !in limits ->
                        Suggestion(pkg, Action.DAILY_LIMIT, t, avg, limitFor(avg))
                    t >= MIN_TODAY -> Suggestion(pkg, Action.LOCK_HOUR, t, avg, lockMinutes = lockMinutesFor(t))
                    else -> null
                }
            }
            .take(MAX)
            .toList()
    }

    /** Milliseconds from [hour]:[minute] until the next [MORNING_HOUR]:00 — tonight's lock. */
    fun msUntilMorning(hour: Int, minute: Int): Long {
        val nowMin = hour * 60 + minute
        val target = MORNING_HOUR * 60
        val mins = if (nowMin < target) target - nowMin else 24 * 60 - nowMin + target
        return mins * 60_000L
    }
}
