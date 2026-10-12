// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Runtime platform snapshot.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.R
import com.zairenkai.app.core.runtime.RuntimeSnapshot
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RuntimeUi(
    val loading: Boolean = true,
    val snapshot: RuntimeSnapshot? = null,
    val error: UiText? = null,
)

class RuntimeViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(RuntimeUi())
    val ui: StateFlow<RuntimeUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _ui.value = _ui.value.copy(loading = true, error = null)
        runCatching { c.runtime.observe() }
            .onSuccess { _ui.value = RuntimeUi(false, it, null) }
            .onFailure { e ->
                // Localized reason, then the exception text as technical detail.
                val reason = uiText(R.string.runtime_observe_failed)
                _ui.value = RuntimeUi(false, null, e.message?.let { UiText.Join(listOf(reason, UiText.Raw(it))) } ?: reason)
            }
    }
}
