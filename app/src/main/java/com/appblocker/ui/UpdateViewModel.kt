package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.Dist
import com.appblocker.data.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Error(val message: String) : UpdateState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    // The big "Update available" popup. Fed ONLY by the launch check, so the user sees it
    // exactly once per app open — manual checks from Profile just update that row's text.
    private val _prompt = MutableStateFlow<Updater.Release?>(null)
    val prompt: StateFlow<Updater.Release?> = _prompt

    val currentVersion: String get() = Updater.current(getApplication())

    private var checkedOnce = false

    /** The release the user asked for while install permission was still missing. Held so that
     *  granting it and coming back continues the update, instead of the tap having done nothing. */
    private var awaitingInstallPermission: Updater.Release? = null

    private var downloadJob: Job? = null

    /** Silent check used on app launch — only surfaces a result if an update exists. */
    fun checkOnLaunch() {
        if (!Dist.SELF_UPDATE) return // Play builds update through Google Play
        if (checkedOnce) return
        checkedOnce = true
        viewModelScope.launch {
            val latest = Updater.latest() ?: return@launch
            if (Updater.isNewer(latest.version, currentVersion)) {
                _state.value = UpdateState.Available(latest)
                _prompt.value = latest
            }
        }
    }

    /** Closes the once-per-launch "Update available" popup. */
    fun dismissPrompt() {
        _prompt.value = null
    }

    /** Manual check from the Profile screen — always reports a result. */
    fun check() {
        if (!Dist.SELF_UPDATE) return // Play builds update through Google Play
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val latest = Updater.latest()
            _state.value = when {
                latest == null -> UpdateState.Error("Couldn't reach the update server.")
                Updater.isNewer(latest.version, currentVersion) -> UpdateState.Available(latest)
                else -> UpdateState.UpToDate
            }
        }
    }

    /** Close the "Update available" prompt for this launch without installing. */
    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /**
     * Call whenever the app comes back to the foreground. If the user left to grant install
     * permission, this resumes the update they asked for.
     *
     * Without it the first update on any phone dead-ends: the tap opens the permission screen and
     * returns, the once-per-launch prompt has already been dismissed and will not come back, and
     * the state still reads "Available" — so from the user's side, Update simply did nothing.
     */
    fun onResumed() {
        val pending = awaitingInstallPermission ?: return
        if (!Updater.canInstall(getApplication())) return
        awaitingInstallPermission = null
        downloadAndInstall(pending)
    }

    fun downloadAndInstall(release: Updater.Release) {
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            awaitingInstallPermission = release
            _state.value = if (Updater.requestInstallPermission(ctx)) {
                UpdateState.Error("Allow AppBlocker to install apps, then come back here.")
            } else {
                // No such settings screen on this phone — say so rather than looking broken.
                awaitingInstallPermission = null
                UpdateState.Error("Allow \"install unknown apps\" for AppBlocker in Settings first.")
            }
            return
        }
        _state.value = UpdateState.Downloading(0)
        downloadJob = viewModelScope.launch {
            runCatching {
                val file = Updater.download(ctx, release.apkUrl) { pct ->
                    _state.value = UpdateState.Downloading(pct)
                }
                Updater.install(ctx, file)
                // Installer is on screen now — go quiet instead of re-raising "Update available"
                // (which used to re-pop the dialog after every install/cancel).
                _state.value = UpdateState.Idle
            }.onFailure { t ->
                // A cancellation is the user's own doing and cancelDownload has already set the
                // state; runCatching swallows it like any other throwable, so re-throw rather
                // than reporting the cancel back to them as a failure.
                if (t is CancellationException) throw t
                _state.value = UpdateState.Error("Download failed — try again.")
            }
        }
    }

    /** Abandons a download in progress. The progress dialog is modal, so without this a slow or
     *  stalled transfer leaves the user with nothing to tap. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateState.Idle
    }
}
