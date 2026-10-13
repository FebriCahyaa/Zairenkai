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
    private const val PREFS = "zk_locale"
    private const val KEY = "tag"
    private const val KEY_INIT = "initialized"
    /** Zairenkai is an Indonesian-first app, so the very first launch opens in
     *  Indonesian (setup included) rather than following an English device. */
    private const val DEFAULT_TAG = "id"

    /*
     * The chosen language tag is kept in our own preference and applied by
     * wrapping every Activity/Service base context on ALL Android versions.
     * We do NOT depend on the framework LocaleManager (Android 13+): with a
     * plain (non-AppCompat) theme its per-app locale was not switching the UI
     * — the app stayed English even when Indonesian was selected. Wrapping the
     * context with createConfigurationContext always loads values-<tag>.
     */

    /**
     * On the first ever launch, default the app language to Indonesian so the
     * setup flow is in Indonesian. Runs once; afterwards the user's own choice
     * (including "follow system") is kept. Call from Application.onCreate.
     */
    fun ensureDefaultLocale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INIT, false)) return
        prefs.edit(commit = true) {
            if (prefs.getString(KEY, null) == null) putString(KEY, DEFAULT_TAG)
            putBoolean(KEY_INIT, true)
        }
        // Keep the system per-app-language list in sync where available (best
        // effort only — the context wrap is what actually applies the locale).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { syncFramework(context, DEFAULT_TAG) }
        }
    }

    fun current(context: Context): AppLanguage = AppLanguage.fromTag(stored(context))

    /**
     * Switch the app language: persist the choice and recreate the activity so
     * its base context is re-wrapped with the new locale. ViewModels survive
     * the recreation, so in-progress screens keep their state.
     */
    fun apply(activity: Activity, language: AppLanguage) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            if (language.tag == null) remove(KEY) else putString(KEY, language.tag)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { syncFramework(activity, language.tag) }
        }
        activity.recreate()
    }

    /** Apply the stored language to an Activity/Service base context. */
    fun wrap(base: Context): Context {
        val tag = stored(base) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun syncFramework(context: Context, tag: String?) {
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        lm.applicationLocales =
            if (tag.isNullOrBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    private fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}

/** The Activity behind a (possibly wrapped) Compose context. */
fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? Activity }
