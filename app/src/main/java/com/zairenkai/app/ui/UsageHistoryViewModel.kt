// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Recorded usage sessions.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UsageHistoryUi(
    val loading: Boolean = true,
    val sessions: List<UsageSession> = emptyList(),
    val hasUsageAccess: Boolean = true,
)

class UsageHistoryViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(UsageHistoryUi())
    val ui: StateFlow<UsageHistoryUi> = _ui.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val list = runCatching { c.usageStore.load() }.getOrDefault(emptyList())
        val access = runCatching { ForegroundApp(c.appContext).hasUsageAccess() }.getOrDefault(false)
        _ui.value = UsageHistoryUi(false, list, access)
    }

    fun delete(id: String) = viewModelScope.launch {
        val list = runCatching { c.usageStore.delete(id) }.getOrDefault(_ui.value.sessions)
        _ui.value = _ui.value.copy(sessions = list)
    }

    fun clearAll() = viewModelScope.launch {
        runCatching { c.usageStore.clear() }
        _ui.value = _ui.value.copy(sessions = emptyList())
    }

    fun find(id: String): UsageSession? = _ui.value.sessions.firstOrNull { it.id == id }
}
