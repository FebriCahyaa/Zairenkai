// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * ViewModel factory that injects the AppContainer.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zairenkai.app.AppContainer
import com.zairenkai.app.data.*

@Suppress("UNCHECKED_CAST")
class VmFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(OverviewViewModel::class.java) -> OverviewViewModel(c)
        modelClass.isAssignableFrom(MonitorViewModel::class.java) -> MonitorViewModel(c)
        modelClass.isAssignableFrom(UsageHistoryViewModel::class.java) -> UsageHistoryViewModel(c)
        modelClass.isAssignableFrom(TweaksViewModel::class.java) -> TweaksViewModel(c)
        modelClass.isAssignableFrom(ProfilesViewModel::class.java) -> ProfilesViewModel(c)
        modelClass.isAssignableFrom(SystemViewModel::class.java) -> SystemViewModel(c)
        modelClass.isAssignableFrom(RuntimeViewModel::class.java) -> RuntimeViewModel(c)
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(c)
        modelClass.isAssignableFrom(com.zairenkai.app.ui.onboarding.OnboardingViewModel::class.java) ->
            com.zairenkai.app.ui.onboarding.OnboardingViewModel(c)
        else -> throw IllegalArgumentException("unknown VM $modelClass")
    } as T
}
