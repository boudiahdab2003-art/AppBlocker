package com.appblocker.data

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * **Why Android says AppBlocker's process ended — the record of the killer that already existed.**
 *
 * By 14 Sep 2026 the stoppage history held 48 stoppages and 60 deaths, and not one line could say
 * what killed the watcher. `deaf=false` says the process died, `after=` says what happened just
 * before it; neither says *who*. Every theory in the outage investigation — our own updates, a
 * battery manager, a memory cleaner — was argued from those two fields, while Android had been
 * writing the answer down for every death since API 30 in [ApplicationExitInfo], readable by the
 * app itself with no permission. Nothing here ever asked (invariant 72).
 *
 * It mattered most on 11 Sep 2026: nothing of AppBlocker ran for two days and the switch was found
 * OFF, and the owner confirmed he had not force-stopped it, cleaned memory, cleared Recents or
 * turned on a battery saver. Only the phone's own record can say what did.
 *
 * ## What is kept
 *
 * Content-free, like every instrument that leaves the phone: a reason from a fixed table, the
 * subreason Android prints, how important Android considered the process at that moment, and the
 * phone's own description of the kill with every app-like name replaced. The note is where a
 * manufacturer's cleaner names itself — and also where another app's package could appear.
 *
 * ## Read when an episode opens, not when it ends
 *
 * Android keeps a small ring of these per package. During a long stoppage every process a worker
 * or the alarm starts, and that then dies, adds a record — so by the time blocking comes back the
 * death that began the episode may already have rotated out. [killedBy] is taken when the episode
 * OPENS, and says so when the ring was too full to be sure it saw the first death.
 */
object ProcessExits {

    /** How many records to ask for — Android's own default ring size. */
    const val MAX_READ = 16

    /** Android could not be asked: older than Android 11, or the call failed. Never "none". */
    const val UNREAD = "?"

    /** Asked, and no death of our process was recorded after the last sign of life — which is
     *  exactly what a watcher that stayed alive and went deaf looks like. */
    const val NONE = "none"

    /** Appended when every record Android returned is inside the window: the first death may
     *  have been pushed out of the ring before anyone looked. */
    const val MAYBE_EARLIER = "+earlier?"

    /** Clock tolerance either side of the window. Both stamps come from the same wall clock. */
    internal const val SLACK_MS = 5_000L

    /** Longest note kept. A cleaner names itself in the first few words; the rest is detail. */
    internal const val MAX_NOTE = 60

    private val TOKEN = Regex("""[a-z0-9:@+?-]{1,80}""")

    /**
     * A token as stored, or [UNREAD] when it is anything else. Stoppage lines are stored `|`-separated
     * and `;`-joined, so a value read back from disk is checked before a report is allowed to print it.
     */
    internal fun safeToken(raw: String?): String = raw?.takeIf { TOKEN.matches(it) } ?: UNREAD

    /** One process death, as Android recorded it. */
    data class Exit(
        /** Wall clock of the death. */
        val at: Long,
        /** From [reasonName]'s table. */
        val reason: String,
        /** Android's subreason as lowercase words joined by `-`; for a signalled death, the signal
         *  ([signalOf]); null when it gave neither. */
        val sub: String?,
        /** [RunningAppProcessInfo] importance at the moment of death. */
        val importance: Int,
        /** [sanitiseNote]'s output, or null. */
        val note: String?,
        /**
         * How much memory the process held when it died, in kB ([ApplicationExitInfo.getRss]); 0 when
         * Android recorded none.
         *
         * The one clue the record already carried that nothing printed. By 18 Sep 2026 the owner's
         * phone had force-stopped AppBlocker six times in three days "due to The system loading
         * is…", and the in-use kills that cost him real minutes (`signaled@fg-service`) name no
         * killer at all. A phone short of memory takes the biggest process first, so the size at
         * death is what separates "it was picked for its size" from "it was picked for being us".
         */
        val rssKb: Long = 0L,
    ) {
        /** `low-memory@cached`, `user-requested:force-stop@perceptible` — for a stoppage line. */
        fun token(): String = buildString {
            append(reason)
            if (sub != null) append(':').append(sub)
            append('@').append(importanceName(importance))
        }

        /** A report line that names itself, so it can never pass for a stoppage. */
        fun render(): String =
            "at=${StoppageHistory.label(at)}  EXITED  reason=$reason  sub=${sub ?: "?"}  " +
                "was=${importanceName(importance)}  rss=${sizeName(rssKb)}  note=${note ?: "-"}"
    }

    /** `212mb`, rounded to the nearest megabyte; `?` when Android recorded no size — never `0mb`,
     *  which would read as a process that held nothing. */
    internal fun sizeName(kb: Long): String = if (kb > 0L) "${(kb + 512) / 1024}mb" else "?"

    /** Android's reason code as a word. Unknown codes keep their number rather than a guess. */
    internal fun reasonName(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash-native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package-state-change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package-updated"
        else -> "code-$code"
    }

    private val SUBREASON = Regex("""subreason=\d+\s*\(([^)]*)\)""")

