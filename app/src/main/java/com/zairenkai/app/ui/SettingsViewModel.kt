// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Settings and live log.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings
    private val _log = MutableStateFlow<List<LogRecord>>(emptyList())
    val log: StateFlow<List<LogRecord>> = _log.asStateFlow()
    private var seq = 0L
    private var streaming = false

    fun setLite(v: Boolean) = viewModelScope.launch { c.settings.setLite(v) }
    fun setMitigation(v: Boolean) = viewModelScope.launch { c.settings.setMitigation(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { c.settings.setDynamicColor(v) }
    fun setThemeMode(v: String) = viewModelScope.launch { c.settings.setThemeMode(v) }
    fun setAccent(v: String) = viewModelScope.launch { c.settings.setAccent(v) }
    fun setGlass(v: Boolean) = viewModelScope.launch { c.settings.setGlass(v) }
    fun setGlassOpacity(v: Float) = viewModelScope.launch { c.settings.setGlassOpacity(v) }
    fun setGlassBlur(v: Float) = viewModelScope.launch { c.settings.setGlassBlur(v) }
    fun setGlassTint(v: Float) = viewModelScope.launch { c.settings.setGlassTint(v) }
    fun setLogLevel(level: String) = viewModelScope.launch {
        c.settings.setLogLevel(level)
        runCatching { c.repository.setLogLevel(level) }
    }

    fun startLog() {
        if (streaming) return
        streaming = true
        viewModelScope.launch {
            while (isActive && streaming) {
                val r = runCatching { c.repository.log(seq) }.getOrNull()
                if (r != null && r.ok) {
                    if (r.records.isNotEmpty()) {
                        _log.value = (_log.value + r.records).takeLast(300)
                        seq = r.nextSeq
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun stopLog() { streaming = false }
}
