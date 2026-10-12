// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Profiles and custom profiles; zperfd is the source of truth.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.R
import com.zairenkai.app.data.*
import com.zairenkai.app.domain.Profile
import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.domain.Profiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ProfilesUi(
    val loading: Boolean = true,
    val engineAvailable: Boolean = false,
    /** Engine truth: desired/effective mode, hold reason. Null when zperfd is unreachable. */
    val status: ZperfStatus? = null,
    /** Modes the device profile defines, keyed by mode id. Empty when unknown. */
    val modes: Map<String, ModeSummary> = emptyMap(),
    /** Stored custom profiles and the built-in modes they derive from; null when unknown. */
    val customs: CustomListResult? = null,
    /** Last explicit profile from local settings; shown only as unverified when the engine is down. */
    val cachedProfile: ProfileId? = null,
    val applying: ProfileId? = null,
    val applyingCustom: String? = null,
    /** The custom profile being edited; null when the editor is closed. */
    val editor: CustomDraft? = null,
    val editorError: UiText? = null,
    val saving: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
) {
    /** The user's persisted choice according to the engine; null means the engine default applies. */
    val selected: Profile? get() = status?.takeIf { it.saved }?.let { Profiles.byMode(it.desired) }
    /** The persisted choice when it is a custom profile. */
    val selectedCustom: CustomProfile?
        get() = status?.takeIf { it.saved && isCustomId(it.desired) }
            ?.let { s -> customs?.profiles?.firstOrNull { it.id == s.desired } }
    val selectedName: UiText?
        get() = selected?.let { uiText(it.nameRes) } ?: selectedCustom?.let { UiText.Raw(it.label) }
            ?: status?.takeIf { it.saved && isCustomId(it.desired) }?.let { UiText.Raw(it.desired) }
    val auto: Boolean get() = selected?.id == ProfileId.AUTO
    val busy: Boolean get() = applying != null || applyingCustom != null || saving
    /** True when the device profile does not define the mode a profile needs. */
    fun unsupported(profile: Profile): Boolean =
        profile.id != ProfileId.AUTO && modes.isNotEmpty() && profile.mode !in modes
    /** Display name of a mode a custom profile derives from. */
    fun baseName(base: String): UiText = Profiles.byMode(base)?.let { uiText(it.nameRes) } ?: UiText.Raw(base)
    fun baseValues(base: String): ModeValues = customs?.bases?.firstOrNull { it.id == base }?.values.orEmpty()
}

class ProfilesViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ProfilesUi())
    val ui: StateFlow<ProfilesUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val cached = c.settings.settings.first().activeProfile
        val available = runCatching { c.zperf.available() }.getOrDefault(false)
        if (!available) {
            _ui.value = _ui.value.copy(loading = false, engineAvailable = false, status = null,
                modes = emptyMap(), customs = null, cachedProfile = cached)
            return@launch
        }
        val status = runCatching { c.zperf.status() }.getOrNull()
        val modes = runCatching { c.zperf.modes() }.getOrNull()?.modes.orEmpty().associateBy { it.id }
        val customs = runCatching { c.zperf.customs() }.getOrNull()
        _ui.value = _ui.value.copy(
            loading = false,
            engineAvailable = status != null,
            status = status,
            modes = modes,
            customs = customs,
            cachedProfile = cached,
        )
    }

    /** Apply an engine mode; [onApplied] runs only after zperfd committed it. */
    private suspend fun applyMode(mode: String, name: UiText, onApplied: suspend () -> Unit = {}) {
        if (!runCatching { c.zperf.available() }.getOrDefault(false)) {
            _ui.value = _ui.value.copy(
                applying = null, applyingCustom = null,
                error = uiText(R.string.apply_engine_not_running),
            )
            return
        }
        val result = runCatching { c.zperf.apply(mode) }.getOrElse {
            ModeApplyResult(ok = false, kind = "protocol", error = it.message)
        }
        if (result.ok) {
            onApplied()
            _ui.value = _ui.value.copy(applying = null, applyingCustom = null,
                notice = profileSuccessMessage(name, result))
        } else {
            _ui.value = _ui.value.copy(applying = null, applyingCustom = null, error = profileFailureMessage(result))
        }
        refresh()
    }

    fun apply(profile: Profile) = viewModelScope.launch {
        _ui.value = _ui.value.copy(applying = profile.id, error = null, notice = null)
        applyMode(profile.mode, uiText(profile.nameRes)) {
            // Local settings only cache the last explicit choice for offline
            // display; the engine status read below is the source of truth.
            if (profile.id != ProfileId.AUTO) c.settings.setActiveProfile(profile.id)
            c.settings.setAutoProfile(profile.id == ProfileId.AUTO)
        }
    }

    fun applyCustom(custom: CustomProfile) = viewModelScope.launch {
        _ui.value = _ui.value.copy(applyingCustom = custom.id, error = null, notice = null)
        applyMode(custom.id, UiText.Raw(custom.label)) { c.settings.setAutoProfile(false) }
    }

    /** Re-apply the saved choice after a kernel/device change (engine hold: revalidation_required). */
    fun confirmSaved() {
        val ui = _ui.value
        ui.selectedCustom?.let { applyCustom(it); return }
        apply(ui.selected ?: return)
    }

    fun setAuto(enabled: Boolean) {
        if (enabled) {
            apply(Profiles.AUTO)
            return
        }
        val fallback = _ui.value.cachedProfile?.takeIf { it != ProfileId.AUTO }?.let(Profiles::byId)
            ?: Profiles.BALANCED
        apply(fallback)
    }

    fun newCustom() {
        val ui = _ui.value
        val bases = ui.customs?.bases.orEmpty().map { it.id }
        val preferred = (ui.selectedCustom?.base ?: ui.selected?.mode)?.takeIf { it in bases }
        val base = preferred ?: "balance".takeIf { it in bases } ?: bases.firstOrNull() ?: return
        _ui.value = ui.copy(editor = CustomDraft(id = "custom-baru", label = "", base = base), editorError = null)
    }

    fun editCustom(custom: CustomProfile) {
        _ui.value = _ui.value.copy(editor = CustomDraft.fromProfile(custom), editorError = null)
    }

    fun updateDraft(draft: CustomDraft) {
        _ui.value = _ui.value.copy(editor = draft, editorError = null)
    }

    fun closeEditor() {
        if (!_ui.value.saving) _ui.value = _ui.value.copy(editor = null, editorError = null)
    }

    fun saveDraft() = viewModelScope.launch {
        val ui = _ui.value
        val editing = ui.editor ?: return@launch
        val existing = ui.customs?.profiles.orEmpty().map { it.id }.toSet()
        val draft = if (editing.isNew) editing.copy(id = newCustomId(editing.label, existing)) else editing
        draft.validate()?.let {
            _ui.value = ui.copy(editorError = it)
            return@launch
        }
        _ui.value = ui.copy(saving = true, editorError = null, error = null, notice = null)
        val result = runCatching { c.zperf.putCustom(draft) }.getOrElse {
            CustomWriteResult(ok = false, id = draft.id, kind = "protocol", error = it.message)
        }
        if (!result.ok) {
            _ui.value = _ui.value.copy(saving = false, editorError = customWriteFailureMessage(result))
            return@launch
        }
        val active = ui.selectedCustom?.id == draft.id
        _ui.value = _ui.value.copy(saving = false, editor = null,
            notice = uiText(R.string.custom_saved, draft.label.trim()))
        refresh().join()
        // The active profile's stored values changed: re-apply so the device
        // runs them now and the outcome is verified like any apply.
        if (active) _ui.value.customs?.profiles?.firstOrNull { it.id == draft.id }?.let { applyCustom(it) }
    }

    fun deleteCustom(custom: CustomProfile) = viewModelScope.launch {
        _ui.value = _ui.value.copy(saving = true, error = null, notice = null)
        val result = runCatching { c.zperf.deleteCustom(custom.id) }.getOrElse {
            CustomWriteResult(ok = false, id = custom.id, kind = "protocol", error = it.message)
        }
        _ui.value = if (result.ok) {
            _ui.value.copy(saving = false, notice = uiText(R.string.custom_deleted, custom.label))
        } else {
            _ui.value.copy(saving = false, error = customWriteFailureMessage(result))
        }
        refresh()
    }

    fun dismissMessages() {
        _ui.value = _ui.value.copy(error = null, notice = null)
    }
}
