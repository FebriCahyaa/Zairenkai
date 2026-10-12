// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * FPS recorder screens: the watch list, the installed-app picker, the
 * recorder service state and the saved sessions.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.data.fps.FpsLive
import com.zairenkai.app.data.fps.FpsMonitorService
import com.zairenkai.app.data.fps.FpsRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val pkg: String, val label: String)

class FpsViewModel(private val c: AppContainer) : ViewModel() {
    val watch: StateFlow<Set<String>> = c.settings.settings.map { it.fpsWatch }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val sessions: StateFlow<List<FpsRecording>?> = c.fpsStore.sessions
    val live: StateFlow<FpsLive> = FpsMonitorService.live

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    init {
        viewModelScope.launch { c.fpsStore.load() }
    }

    fun reload() {
        viewModelScope.launch { c.fpsStore.load() }
    }

    /** Launchable apps other than this one, by label. */
    fun loadApps() {
        if (_apps.value != null) return
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) {
                val pm = c.appContext.packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first != c.appContext.packageName }
                    .distinctBy { it.first }
                    .map { InstalledApp(it.first, it.second) }
                    .sortedBy { it.label.lowercase() }
            }
        }
    }

    fun setWatched(pkg: String, on: Boolean) {
        viewModelScope.launch {
            val now = watch.value
            c.settings.setFpsWatch(if (on) now + pkg else now - pkg)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { c.fpsStore.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { c.fpsStore.clear() }
    }
}
