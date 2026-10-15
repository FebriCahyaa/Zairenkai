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
import javax.xml.parsers.DocumentBuilderFactory

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
    @Test
    fun indonesianResourcesCoverEveryTranslatableDefaultString() {
        val root = listOf(File("app/src/main/res"), File("src/main/res"))
            .first { it.resolve("values").isDirectory }
        val baseDir = root.resolve("values")
        // Android versions before API 35 use the legacy Indonesian resource
        // qualifier `in`; Android 15+ may request the ISO language code `id`.
        // Keep both resource directories byte-for-byte aligned for all SDKs.
        val resourceDirs = listOf(root.resolve("values-in"), root.resolve("values-id"))
        val factory = DocumentBuilderFactory.newInstance()

        fun translatableNames(file: File): Set<String> {
            if (!file.exists()) return emptySet()
            val doc = factory.newDocumentBuilder().parse(file)
            val result = linkedSetOf<String>()
            val nodes = doc.documentElement.childNodes
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                if (node.nodeName !in setOf("string", "plurals", "string-array")) continue
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                val translatable = node.attributes?.getNamedItem("translatable")?.nodeValue
                if (translatable != "false") result += name
            }
            return result
        }

        val baseFiles = baseDir.listFiles { f -> f.extension == "xml" }.orEmpty()
        resourceDirs.forEach { localeDir ->
            baseFiles.forEach { baseFile ->
                val localizedFile = localeDir.resolve(baseFile.name)
                val missing = translatableNames(baseFile) - translatableNames(localizedFile)
                assertEquals(
                    "Missing Indonesian translation(s) in ${localeDir.name}/${baseFile.name}",
                    emptySet<String>(),
                    missing,
                )
            }
        }

        val canonical = resourceDirs.first().listFiles { f -> f.extension == "xml" }.orEmpty()
            .associate { it.name to it.readText() }
        val isoCode = resourceDirs.last().listFiles { f -> f.extension == "xml" }.orEmpty()
            .associate { it.name to it.readText() }
        assertEquals("values-in and values-id resources must stay in sync", canonical, isoCode)
    }

}
