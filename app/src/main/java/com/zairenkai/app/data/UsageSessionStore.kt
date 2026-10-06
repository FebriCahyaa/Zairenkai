// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Persists recorded usage sessions as a JSON array in the app's private
 * storage. Keeps the newest [cap] sessions.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class UsageSessionStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "usage_sessions.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(UsageSession.serializer())
    private val cap = 50

    suspend fun load(): List<UsageSession> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString(ser, file.readText()) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.endedAtMs }
    }

    suspend fun save(sessions: List<UsageSession>) = withContext(Dispatchers.IO) {
        val trimmed = sessions.sortedByDescending { it.endedAtMs }.take(cap)
        runCatching { file.writeText(json.encodeToString(ser, trimmed)) }
        Unit
    }

    suspend fun add(session: UsageSession): List<UsageSession> {
        val all = load() + session
        save(all)
        return load()
    }

    suspend fun delete(id: String): List<UsageSession> {
        val all = load().filterNot { it.id == id }
        save(all)
        return all
    }

    suspend fun clear(): List<UsageSession> = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        emptyList()
    }
}
