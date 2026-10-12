// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Groups per-core monitor samples into the device's CPU clusters (one per
 * cpufreq policy reported by zperfd) and names them by their rank. Pure and
 * unit tested.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.cpu

import com.zairenkai.app.data.CpuSample
import com.zairenkai.app.data.ZperfPolicy

enum class ClusterRole { SINGLE, EFFICIENCY, BALANCED, PERFORMANCE, PRIME }

data class CpuCluster(
    val policy: String,
    val role: ClusterRole,
    val cores: List<CpuSample>,
    val governor: String?,
    val minKhz: Long,
    val maxKhz: Long,
) {
    private val online get() = cores.filter { it.online && it.loadPct in 0..100 }

    /** Mean load of the online cores, or null when none reported a load. */
    val loadPct: Int? get() = online.takeIf { it.isNotEmpty() }?.let { c -> c.sumOf { it.loadPct } / c.size }

    /** Highest current core frequency in the cluster. */
    val curKhz: Long? get() = cores.mapNotNull { it.curKhz.takeIf { f -> f > 0 } }.maxOrNull()

    /** Current frequency as a share of the hardware range. */
    val freqPct: Int?
        get() {
            val cur = curKhz ?: return null
            if (maxKhz <= minKhz) return null
            return (((cur - minKhz) * 100) / (maxKhz - minKhz)).toInt().coerceIn(0, 100)
        }
}

object CpuClusters {
    /**
     * Clusters ordered from the slowest to the fastest policy. Cores without
     * a policy (no zperfd, or an unknown layout) become one cluster.
     */
    fun group(policies: List<ZperfPolicy>, cores: List<CpuSample>): List<CpuCluster> {
        val byCpu = cores.associateBy { it.cpu }
        val known = policies.filter { it.cpus.isNotEmpty() }.sortedBy { it.maxHw }
        if (known.isEmpty()) {
            return if (cores.isEmpty()) emptyList()
            else listOf(CpuCluster("", ClusterRole.SINGLE, cores.sortedBy { it.cpu }, null, 0, 0))
        }
        val roles = roles(known.size)
        val out = known.mapIndexed { i, p ->
            CpuCluster(
                policy = p.name,
                role = roles[i],
                cores = p.cpus.sorted().map { byCpu[it] ?: CpuSample(cpu = it, online = false) },
                governor = p.governor,
                minKhz = p.minHw,
                maxKhz = p.maxHw,
            )
        }
        val covered = known.flatMap { it.cpus }.toSet()
        val rest = cores.filter { it.cpu !in covered }
        return if (rest.isEmpty()) out else out + CpuCluster("", ClusterRole.SINGLE, rest.sortedBy { it.cpu }, null, 0, 0)
    }

    private fun roles(n: Int): List<ClusterRole> = when (n) {
        1 -> listOf(ClusterRole.SINGLE)
        2 -> listOf(ClusterRole.EFFICIENCY, ClusterRole.PERFORMANCE)
        3 -> listOf(ClusterRole.EFFICIENCY, ClusterRole.PERFORMANCE, ClusterRole.PRIME)
        else -> List(n) { i ->
            when (i) {
                0 -> ClusterRole.EFFICIENCY
                n - 1 -> ClusterRole.PRIME
                1 -> ClusterRole.BALANCED
                else -> ClusterRole.PERFORMANCE
            }
        }
    }
}
