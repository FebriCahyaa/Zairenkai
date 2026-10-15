// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Per-app language. Android 13+ stores it in the framework (LocaleManager),
 * which also lists the languages in the system's per-app language settings
 * (res/xml/locales_config.xml). On Android 12 the choice is kept in a small
 * private preference and applied by wrapping each Activity/Service context.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.util.Locale

/** Languages the app ships; [tag] null follows the system. Labels are endonyms, never translated. */
enum class AppLanguage(val tag: String?, val endonym: String?) {
    SYSTEM(null, null),
    INDONESIAN("id", "Bahasa Indonesia"),
    ENGLISH_UK("en-GB", "English (UK)"),
    ENGLISH_US("en-US", "English (US)"),
    JAPANESE("ja", "日本語"),
    SPANISH("es", "Español"),
    CHINESE_SIMPLIFIED("zh-CN", "简体中文"),
    ;

    companion object {
        /** The shipped language a stored/framework tag stands for; unknown tags follow the system. */
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.let { return it }
            val locale = Locale.forLanguageTag(tag)
            return when (locale.language) {
                "in", "id" -> INDONESIAN
                "en" -> if (locale.country == "GB") ENGLISH_UK else ENGLISH_US
                "ja" -> JAPANESE
                "es" -> SPANISH
                "zh" -> CHINESE_SIMPLIFIED
                else -> SYSTEM
            }
        }
    }
}

object AppLocale {
    private data class FrameworkLocale(val available: Boolean, val tag: String?)

    private const val PREFS = "zk_locale"
    private const val KEY = "tag"
    private const val KEY_INIT = "initialized"
    // True only after LocaleManager accepted our last per-app locale. This lets
    // Android 13+ distinguish an intentional "System default" (empty framework
    // locale list) from an API failure where the stored preference is fallback.
    private const val KEY_FRAMEWORK_SYNCED = "framework_locale_synced"
    /** Zairenkai is an Indonesian-first app, so the very first launch opens in
     *  Indonesian (setup included) rather than following an English device. */
    private const val DEFAULT_TAG = "id"

    /**
     * First launch defaults to Indonesian (including onboarding). On Android
     * 13+, migrate an existing saved language to LocaleManager once. After the
     * framework accepts a locale, its value is authoritative so changes made in
     * Android's App language settings are not overwritten by a stale preference.
     */
    fun ensureDefaultLocale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val initialized = prefs.getBoolean(KEY_INIT, false)
        if (!initialized) {
            prefs.edit(commit = true) {
                if (prefs.getString(KEY, null) == null) putString(KEY, DEFAULT_TAG)
                putBoolean(KEY_INIT, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putBoolean(KEY_FRAMEWORK_SYNCED, false)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !prefs.getBoolean(KEY_FRAMEWORK_SYNCED, false)
        ) {
            // Fresh installs and upgrades from versions that only stored a tag
            // need a one-time migration. If no tag is stored, retain System.
            val legacyTag = prefs.getString(KEY, null)
            if (legacyTag != null || !initialized) {
                syncFramework(context, legacyTag ?: DEFAULT_TAG)
            }
        }
    }

    fun current(context: Context): AppLanguage = AppLanguage.fromTag(effectiveTag(context))

    /**
     * Persist the choice on every Android version. Android 13+ normally
     * recreates the activity when applicationLocales changes; the explicit
     * recreate remains the fallback when LocaleManager is unavailable/fails.
     */
    fun apply(activity: Activity, language: AppLanguage) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            if (language.tag == null) remove(KEY) else putString(KEY, language.tag)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(KEY_FRAMEWORK_SYNCED, false)
            }
        }

        val frameworkBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            readFrameworkLocale(activity)
        } else {
            null
        }
        val frameworkApplied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            syncFramework(activity, language.tag)
        } else {
            false
        }
        val frameworkAfter = if (frameworkApplied) readFrameworkLocale(activity) else null
        // A changed framework locale causes Android to recreate the Activity
        // because locale|layoutDirection are intentionally not consumed in the
        // manifest. If the framework value did not change (for example, an old
        // wrapper still forced Indonesian while LocaleManager was already set
        // to System), recreate explicitly to remove the stale context override.
        val frameworkWillRecreate = frameworkBefore?.available == true &&
            frameworkAfter?.available == true && frameworkBefore.tag != frameworkAfter.tag
        if (!frameworkWillRecreate) activity.recreate()
    }

    /** Apply the effective app language to Activity/Service contexts. */
    fun wrap(base: Context): Context {
        val tag = effectiveTag(base) ?: return base
        val desiredLocales = LocaleList.forLanguageTags(tag)
        if (desiredLocales.isEmpty()) return base

        val config = Configuration(base.resources.configuration)
        config.setLocales(desiredLocales)
        config.setLayoutDirection(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    /**
     * Android's per-app locale is authoritative when it has a selected tag.
     * An empty list means "System default" only after our last sync succeeded;
     * otherwise keep using the stored tag as a safe fallback.
     */
    private fun effectiveTag(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedTag = prefs.getString(KEY, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return savedTag

        val framework = readFrameworkLocale(context)
        if (!framework.available) return savedTag
        return framework.tag ?: if (prefs.getBoolean(KEY_FRAMEWORK_SYNCED, false)) null else savedTag
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun readFrameworkLocale(context: Context): FrameworkLocale {
        return try {
            val manager = context.getSystemService(LocaleManager::class.java)
                ?: return FrameworkLocale(available = false, tag = null)
            val tag = manager.applicationLocales.toLanguageTags().takeIf { it.isNotBlank() }
            FrameworkLocale(available = true, tag = tag)
        } catch (_: Exception) {
            FrameworkLocale(available = false, tag = null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun syncFramework(context: Context, tag: String?): Boolean {
        return try {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return false
            manager.applicationLocales =
                if (tag.isNullOrBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                putBoolean(KEY_FRAMEWORK_SYNCED, true)
            }
            true
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                putBoolean(KEY_FRAMEWORK_SYNCED, false)
            }
            false
        }
    }

}

/** The Activity behind a (possibly wrapped) Compose context. */
fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? Activity }
