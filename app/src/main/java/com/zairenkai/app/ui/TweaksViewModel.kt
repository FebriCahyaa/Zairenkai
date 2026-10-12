// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Runtime tweaks through zperfd.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.R
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TweaksUi(
    val loading: Boolean = true,
    val lite: Boolean = false,
    val tweaks: List<Tweak> = emptyList(),
    /** False when the list came from the zkfctl fallback: nothing is writable. */
    val writable: Boolean = false,
    /** Set when the list itself could not be read (not the same as "no tunables"). */
    val loadError: UiText? = null,
    val busyId: String? = null,
    val error: UiText? = null,
    val notice: UiText? = null,
)

class TweaksViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(TweaksUi())
    val ui: StateFlow<TweaksUi> = _ui.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val lite = c.settings.settings.first().liteMode
        _ui.value = _ui.value.copy(loading = true, lite = lite)
        if (!c.repository.rootAvailable()) {
            _ui.value = TweaksUi(loading = false, lite = lite, loadError = uiText(R.string.tweaks_load_no_root))
            return@launch
        }
        val list = runCatching { c.repository.tweaks(lite) }
        _ui.value = list.fold(
            onSuccess = {
                TweaksUi(
                    loading = false,
                    lite = lite,
                    tweaks = it.tweaks,
                    writable = it.source == "zperfd",
                    loadError = if (!it.ok) (it.error?.let { e -> UiText.Raw(e) } ?: uiText(R.string.tweaks_load_failed)) else null,
                )
            },
            onFailure = {
                TweaksUi(
                    loading = false,
                    lite = lite,
                    loadError = uiText(
                        R.string.tweaks_load_failed_detail,
                        it.message ?: uiText(R.string.tweaks_load_error_generic),
                    ),
                )
            },
        )
    }

    fun set(id: String, value: String) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busyId = id, error = null, notice = null)
        val lite = _ui.value.lite
        val result = runCatching { c.repository.setTweak(id, value, lite) }.getOrElse {
            TweakWriteResult(ok = false, id = id, kind = "protocol", error = it.message)
        }
        if (!result.ok) {
            _ui.value = _ui.value.copy(busyId = null, error = tweakFailureMessage(result))
            return@launch
        }
        // Re-read from the engine so the row shows the observed value, not the request.
        val list = runCatching { c.repository.tweaks(lite) }.getOrNull()
        _ui.value = _ui.value.copy(
            busyId = null,
            tweaks = list?.tweaks ?: _ui.value.tweaks,
            notice = tweakSuccessMessage(result),
        )
    }

    fun dismissMessages() {
        _ui.value = _ui.value.copy(error = null, notice = null)
    }
}
