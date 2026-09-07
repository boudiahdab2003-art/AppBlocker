package com.appblocker.data

import android.content.Context
import android.util.Log
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * **Keeps the watcher's fallbacks current even while the watcher is dead.**
 *
 * Four snapshots defend the seconds between the accessibility service binding and Room's first
 * emission — [RuleSnapshot] (blocked apps), [StrictSnapshot] (a running Strict session),
 * the blocked words and [ScheduleSnapshot]. They are the only thing enforcing anything in that
 * window, and on 5 Sep 2026 all four were written from exactly one place: the service's own
 * `combine` collector.
 *
 * ⚠️ **So the defence went stale precisely when it was needed.** The watcher on this phone dies
 * around thirty times a day. Every rule the owner changes while it is dead — blocking a new app
 * because he noticed blocking had stopped, starting a Strict session, adding a word — lands in
 * Room and **nowhere else**, because the only writer of the fallback was not running. The next
 * bind then enforces the previous state through the very window the fallback exists to cover.
 * `NewAppWatcher` and `PackageInstallReceiver` write rules from outside the service too, so with
 * `autoBlockNew` on a newly installed app could be blocked in Room and absent from the snapshot.
 *
 * The fix is to stop making the fallback a side effect of *watching* and make it a consequence of
 * *writing*. Room's [InvalidationTracker] fires on any change to a table from anywhere in the
 * process, so a future writer cannot forget to update the snapshot — there is nothing to remember.
 * Registration hangs off [BlockerDatabase.get], the one door every component goes through to
 * reach these tables, which is what makes "anywhere in the process" true rather than hopeful.
 *
 * The service still writes them on its own flow. That is not redundant: it also refreshes the
 * in-memory copies it decides from, and it is the path that runs while blocking is healthy. Both
 * writers compare before writing, so the duplicate costs one read.
 */
internal object Snapshots {

    /** The tables every snapshot is derived from. A snapshot added without its table listed here
     *  would be written only while the service is alive, which is the bug this object exists for
     *  — `CodeShapeTest` fails the build if the two lists drift apart. */
    val WATCHED = arrayOf("app_rules", "focus_state", "blocked_keywords", "schedules")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false

    /**
     * Registers the observer once per process. Called from [BlockerDatabase.get] rather than an
     * Application class: there is no custom Application, and adding one would put a new surface
     * on every cold start of a process that may only be delivering a broadcast.
     */
    fun start(db: BlockerDatabase, context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        // Room does real work when the first observer is added, and `get` is called from the main
        // thread. Off the main thread, and a first refresh so a snapshot written by an older build
        // — or never written at all — is corrected without waiting for the next edit.
        scope.launch {
            runCatching { db.invalidationTracker.addObserver(observer(app)) }
                .onFailure { Log.w(TAG, "snapshot observer not registered", it) }
            refresh(app)
        }
    }

    private fun observer(app: Context) = object : InvalidationTracker.Observer(WATCHED) {
        override fun onInvalidated(tables: Set<String>) {
            scope.launch { refresh(app) }
        }
    }

    /**
     * Recomputes all four fallbacks from the database and stores the ones that changed.
     *
     * Never throws: it runs on a background scope where an escape would be silent, and a failure
     * here must not be able to take the database's own invalidation machinery down with it.
     *
     * ⚠️ **One guard per snapshot, and that is the whole point of [step].** This used to be a
     * single `runCatching` around all four, so a throw reading the *first* — a DAO query, a
     * corrupt row, a disk error — silently skipped the other three, and the log line said
     * "snapshot refresh failed" without saying which. These four are the only enforcement in the
     * window between the watcher binding and Room's first emission, so three of them going stale
     * because the first read failed is the exact failure this object was built to end, arriving by
     * a different door.
     *
     * `BugReportSender.appContext` already learned this and says so in its own comment: *"Each
     * field is read on its own. This used to be one runCatching around the whole map, which meant
     * ONE throw among ten calls produced an empty map with nothing to say why — and that is
     * exactly what happened."* Same shape, two files apart, and it was never grepped for.
     */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        // Outside the per-step guards on purpose: with no database there is nothing to refresh,
        // and four identical failures would say less than one.
        val db = runCatching { BlockerDatabase.get(app) }.getOrNull() ?: run {
            Log.w(TAG, "snapshot refresh skipped: no database")
            return
        }

        step("blocked apps") {
            val blocked = RuleSnapshot.encode(db.appRuleDao().getAll().first())
            if (blocked != SettingsStore.blockedSnapshot(app)) {
                SettingsStore.setBlockedSnapshot(app, blocked)
            }
        }

        step("strict session") {
            val focus = db.focusDao().get().first()
            val strict = StrictSnapshot.Session(
                realtimeStart = focus?.realtimeStartMillis ?: 0L,
                realtimeEnd = focus?.realtimeEndMillis ?: 0L,
                wallStart = focus?.startTimeMillis ?: 0L,
                wallEnd = focus?.endTimeMillis ?: 0L,
                bootCount = focus?.bootCount ?: -1,
            )
            if (strict != SettingsStore.strictSnapshot(app)) {
                SettingsStore.setStrictSnapshot(app, strict)
            }
        }

        step("keywords") {
            val words = db.blockedKeywordDao().getAll().first().map { it.keyword }.toSet()
            if (words != SettingsStore.keywordSnapshot(app)) {
                SettingsStore.setKeywordSnapshot(app, words)
            }
        }

        step("schedules") {
            val schedules = db.scheduleDao().getAll().first()
            if (schedules != SettingsStore.scheduleSnapshot(app)) {
                SettingsStore.setScheduleSnapshot(app, schedules)
            }
        }
    }

    /** One snapshot's refresh, isolated so a failure cannot take the others with it — and named,
     *  so the log says which one. Modelled on `BugReportSender.appContext`'s `field`. */
    private inline fun step(what: String, write: () -> Unit) {
        runCatching(write).onFailure { Log.w(TAG, "snapshot refresh failed: $what", it) }
    }

    private const val TAG = "Snapshots"
}
