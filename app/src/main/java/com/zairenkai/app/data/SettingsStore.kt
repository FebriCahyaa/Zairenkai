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
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zairenkai.app.domain.ProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zairenkai")

data class AppSettings(
    val onboarded: Boolean = false,
    val liteMode: Boolean = false,
    val deviceMitigation: Boolean = false,
    val logLevel: String = "info",
    val activeProfile: ProfileId? = null,
    val autoProfile: Boolean = false,
    val dynamicColor: Boolean = true,
    // Theme
    val themeMode: String = "system",   // system | light | dark
    val accent: String = "indigo",      // indigo | violet | aqua | amber | rose | mono
    val glass: Boolean = false,
    val glassOpacity: Float = 0.6f,     // 0.35 .. 0.95
    val glassBlur: Float = 18f,         // 0 .. 40 (dp)
    val glassTint: Float = 0.12f,       // 0 .. 0.4 accent tint
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val LITE = booleanPreferencesKey("lite_mode")
        val MITIGATION = booleanPreferencesKey("device_mitigation")
        val LOG = stringPreferencesKey("log_level")
        val PROFILE = stringPreferencesKey("active_profile")
        val AUTO = booleanPreferencesKey("auto_profile")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val GLASS = booleanPreferencesKey("glass")
        val GLASS_OPACITY = floatPreferencesKey("glass_opacity")
        val GLASS_BLUR = floatPreferencesKey("glass_blur")
        val GLASS_TINT = floatPreferencesKey("glass_tint")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboarded = p[Keys.ONBOARDED] ?: false,
            liteMode = p[Keys.LITE] ?: false,
            deviceMitigation = p[Keys.MITIGATION] ?: false,
            logLevel = p[Keys.LOG] ?: "info",
            activeProfile = p[Keys.PROFILE]?.let { runCatching { ProfileId.valueOf(it) }.getOrNull() },
            autoProfile = p[Keys.AUTO] ?: false,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            themeMode = p[Keys.THEME_MODE] ?: "system",
            accent = p[Keys.ACCENT] ?: "indigo",
            glass = p[Keys.GLASS] ?: false,
            glassOpacity = p[Keys.GLASS_OPACITY] ?: 0.6f,
            glassBlur = p[Keys.GLASS_BLUR] ?: 18f,
            glassTint = p[Keys.GLASS_TINT] ?: 0.12f,
        )
    }

    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDED] = v }
    suspend fun setLite(v: Boolean) = context.dataStore.edit { it[Keys.LITE] = v }
    suspend fun setMitigation(v: Boolean) = context.dataStore.edit { it[Keys.MITIGATION] = v }
    suspend fun setLogLevel(v: String) = context.dataStore.edit { it[Keys.LOG] = v }
    suspend fun setActiveProfile(id: ProfileId?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.PROFILE) else it[Keys.PROFILE] = id.name
    }
    suspend fun setAutoProfile(v: Boolean) = context.dataStore.edit { it[Keys.AUTO] = v }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = v }
    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[Keys.THEME_MODE] = v }
    suspend fun setAccent(v: String) = context.dataStore.edit { it[Keys.ACCENT] = v }
    suspend fun setGlass(v: Boolean) = context.dataStore.edit { it[Keys.GLASS] = v }
    suspend fun setGlassOpacity(v: Float) = context.dataStore.edit { it[Keys.GLASS_OPACITY] = v }
    suspend fun setGlassBlur(v: Float) = context.dataStore.edit { it[Keys.GLASS_BLUR] = v }
    suspend fun setGlassTint(v: Float) = context.dataStore.edit { it[Keys.GLASS_TINT] = v }
}
