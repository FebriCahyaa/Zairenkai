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

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            AppLanguage.fromTag(if (locales.isEmpty) null else locales[0].toLanguageTag())
        } else {
            AppLanguage.fromTag(stored(context))
        }

    /**
     * Switch the app language. Android 13+ recreates running activities
     * itself; on Android 12 the activity is recreated here. ViewModels
     * survive the recreation, so in-progress screens keep their state.
     */
    fun apply(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } else {
            // Written synchronously: the recreated activity reads it in attachBaseContext.
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                if (language.tag == null) remove(KEY) else putString(KEY, language.tag)
            }
            activity.recreate()
        }
    }

    /** Android 12: apply the stored language to an Activity/Service base context. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = stored(base) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }

    private fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}

/** The Activity behind a (possibly wrapped) Compose context. */
fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? Activity }
