package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.Updater
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

    val currentVersion: String get() = Updater.current(getApplication())

    private var checkedOnce = false

    /** Silent check used on app launch — only surfaces a result if an update exists. */
    fun checkOnLaunch() {
        if (checkedOnce) return
        checkedOnce = true
        viewModelScope.launch {
            val latest = Updater.latest() ?: return@launch
            if (Updater.isNewer(latest.version, currentVersion)) {
                _state.value = UpdateState.Available(latest)
            }
        }
    }

    /** Manual check from the Profile screen — always reports a result. */
    fun check() {
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

    fun downloadAndInstall(release: Updater.Release) {
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            Updater.requestInstallPermission(ctx)
            return
        }
        _state.value = UpdateState.Downloading(0)
        viewModelScope.launch {
            runCatching {
                val file = Updater.download(ctx, release.apkUrl) { pct ->
                    _state.value = UpdateState.Downloading(pct)
                }
                Updater.install(ctx, file)
                _state.value = UpdateState.Available(release) // back to actionable state after launching installer
            }.onFailure {
                _state.value = UpdateState.Error("Download failed — try again.")
            }
        }
    }
}
