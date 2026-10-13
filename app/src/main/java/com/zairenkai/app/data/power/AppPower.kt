// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Per-app battery use. These figures are Android's OWN estimate, read from
 * `dumpsys batterystats` ("Estimated power use (mAh)") — Zairenkai does not
 * model power itself and invents nothing. batterystats is a privileged dump,
 * so this needs root; without it the Battery screen shows no per-app list
 * rather than a fabricated one.
 *
 * Each "Uid u0aNNN: <mAh>" line is attributed to the packages sharing that
 * uid, resolved through the package manager. System uids keep their name.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.power

import android.content.Context
import android.content.pm.PackageManager
import com.zairenkai.app.data.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One app's share of the estimated drain since the last full charge. */
data class AppPower(
    val uid: Int,
    val packageName: String,
    val label: String,
    val mah: Double,
    val isSystem: Boolean,
) {
    fun pctOf(totalMah: Double): Int = if (totalMah > 0) (mah / totalMah * 100).toInt() else 0
}

data class AppPowerReport(
    val computedDrainMah: Double,
    val apps: List<AppPower>,
) {
    val totalMah: Double get() = apps.sumOf { it.mah }
}

object AppPowerReader {
    /** Null when root is unavailable or batterystats could not be read/parsed. */
    suspend fun read(context: Context): AppPowerReport? = withContext(Dispatchers.IO) {
        if (!RootShell.isRootAvailable()) return@withContext null
        val r = RootShell.run("dumpsys batterystats", 8000)
        if (!r.ok || r.stdout.isBlank()) return@withContext null
        val parsed = parse(r.stdout)
        if (parsed.second.isEmpty()) return@withContext null
        val pm = context.packageManager
        val apps = parsed.second
            .map { (uid, mah) -> resolve(pm, uid, mah) }
            .sortedByDescending { it.mah }
        AppPowerReport(parsed.first, apps)
    }

    /** Pure parser: text -> (computed drain mAh, list of uid -> mAh). Unit tested. */
    internal fun parse(text: String): Pair<Double, List<Pair<Int, Double>>> {
        var inSection = false
        var computed = 0.0
        val out = ArrayList<Pair<Int, Double>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Estimated power use")) { inSection = true; continue }
            if (!inSection) continue
            // The section ends at the next blank line after it has started.
            if (line.isEmpty() && out.isNotEmpty()) break
            if (line.startsWith("Capacity:")) {
                Regex("Computed drain:\\s*([0-9.]+)").find(line)?.let { computed = it.groupValues[1].toDoubleOrNull() ?: 0.0 }
                continue
            }
            // "Uid u0a234: 45.6 ( cpu=30.0 ... )"  — take the number before any '('.
            val m = Regex("^Uid\\s+(\\S+):\\s*([0-9.]+)").find(line) ?: continue
            val uid = uidOf(m.groupValues[1]) ?: continue
            val mah = m.groupValues[2].toDoubleOrNull() ?: continue
            if (mah > 0) out += uid to mah
        }
        return computed to out
    }

    /** "u0a234" -> 10234, "u10a5" -> 1010005, "1000" -> 1000. */
    internal fun uidOf(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val m = Regex("^u(\\d+)a(\\d+)$").find(token) ?: return null
        val user = m.groupValues[1].toInt()
        val appId = m.groupValues[2].toInt()
        return user * 100_000 + 10_000 + appId
    }

    private fun resolve(pm: PackageManager, uid: Int, mah: Double): AppPower {
        val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
        val pkg = pkgs?.firstOrNull()
        val system = uid < 10_000 || (uid % 100_000) < 10_000
        val label = when {
            pkg != null -> runCatching {
                pm.getApplicationInfo(pkg, 0).let { pm.getApplicationLabel(it).toString() }
            }.getOrDefault(pkg)
            uid == 1000 -> "Android system"
            uid == 0 -> "Kernel (root)"
            else -> "uid $uid"
        }
        return AppPower(uid, pkg ?: "uid:$uid", label, mah, system)
    }
}