    /**
     * Android's subreason, taken from [ApplicationExitInfo.toString] — **the only public place it
     * appears**: `getSubReason()` is hidden API. `FORCE STOP` becomes `force-stop`.
     *
     * ⚠️ Lowercased with [Locale.ROOT]: this is a machine-read token, not prose (invariant 68).
     * A string that does not have the shape answers null, never a guess.
     */
    internal fun subreasonOf(described: String?): String? {
        val name = described?.let { SUBREASON.find(it)?.groupValues?.get(1) } ?: return null
        val words = name.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words == listOf("unknown")) return null
        return words.joinToString("-")
    }

    /**
     * **Which signal ended the process**, as `sig-9` — for the one reason whose status field is a
     * signal number ([ApplicationExitInfo.REASON_SIGNALED]), and null for every other.
     *
     * On 15 Sep 2026 the first killer the owner's phone ever named was `signaled@fg-service`: ended by
     * a signal while still the blocker, with no subreason and no description. Which signal is the
     * next question — 9 is SIGKILL, the process killed outright; any other number points elsewhere —
     * and [ApplicationExitInfo.getStatus] is public API nothing here read. For every other reason the
     * status is an exit code or zero, so it is never borrowed.
     */
    internal fun signalOf(reason: Int, status: Int): String? =
        if (reason == ApplicationExitInfo.REASON_SIGNALED && status > 0) "sig-$status" else null

    /**
     * How important Android considered the process when it died.
     *
     * The line that separates two different stories: **perceptible or better** means it died while
     * still bound as the blocker; **cached** or **empty** means the binding was already gone and the
     * process was merely reclaimed afterwards — so the real event happened earlier.
     */
    internal fun importanceName(importance: Int): String = when {
        importance <= 0 -> "unknown"
        importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "fg-service"
        importance <= RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance <= RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        importance <= RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        importance <= RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "cant-save-state"
        importance <= RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        importance < RunningAppProcessInfo.IMPORTANCE_GONE -> "empty"
        else -> "gone"
    }

    /** Dotted names: packages, processes, hosts. Kept only when they are the platform's own. */
    private val DOTTED = Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+""")

    private val PLATFORM_PREFIXES = listOf(
        "android.", "com.android.", "com.google.android.", "com.miui.", "com.xiaomi.", "miui.",
        "com.appblocker.",
    )

    /**
     * The phone's own words about the kill, with **every name that is not the platform's or ours
     * replaced by `<app>`** and anything but plain letters, digits and a few separators dropped.
     *
     * The platform names stay because they are the answer — `com.miui.powerkeeper` in a note is
     * the whole finding. Any other dotted name could be an app the owner uses, and those may never
     * leave the phone (see [BugReport]'s contract).
     */
    internal fun sanitiseNote(description: String?): String? {
        val raw = description?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val named = DOTTED.replace(raw) { m ->
            val v = m.value
            if (v == "com.appblocker" || PLATFORM_PREFIXES.any { v.startsWith(it) }) v else "<app>"
        }
        val kept = buildString {
            for (c in named) {
                val plain = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in " ._:/()#<>=-"
                append(if (plain) c else ' ')
            }
        }
        return kept.replace(Regex("\\s+"), " ").trim().take(MAX_NOTE).trim().ifEmpty { null }
    }

    /**
     * **The first death after the watcher's last sign of life**, as a token for a stoppage line.
     *
     * The first, not the newest: everything after it is aftermath — processes a worker or the alarm
     * started while blocking was down, dying in their turn. [UNREAD] when Android could not be
     * asked or there is no sign of life to measure from; [NONE] when it was asked and nothing died.
     * Pure, so every case is tested.
     */
    internal fun killedBy(
        exits: List<Exit>?,
        lastSignOfLife: Long,
        now: Long,
        max: Int = MAX_READ,
    ): String {
        if (exits == null || lastSignOfLife <= 0L) return UNREAD
        val inWindow = exits.filter { it.at >= lastSignOfLife - SLACK_MS && it.at <= now + SLACK_MS }
        val first = inWindow.minByOrNull { it.at } ?: return NONE
        val ringMayHaveRotated = exits.size >= max && inWindow.size == exits.size
        return first.token() + if (ringMayHaveRotated) MAYBE_EARLIER else ""
    }

    /**
     * This app's recorded deaths, newest first — or **null when Android could not be asked**, which
     * is not the same answer as an empty list.
     *
     * One binder call for a handful of small records. Called when an episode opens and when a
     * report is built, both rare, so "keep the battery as it is" holds.
     */
    fun read(context: Context): List<Exit>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            am.getHistoricalProcessExitReasons(null, 0, MAX_READ)
                .filter { it.processName == context.packageName }
                .map {
                    Exit(
                        at = it.timestamp,
                        reason = reasonName(it.reason),
                        sub = subreasonOf(it.toString()) ?: signalOf(it.reason, it.status),
                        importance = it.importance,
                        note = sanitiseNote(it.description),
                        rssKb = it.rss,
                    )
                }
        }.getOrNull()
    }
}
