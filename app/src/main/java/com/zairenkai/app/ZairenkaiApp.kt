// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Application + tiny service locator (no DI framework needed).
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app

import android.app.Application
import com.zairenkai.app.data.SettingsStore
import com.zairenkai.app.data.ZkfcRepository

class AppContainer(app: Application) {
    val repository = ZkfcRepository()
    val settings = SettingsStore(app)
}

class ZairenkaiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
