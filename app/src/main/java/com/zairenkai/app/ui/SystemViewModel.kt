// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * System, security and license state.
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

data class SystemUi(
    val loading: Boolean = true,
    val security: SecurityResult? = null,
    val license: LicenseResult? = null,
    val policy: PolicyResult? = null,
    val info: InfoResult? = null,
    val zperf: ZperfProbe? = null,
)

class SystemViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SystemUi())
    val ui: StateFlow<SystemUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _ui.value = _ui.value.copy(loading = true)
        _ui.value = SystemUi(
            loading = false,
            security = runCatching { c.repository.security() }.getOrNull(),
            license = runCatching { c.repository.license() }.getOrNull(),
            policy = runCatching { c.repository.policy() }.getOrNull(),
            info = runCatching { c.repository.info() }.getOrNull(),
            zperf = runCatching { c.zperf.probe() }.getOrNull(),
        )
    }
}
