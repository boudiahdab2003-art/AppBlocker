package com.appblocker.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.appblocker.MainActivity
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.SessionClock
import com.appblocker.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile to turn Quick Block on/off from the pull-down shade without opening the
 * app. Mirrors the in-app Start/Stop toggle: resuming is always allowed, pausing is refused
 * while a Strict session is running (Strict forces blocking on regardless of the pause flag).
 */
class QuickBlockTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class State(
        val active: Boolean,
        val strict: Boolean,
        val configured: Boolean,
        val paused: Boolean,
    )

    override fun onStartListening() = refresh()
    override fun onTileAdded() = refresh()

    override fun onClick() {
        scope.launch {
            val s = readState()
            when {
                s.strict -> {} // locked on — can't pause while Strict Mode is running
                !s.configured -> { openApp(); return@launch } // nothing to block yet: open the app
                else -> SettingsStore.setQuickBlockPaused(applicationContext, !s.paused)
            }
            render(readState())
        }
    }

    private fun refresh() {
        scope.launch { render(readState()) }
    }

    /** One-shot read of the current Quick Block state (DB reads are suspend). */
    private suspend fun readState(): State {
        val db = BlockerDatabase.get(applicationContext)
        val paused = SettingsStore.quickBlockPaused(applicationContext)
        val focus = db.focusDao().get().first()
        val strict = focus != null && SessionClock.remaining(
            focus.realtimeStartMillis, focus.realtimeEndMillis,
            focus.startTimeMillis, focus.endTimeMillis,
        ) > 0L
        val configured =
            db.appRuleDao().getAll().first().any { it.isBlocked } ||
                db.blockedKeywordDao().getAll().first().isNotEmpty()
        return State(active = strict || (configured && !paused), strict, configured, paused)
    }

    private suspend fun render(s: State) = withContext(Dispatchers.Main) {
        val tile = qsTile ?: return@withContext
        tile.state = if (s.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Quick Block"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                s.strict -> "Strict"
                !s.configured -> "Set up"
                s.paused -> "Paused"
                else -> "On"
            }
        }
        tile.updateTile()
    }

    /** Open the app so the user can pick apps/words when nothing is configured yet. */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
