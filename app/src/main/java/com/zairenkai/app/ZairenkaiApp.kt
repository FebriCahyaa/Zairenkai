// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Application + tiny service locator (no DI framework needed).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app

import android.app.Application
import android.content.Context
import com.zairenkai.app.data.SettingsStore
import com.zairenkai.app.data.UsageSessionStore
import com.zairenkai.app.data.ZkfcRepository
import com.zairenkai.app.data.LicenseServerEnrollment
import com.zairenkai.app.data.ZperfClient
import com.zairenkai.app.data.AppRuntimeClients
import com.zairenkai.app.core.runtime.RuntimeSnapshotRepository
import com.zairenkai.app.data.fps.FpsSessionStore
import com.zairenkai.app.data.monitor.LiveMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(app: Application) {
    val appContext: Context = app.applicationContext
    val zperf = ZperfClient()
    val repository = ZkfcRepository(zperf = zperf)
    val runtimeClients = AppRuntimeClients(repository, zperf)
    val runtime = RuntimeSnapshotRepository(runtimeClients)
    val settings = SettingsStore(app)
    val usageStore = UsageSessionStore(app)
    val fpsStore = FpsSessionStore(app)
    val updater = com.zairenkai.app.data.update.Updater(app.applicationContext)
    val licenseServerEnrollment = LicenseServerEnrollment(app.applicationContext)

    /** Process-wide scope for samplers shared across screens. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val live = LiveMonitor(app, repository, zperf, appScope)
}

class ZairenkaiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First launch opens in Indonesian (setup included); runs once, then the
        // user's own language choice is respected.
        com.zairenkai.app.ui.locale.AppLocale.ensureDefaultLocale(this)
        container = AppContainer(this)
    }
}
