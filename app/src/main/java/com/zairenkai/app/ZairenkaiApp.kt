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
import com.zairenkai.app.data.ZperfClient
import com.zairenkai.app.data.AppRuntimeClients
import com.zairenkai.app.core.runtime.RuntimeSnapshotRepository

class AppContainer(app: Application) {
    val appContext: Context = app.applicationContext
    val zperf = ZperfClient()
    val repository = ZkfcRepository(zperf = zperf)
    val runtimeClients = AppRuntimeClients(repository, zperf)
    val runtime = RuntimeSnapshotRepository(runtimeClients)
    val settings = SettingsStore(app)
    val usageStore = UsageSessionStore(app)
}

class ZairenkaiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
