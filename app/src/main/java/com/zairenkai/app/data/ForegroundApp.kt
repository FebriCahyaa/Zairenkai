// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Resolve the current foreground package. Primary path is UsageStatsManager
 * (needs the "Usage access" special permission); when that is unavailable we
 * fall back to a root dumpsys query. App labels come from PackageManager.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

class ForegroundApp(context: Context) {
    private val app = context.applicationContext
    private val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val pm = app.packageManager
    private val labelCache = HashMap<String, String>()
    private var lastRootPkg: String? = null
    private var lastRootAtMs = 0L

    /** True if the app holds "Usage access" (PACKAGE_USAGE_STATS). */
    fun hasUsageAccess(): Boolean {
        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_IGNORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Best-effort current foreground package, or null. */
    suspend fun current(): String? {
        usm?.let { m ->
            val now = System.currentTimeMillis()
            val events = runCatching { m.queryEvents(now - 12_000, now) }.getOrNull()
            if (events != null) {
                var last: String? = null
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                    ) {
                        last = e.packageName
                    }
                }
                if (!last.isNullOrBlank()) return last
            }
        }
        // Root fallback, throttled to ~5s so we do not spawn su every tick.
        val now = System.currentTimeMillis()
        if (now - lastRootAtMs > 5_000) {
            lastRootAtMs = now
            if (RootShell.isRootAvailable()) {
                val r = RootShell.run(
                    "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n1",
                    3000,
                )
                if (r.ok) {
                    Regex("""\s([a-zA-Z0-9_.]+)/""").find(r.stdout)?.groupValues?.getOrNull(1)
                        ?.let { lastRootPkg = it }
                }
            }
        }
        return lastRootPkg
    }

    fun label(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
    }
}
