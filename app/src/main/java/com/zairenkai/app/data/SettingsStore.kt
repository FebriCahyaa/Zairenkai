// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * App preferences backed by DataStore.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zairenkai.app.domain.ProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zairenkai")

data class AppSettings(
    val liteMode: Boolean = false,
    val deviceMitigation: Boolean = false,
    val logLevel: String = "info",
    val activeProfile: ProfileId? = null,
    val autoProfile: Boolean = false,
    val dynamicColor: Boolean = true,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val LITE = booleanPreferencesKey("lite_mode")
        val MITIGATION = booleanPreferencesKey("device_mitigation")
        val LOG = stringPreferencesKey("log_level")
        val PROFILE = stringPreferencesKey("active_profile")
        val AUTO = booleanPreferencesKey("auto_profile")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            liteMode = p[Keys.LITE] ?: false,
            deviceMitigation = p[Keys.MITIGATION] ?: false,
            logLevel = p[Keys.LOG] ?: "info",
            activeProfile = p[Keys.PROFILE]?.let { runCatching { ProfileId.valueOf(it) }.getOrNull() },
            autoProfile = p[Keys.AUTO] ?: false,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
        )
    }

    suspend fun setLite(v: Boolean) = context.dataStore.edit { it[Keys.LITE] = v }
    suspend fun setMitigation(v: Boolean) = context.dataStore.edit { it[Keys.MITIGATION] = v }
    suspend fun setLogLevel(v: String) = context.dataStore.edit { it[Keys.LOG] = v }
    suspend fun setActiveProfile(id: ProfileId?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.PROFILE) else it[Keys.PROFILE] = id.name
    }
    suspend fun setAutoProfile(v: Boolean) = context.dataStore.edit { it[Keys.AUTO] = v }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = v }
}
