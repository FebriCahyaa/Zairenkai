// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * App language list: stored/framework tags map to the shipped languages and
 * the list matches res/xml/locales_config.xml.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AppLanguageTest {
    @Test
    fun tagsMapToShippedLanguages() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromTag("id"))
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromTag("in-ID")) // legacy Indonesian code
        assertEquals(AppLanguage.ENGLISH_UK, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.ENGLISH_US, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.ENGLISH_US, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja-JP"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTag("es-419"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-Hans-CN"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("fr-FR"))
    }

    @Test
    fun localeConfigListsExactlyTheShippedLanguages() {
        val xml = listOf("src/main/res/xml/locales_config.xml", "app/src/main/res/xml/locales_config.xml")
            .map(::File).first { it.exists() }.readText()
        val declared = Regex("""android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(AppLanguage.entries.mapNotNull { it.tag }, declared)
    }
}
