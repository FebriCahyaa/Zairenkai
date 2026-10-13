// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Drives the Update app screen: check the right channel for the installed
 * build, download the APK with progress, then install it. All network work is
 * user-triggered (or the explicit auto-check), never a background service.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.data.update.CheckResult
import com.zairenkai.app.data.update.ReleaseManifest
import com.zairenkai.app.data.update.UpdateChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What the Update screen shows right now. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val versionName: String) : UpdateUiState
    data class Available(val manifest: ReleaseManifest) : UpdateUiState
    data class Downloading(val manifest: ReleaseManifest, val progress: Float) : UpdateUiState
    data class ReadyToInstall(val manifest: ReleaseManifest, val file: File) : UpdateUiState
    data class Failed(val reason: CheckResult.Reason) : UpdateUiState
    /** The download failed (network, or the SHA-256 did not match). */
    data object DownloadFailed : UpdateUiState
}

class UpdateViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings
    val channel: UpdateChannel = UpdateChannel.forBuildType(BuildConfig.BUILD_TYPE)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Check once; used by the auto-check on screen open and the manual button. */
    fun check() {
        if (_state.value is UpdateUiState.Checking || _state.value is UpdateUiState.Downloading) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            _state.value = when (val r = c.updater.check(channel, BuildConfig.VERSION_CODE)) {
                is CheckResult.Available -> UpdateUiState.Available(r.manifest)
                is CheckResult.UpToDate -> UpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
                is CheckResult.Failed -> UpdateUiState.Failed(r.reason)
            }
        }
    }

    fun download(manifest: ReleaseManifest) {
        _state.value = UpdateUiState.Downloading(manifest, 0f)
        viewModelScope.launch {
            val file = c.updater.download(manifest) { p ->
                _state.value = UpdateUiState.Downloading(manifest, p)
            }
            _state.value = if (file != null) UpdateUiState.ReadyToInstall(manifest, file)
            else UpdateUiState.DownloadFailed
        }
    }

    fun install(file: File) = c.updater.install(file)

    fun setAutoUpdateCheck(v: Boolean) = viewModelScope.launch { c.settings.setAutoUpdateCheck(v) }
}
