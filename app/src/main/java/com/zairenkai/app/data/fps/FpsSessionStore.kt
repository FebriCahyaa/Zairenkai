// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Persists recorded FPS sessions as a JSON array in app-private storage,
 * newest first, keeping the latest [CAP]. Exposes them as a Flow so the
 * FPS screen updates when the recorder service saves a session.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.fps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class FpsSessionStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "fps_sessions.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val ser = ListSerializer(FpsRecording.serializer())
    private val lock = Mutex()
    private val _sessions = MutableStateFlow<List<FpsRecording>?>(null)

    /** null until first loaded. */
    val sessions: StateFlow<List<FpsRecording>?> = _sessions.asStateFlow()

    suspend fun load(): List<FpsRecording> = lock.withLock { readLocked().also { _sessions.value = it } }

    suspend fun add(rec: FpsRecording) = lock.withLock { writeLocked(listOf(rec) + readLocked()) }

    suspend fun delete(id: String) = lock.withLock { writeLocked(readLocked().filterNot { it.id == id }) }

    suspend fun clear() = lock.withLock { writeLocked(emptyList()) }

    private suspend fun readLocked(): List<FpsRecording> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())
            .sortedByDescending { it.startedAtMs }
    }

    private suspend fun writeLocked(list: List<FpsRecording>) = withContext(Dispatchers.IO) {
        val trimmed = list.sortedByDescending { it.startedAtMs }.take(CAP)
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(json.encodeToString(ser, trimmed))
            tmp.renameTo(file)
        }
        _sessions.value = trimmed
    }

    companion object {
        const val CAP = 100
    }
}
